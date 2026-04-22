/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Minimal JNI wrapper over OpenH264 (Cisco)'s SVC encoder/decoder C API.
 * Statically linked against libopenh264.a; produces a single
 * libscev_h264.so per platform with no runtime dependency on the host
 * having libopenh264 installed.
 *
 * Design goals, in order:
 *   1. Encode one YUV I420 frame at a time → emit H.264 NAL units into
 *      a caller-provided byte buffer.
 *   2. Decode one access-unit worth of NAL units → emit YUV I420 into
 *      a caller-provided byte buffer, along with decoded dimensions.
 *   3. No callbacks into the JVM from native code — the native side
 *      never holds a JNIEnv* reference across a Java call return.
 *   4. Zero per-frame heap allocation on the native side. All buffers
 *      either live on the encoder/decoder internals or come from the
 *      Java side pinned via GetByteArrayElements.
 */

#include <jni.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "codec_api.h"
#include "codec_app_def.h"
#include "codec_def.h"

/* -------------------------------------------------------------- */
/* Error codes returned to Java. Non-negative values are byte     */
/* counts; negatives are well-known errors.                       */
/* -------------------------------------------------------------- */

#define SCEV_H264_ERR_INIT          (-1)   /* create/init failed     */
#define SCEV_H264_ERR_ENCODE        (-2)   /* EncodeFrame returned non-zero */
#define SCEV_H264_ERR_OUTPUT_TOO_SMALL (-3) /* caller's outBuf too small */
#define SCEV_H264_ERR_DECODE        (-4)   /* DecodeFrameNoDelay error */
#define SCEV_H264_ERR_NO_FRAME      (-5)   /* decode succeeded but produced no output */
#define SCEV_H264_ERR_NULL_ARG      (-6)

/* -------------------------------------------------------------- */
/* Encoder                                                        */
/* -------------------------------------------------------------- */

/**
 * Allocate + initialize an encoder for the given frame size, target
 * bitrate (bits/sec), and frame rate. Returns a handle (cast of
 * ISVCEncoder*) or 0 on failure.
 *
 * Tuned for screen content (terminals, desktops, editors) rather than
 * the camera-video default:
 *
 *   - iUsageType = SCREEN_CONTENT_REAL_TIME flips OpenH264's internal
 *     RD tradeoffs toward the sharp-edge, high-frequency regime. The
 *     camera preset smears text on heavy motion (page scrolls) which
 *     shows up as "ghosting".
 *
 *   - iRCMode = RC_BITRATE_MODE + iMaxBitrate = 2×target gives the rate
 *     controller headroom to spike on full-frame changes (scroll, mode
 *     switch) without triggering VBV overflow. Without the cap it tries
 *     to hit the target every frame and burns quality on flat content.
 *
 *   - bEnableFrameSkip = false is the critical anti-shadowing knob. The
 *     default true lets the RC drop frames when budget is exceeded; the
 *     decoder then holds the previous picture for multiple ticks, which
 *     the viewer perceives as trails/ghosts on scrolling text. With
 *     skip disabled the encoder always emits a frame, degrading quality
 *     for that one frame under budget pressure instead of dropping it.
 *
 *   - uiIntraPeriod = 2×fps bounds decoder recovery time to ~2 s. The
 *     caller (ComputerCaseBlockEntity) has its own forced-IDR schedule
 *     at 40 frames / 2 s; we mirror that here so implicit and explicit
 *     schedules agree. Frequent IDRs also help any late-joining client.
 */
JNIEXPORT jlong JNICALL
Java_lekkit_scev_codec_H264Native_createEncoder(JNIEnv *env, jclass cls,
                                                jint width, jint height,
                                                jint bitrate, jint fps) {
    (void)env; (void)cls;
    ISVCEncoder *enc = NULL;
    if (WelsCreateSVCEncoder(&enc) != 0 || enc == NULL) {
        return 0;
    }

    SEncParamExt param;
    /* GetDefaultParams fills in a sane baseline (thread count, QP
     * bounds, loop filter, slice config) so we only override the fields
     * we actually care about. */
    if ((*enc)->GetDefaultParams(enc, &param) != 0) {
        WelsDestroySVCEncoder(enc);
        return 0;
    }

    param.iUsageType              = SCREEN_CONTENT_REAL_TIME;
    param.iPicWidth               = width;
    param.iPicHeight              = height;
    param.iTargetBitrate          = bitrate;
    param.iMaxBitrate             = bitrate * 2;
    param.iRCMode                 = RC_BITRATE_MODE;
    param.fMaxFrameRate           = (float)fps;
    param.iSpatialLayerNum        = 1;
    param.uiIntraPeriod           = (unsigned int)(fps * 2);
    param.bEnableFrameSkip        = false;
    /* GetDefaultParams turns these on but OpenH264 force-disables them
     * for screen content and warns on every init. Set them off ourselves
     * to keep the log clean — the RD cost they'd add isn't applicable
     * to our workload anyway. */
    param.bEnableAdaptiveQuant       = false;
    param.bEnableBackgroundDetection = false;

    /* Single spatial layer config. OpenH264 requires the per-layer
     * width/height/bitrate/framerate to match (or be consistent with)
     * the top-level values; leaving uiProfileIdc at PRO_UNKNOWN lets
     * OpenH264 pick a profile that matches the other knobs. */
    SSpatialLayerConfig *layer = &param.sSpatialLayers[0];
    layer->iVideoWidth        = width;
    layer->iVideoHeight       = height;
    layer->fFrameRate         = (float)fps;
    layer->iSpatialBitrate    = bitrate;
    layer->iMaxSpatialBitrate = bitrate * 2;

    if ((*enc)->InitializeExt(enc, &param) != 0) {
        WelsDestroySVCEncoder(enc);
        return 0;
    }

    return (jlong)(intptr_t)enc;
}

/**
 * Encode one YUV I420 frame. {@code yuvIn} is expected to be
 * {@code width*height*3/2} bytes in the layout Y (W×H), then U (W/2×H/2),
 * then V (W/2×H/2). {@code nalOut} is written to with the concatenated
 * NAL bytes produced for this access unit. Returns number of bytes
 * written to {@code nalOut}, or a negative error code.
 *
 * The first encoded frame is always an IDR carrying SPS+PPS+I-slice
 * NAL units; subsequent frames are P (or periodic IDR per the
 * encoder's internal schedule).
 */
JNIEXPORT jint JNICALL
Java_lekkit_scev_codec_H264Native_encodeFrame(JNIEnv *env, jclass cls,
                                              jlong handle,
                                              jbyteArray yuvIn,
                                              jint width, jint height,
                                              jbyteArray nalOut) {
    (void)cls;
    if (handle == 0 || yuvIn == NULL || nalOut == NULL) return SCEV_H264_ERR_NULL_ARG;
    ISVCEncoder *enc = (ISVCEncoder *)(intptr_t)handle;

    jbyte *yuv = (*env)->GetByteArrayElements(env, yuvIn, NULL);
    if (yuv == NULL) return SCEV_H264_ERR_NULL_ARG;

    SSourcePicture src;
    memset(&src, 0, sizeof(src));
    src.iColorFormat = videoFormatI420;
    src.iPicWidth    = width;
    src.iPicHeight   = height;
    src.iStride[0]   = width;
    src.iStride[1]   = width / 2;
    /* stride[2] is ignored for I420 but harmless to set for clarity. */
    src.iStride[2]   = width / 2;
    src.pData[0]     = (uint8_t *)yuv;
    src.pData[1]     = (uint8_t *)yuv + width * height;
    src.pData[2]     = (uint8_t *)yuv + width * height + (width / 2) * (height / 2);

    SFrameBSInfo bsInfo;
    memset(&bsInfo, 0, sizeof(bsInfo));
    int ret = (*enc)->EncodeFrame(enc, &src, &bsInfo);

    (*env)->ReleaseByteArrayElements(env, yuvIn, yuv, JNI_ABORT);

    if (ret != 0) return SCEV_H264_ERR_ENCODE;
    if (bsInfo.eFrameType == videoFrameTypeSkip) return 0;

    jsize outCapacity = (*env)->GetArrayLength(env, nalOut);
    jbyte *outPtr = (*env)->GetByteArrayElements(env, nalOut, NULL);
    if (outPtr == NULL) return SCEV_H264_ERR_NULL_ARG;

    /* Concatenate every NAL unit from every emitted layer. OpenH264's
     * layout: bsInfo.sLayerInfo[i].pBsBuf contains sum(pNalLengthInByte[])
     * bytes of NALs (with start codes already embedded by the encoder),
     * followed by the next layer's buffer. For single-layer real-time
     * encode we'll typically see iLayerNum == 1.
     */
    int totalBytes = 0;
    for (int i = 0; i < bsInfo.iLayerNum; i++) {
        SLayerBSInfo *layer = &bsInfo.sLayerInfo[i];
        int layerBytes = 0;
        for (int n = 0; n < layer->iNalCount; n++) layerBytes += layer->pNalLengthInByte[n];
        if (totalBytes + layerBytes > outCapacity) {
            (*env)->ReleaseByteArrayElements(env, nalOut, outPtr, JNI_ABORT);
            return SCEV_H264_ERR_OUTPUT_TOO_SMALL;
        }
        memcpy(outPtr + totalBytes, layer->pBsBuf, layerBytes);
        totalBytes += layerBytes;
    }

    (*env)->ReleaseByteArrayElements(env, nalOut, outPtr, 0);
    return totalBytes;
}

JNIEXPORT void JNICALL
Java_lekkit_scev_codec_H264Native_destroyEncoder(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    if (handle == 0) return;
    ISVCEncoder *enc = (ISVCEncoder *)(intptr_t)handle;
    (*enc)->Uninitialize(enc);
    WelsDestroySVCEncoder(enc);
}

/**
 * Request the next encoded frame be an IDR (carrying SPS+PPS and a
 * full I-slice). Used to bound decoder recovery time for late-joining
 * clients: without periodic IDRs, a client opening a monitor screen
 * between keyframes can't decode until the next scheduled IDR.
 *
 * Returns 0 on success, non-zero on encoder error.
 */
JNIEXPORT jint JNICALL
Java_lekkit_scev_codec_H264Native_forceIntraFrame(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    if (handle == 0) return SCEV_H264_ERR_NULL_ARG;
    ISVCEncoder *enc = (ISVCEncoder *)(intptr_t)handle;
    return (*enc)->ForceIntraFrame(enc, true);
}

/* -------------------------------------------------------------- */
/* Decoder                                                        */
/* -------------------------------------------------------------- */

JNIEXPORT jlong JNICALL
Java_lekkit_scev_codec_H264Native_createDecoder(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    ISVCDecoder *dec = NULL;
    if (WelsCreateDecoder(&dec) != 0 || dec == NULL) return 0;

    SDecodingParam param;
    memset(&param, 0, sizeof(param));
    param.eEcActiveIdc   = ERROR_CON_SLICE_COPY;    /* error concealment */
    param.bParseOnly     = false;
    param.sVideoProperty.eVideoBsType = VIDEO_BITSTREAM_DEFAULT;

    if ((*dec)->Initialize(dec, &param) != 0) {
        WelsDestroyDecoder(dec);
        return 0;
    }
    return (jlong)(intptr_t)dec;
}

/**
 * Decode one access unit worth of NAL bytes. Writes the decoded Y/U/V
 * planes contiguously into {@code yuvOut} (Y then U then V). Writes
 * the decoded dimensions into {@code outDims}: {@code outDims[0]} =
 * width, {@code outDims[1]} = height, {@code outDims[2]} = Y-stride,
 * {@code outDims[3]} = UV-stride.
 *
 * Returns the total YUV bytes written (i.e. `W*H*3/2` for I420), or a
 * negative error code / {@link #SCEV_H264_ERR_NO_FRAME} if the NAL
 * didn't produce a complete frame (common for SPS/PPS-only inputs or
 * the first packet of a new stream before the decoder has converged).
 */
JNIEXPORT jint JNICALL
Java_lekkit_scev_codec_H264Native_decodeFrame(JNIEnv *env, jclass cls,
                                              jlong handle,
                                              jbyteArray nalIn, jint nalLen,
                                              jbyteArray yuvOut,
                                              jintArray outDims) {
    (void)cls;
    if (handle == 0 || nalIn == NULL || yuvOut == NULL || outDims == NULL) {
        return SCEV_H264_ERR_NULL_ARG;
    }
    ISVCDecoder *dec = (ISVCDecoder *)(intptr_t)handle;

    jbyte *nal = (*env)->GetByteArrayElements(env, nalIn, NULL);
    if (nal == NULL) return SCEV_H264_ERR_NULL_ARG;

    SBufferInfo bufInfo;
    memset(&bufInfo, 0, sizeof(bufInfo));
    /* pDst[0..2] will be pointed at OpenH264's internal decode buffer. */
    uint8_t *pDst[3] = {NULL, NULL, NULL};

    DECODING_STATE state = (*dec)->DecodeFrameNoDelay(dec,
                                                     (const unsigned char *)nal,
                                                     nalLen,
                                                     pDst,
                                                     &bufInfo);
    (*env)->ReleaseByteArrayElements(env, nalIn, nal, JNI_ABORT);

    if (state != dsErrorFree) return SCEV_H264_ERR_DECODE;
    if (bufInfo.iBufferStatus != 1) return SCEV_H264_ERR_NO_FRAME;

    int width  = bufInfo.UsrData.sSystemBuffer.iWidth;
    int height = bufInfo.UsrData.sSystemBuffer.iHeight;
    int yStride = bufInfo.UsrData.sSystemBuffer.iStride[0];
    int cStride = bufInfo.UsrData.sSystemBuffer.iStride[1];
    int ySize = width * height;
    int cSize = (width / 2) * (height / 2);
    int total = ySize + 2 * cSize;

    jsize outCap = (*env)->GetArrayLength(env, yuvOut);
    if (total > outCap) return SCEV_H264_ERR_OUTPUT_TOO_SMALL;

    jbyte *out = (*env)->GetByteArrayElements(env, yuvOut, NULL);
    if (out == NULL) return SCEV_H264_ERR_NULL_ARG;

    /* Copy each plane row-by-row, skipping stride padding OpenH264 may
     * have inserted (iStride[] can be > frame-width on some
     * architectures / SIMD alignment policies). */
    uint8_t *dstY = (uint8_t *)out;
    uint8_t *dstU = dstY + ySize;
    uint8_t *dstV = dstU + cSize;
    for (int r = 0; r < height; r++) memcpy(dstY + r * width, pDst[0] + r * yStride, width);
    for (int r = 0; r < height / 2; r++) {
        memcpy(dstU + r * (width / 2), pDst[1] + r * cStride, width / 2);
        memcpy(dstV + r * (width / 2), pDst[2] + r * cStride, width / 2);
    }

    (*env)->ReleaseByteArrayElements(env, yuvOut, out, 0);

    jint dims[4] = {width, height, yStride, cStride};
    (*env)->SetIntArrayRegion(env, outDims, 0, 4, dims);
    return total;
}

JNIEXPORT void JNICALL
Java_lekkit_scev_codec_H264Native_destroyDecoder(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    if (handle == 0) return;
    ISVCDecoder *dec = (ISVCDecoder *)(intptr_t)handle;
    (*dec)->Uninitialize(dec);
    WelsDestroyDecoder(dec);
}
