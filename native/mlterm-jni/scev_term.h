/* SPDX-License-Identifier: MPL-2.0
 *
 * JNI surface for the mlterm-fb-backed terminal. Stable contract
 * the Kotlin side commits to before the C wrapper lands.
 *
 * Lifecycle: one term per VT100 GUI screen. Created on first open,
 * destroyed on screen close. Not thread-safe; all calls happen on
 * the MC client/render thread.
 *
 * Coordinate space:
 *   - cols, rows are in *cells* (text grid)
 *   - pixel dims (queried via scev_term_pixel_*) come from mlterm's
 *     loaded font and may not match cols × hardcoded-pitch
 *   - pixel format is host-endian uint32_t ARGB8888 — matches what
 *     MC's NativeImage stores internally on little-endian when
 *     useStb=false
 */
#ifndef SCEV_TERM_H_
#define SCEV_TERM_H_

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct scev_term scev_term_t;

/* Lifecycle ------------------------------------------------------ */

/* One-time process init. Locks mlterm out of all filesystem reads
 * (~/.mlterm, /etc/mlterm), injects `font_path` as the bundled
 * font for every charset mlterm asks for, sets up the term + color
 * managers. Idempotent. Returns 1 on success.
 *
 * Must be called before scev_term_new().
 */
int scev_term_init_once(const char *font_path);

/* Create a term sized for the given grid with the given term_type
 * string ("vt100", "vt220", "vt340", "xterm", ...). term_type drives
 * mlterm's DA reply, accepted escape sequences, and the matching
 * terminfo entry. Pass NULL for the previous behaviour (defaults
 * to "xterm").
 *
 * Allocates an internal pixel buffer, registers it with mlterm via
 * ui_fb_embed_attach, constructs vt_term + ui_font_manager +
 * ui_color_manager + ui_screen + display root.
 *
 * Single-buffer embed limits us to one active term at a time per
 * process. Returns NULL on failure or if a term is already active.
 */
scev_term_t *scev_term_new(const char *term_type, int cols, int rows);

/* Tear down. Closes the underlying screen + frees the buffer.
 * Safe to pass NULL. Does NOT run mlterm's process-global
 * teardown — that's deferred to scev_term_shutdown() so the
 * render thread can't freeze on a per-screen close. */
void scev_term_destroy(scev_term_t *term);

/* Process-global mlterm teardown. Idempotent. Intended for a JVM
 * shutdown hook — at that point the render thread is gone, so
 * blocking inside main_loop_final no longer matters. */
void scev_term_shutdown(void);


/* Data plane ----------------------------------------------------- */

/* Push raw bytes into the VT parser via vt_term_write_loopback —
 * exactly as if the bytes had come from the PTY. Returns the
 * number accepted. */
size_t scev_term_write(scev_term_t *term, const uint8_t *bytes, size_t len);

/* Drain queued reply bytes (DA / DSR / mouse-report responses
 * mlterm tried to vt_write_to_pty). Caller forwards these back
 * toward the guest — same destination as typed keystrokes
 * (kernel UART RX). Returns number copied into `out` (0 if no
 * pending replies). Non-blocking. */
size_t scev_term_poll_reply(scev_term_t *term, uint8_t *out, size_t cap);


/* Rendering ------------------------------------------------------ */

/* Pixel dimensions of the rendered surface. Set after scev_term_new
 * returns. */
int scev_term_pixel_w(scev_term_t *term);
int scev_term_pixel_h(scev_term_t *term);

/* Run one non-blocking pump iteration (drains pending PTY data,
 * runs the per-display idle pass, paints any dirty cells into the
 * internal buffer). Then copy the buffer's contents into `out` as
 * uint32_t ARGB8888 pixels at `stride_px` row stride. `out` must
 * hold at least scev_term_pixel_h() * stride_px * 4 bytes. */
void scev_term_render(scev_term_t *term, uint32_t *out, int stride_px);

/* Like scev_term_render but writes into `out_ptr` as RGBA8888
 * BYTE order (R at byte 0, A at byte 3 — matches MC's NativeImage
 * format=RGBA buffer layout). `stride_px` is in pixels (4 bytes
 * each). Intended for hosts that hand us a raw pointer (e.g. a
 * NativeImage's underlying malloc) so we skip the int[] hop and
 * the per-pixel JNI setPixel call. */
void scev_term_render_abgr_ptr(scev_term_t *term, void *out_ptr, int stride_px);


#ifdef __cplusplus
}
#endif
#endif /* SCEV_TERM_H_ */
