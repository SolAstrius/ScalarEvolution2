/* SPDX-License-Identifier: MPL-2.0
 *
 * JNI shim that exposes scev_term.h to Java. Mirrors the Java
 * declarations in lekkit/scev/native/Mlterm.java exactly — every
 * function here is named after the Java class+method per JNI's
 * `Java_<package>_<class>_<method>` convention.
 *
 * Threading: Java side calls all methods on the MC client/render
 * thread. The C side serialises against itself naturally; mlterm's
 * own background work happens inside ui_fb_embed_pump.
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "scev_term.h"

/* JNI mangling: Java_<package>_<class>_<method>. Using a clean
 * lekkit.mlterm package (no underscores in the name) avoids the
 * `_1` escape JNI inserts for package names that contain `_`. */
#define JNICALL_(ret, name) JNIEXPORT ret JNICALL Java_lekkit_mlterm_Mlterm_##name

JNICALL_(jboolean, nativeInit)(JNIEnv *env, jclass cls, jstring jfont_path) {
  (void)cls;
  if (!jfont_path) return JNI_FALSE;
  const char *font_path = (*env)->GetStringUTFChars(env, jfont_path, NULL);
  if (!font_path) return JNI_FALSE;
  int ok = scev_term_init_once(font_path);
  (*env)->ReleaseStringUTFChars(env, jfont_path, font_path);
  return ok ? JNI_TRUE : JNI_FALSE;
}

JNICALL_(jlong, nativeNew)(JNIEnv *env, jclass cls,
                           jstring jterm_type, jint cols, jint rows) {
  (void)cls;
  const char *term_type = NULL;
  if (jterm_type) {
    term_type = (*env)->GetStringUTFChars(env, jterm_type, NULL);
    if (!term_type) return 0;
  }
  scev_term_t *t = scev_term_new(term_type, (int)cols, (int)rows);
  if (term_type) (*env)->ReleaseStringUTFChars(env, jterm_type, term_type);
  return (jlong)(uintptr_t)t;
}

JNICALL_(void, nativeDestroy)(JNIEnv *env, jclass cls, jlong handle) {
  (void)env; (void)cls;
  scev_term_destroy((scev_term_t *)(uintptr_t)handle);
}

JNICALL_(void, nativeShutdown)(JNIEnv *env, jclass cls) {
  (void)env; (void)cls;
  scev_term_shutdown();
}

JNICALL_(jint, nativePixelW)(JNIEnv *env, jclass cls, jlong handle) {
  (void)env; (void)cls;
  return (jint)scev_term_pixel_w((scev_term_t *)(uintptr_t)handle);
}

JNICALL_(jint, nativePixelH)(JNIEnv *env, jclass cls, jlong handle) {
  (void)env; (void)cls;
  return (jint)scev_term_pixel_h((scev_term_t *)(uintptr_t)handle);
}

JNICALL_(jint, nativePollReply)(JNIEnv *env, jclass cls, jlong handle,
                                jbyteArray out, jint off, jint cap) {
  (void)cls;
  scev_term_t *t = (scev_term_t *)(uintptr_t)handle;
  if (!t || !out || cap <= 0) return 0;
  jbyte *bytes = (*env)->GetByteArrayElements(env, out, NULL);
  if (!bytes) return 0;
  size_t n = scev_term_poll_reply(t, (uint8_t *)(bytes + off), (size_t)cap);
  /* Mode 0 commits the write back to the JVM array. */
  (*env)->ReleaseByteArrayElements(env, out, bytes, 0);
  return (jint)n;
}

JNICALL_(jint, nativeWrite)(JNIEnv *env, jclass cls, jlong handle,
                            jbyteArray data, jint off, jint len) {
  (void)cls;
  scev_term_t *t = (scev_term_t *)(uintptr_t)handle;
  if (!t || !data || len <= 0) return 0;
  jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
  if (!bytes) return 0;
  size_t n = scev_term_write(t, (uint8_t *)(bytes + off), (size_t)len);
  (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
  return (jint)n;
}

JNICALL_(void, nativeRender)(JNIEnv *env, jclass cls, jlong handle,
                             jintArray out, jint stride_px) {
  (void)cls;
  scev_term_t *t = (scev_term_t *)(uintptr_t)handle;
  if (!t || !out) return;
  /* GetPrimitiveArrayCritical pins the JVM's int[] backing store so
   * we can hand it to scev_term_render as a uint32_t* without
   * copying. The "critical" name is literal — must not call back
   * into the JVM or block while holding it; scev_term_render does
   * neither, and the pin is released the moment the call returns. */
  jint *pixels = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
  if (pixels) {
    scev_term_render(t, (uint32_t *)pixels, (int)stride_px);
    (*env)->ReleasePrimitiveArrayCritical(env, out, pixels, 0);
  }
}

/* Bulk render straight into a raw memory address (typically a
 * NativeImage's underlying malloc). Skips the int[] roundtrip AND
 * the per-pixel JVM setPixelRGBA dispatch that the int[] path's
 * caller (Vt100Screen) used to do — that loop ran ~9.7M JNI calls
 * per second at 60fps for a 480×336 grid. Output byte order is RGBA
 * (R at byte 0, A at byte 3) to match MC's NativeImage format=RGBA. */
JNICALL_(void, nativeRenderToPtr)(JNIEnv *env, jclass cls, jlong handle,
                                  jlong out_ptr, jint stride_px) {
  (void)env; (void)cls;
  scev_term_t *t = (scev_term_t *)(uintptr_t)handle;
  void *ptr = (void *)(uintptr_t)out_ptr;
  if (!t || !ptr) return;
  scev_term_render_abgr_ptr(t, ptr, (int)stride_px);
}
