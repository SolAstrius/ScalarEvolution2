/* SPDX-License-Identifier: MPL-2.0
 *
 * Full-stack smoke test for the scev_term JNI primitives. Exercises
 * the EXACT same code path the in-game terminal uses:
 *
 *   scev_term_init_once(font_path)
 *   scev_term_new(cols, rows)        → spawns worker, opens term + screen
 *   scev_term_write(bytes, len)      → SPSC ring → worker → vt_term_write_loopback
 *                                      → parser → DCS handler → show_picture
 *                                      → exec_mlimgloader → embed_load_image_file
 *                                      → regis_render_file (for ReGIS) or stb_image
 *   ... wait for the worker to settle ...
 *   scev_term_render(buf)            → atomic_load(publish_front) + memcpy
 *   write PNG of the framebuffer
 *   scev_term_destroy
 *   scev_term_shutdown
 *
 * No JVM, no Minecraft. Run from a shell to iterate on the embed
 * pipeline in seconds. PNGs go to ./out-*.png so we can diff
 * against expected goldens.
 *
 * Usage:
 *   ./scev_smoke <font.bdf> <input.bytes> <output.png>
 *
 * <input.bytes> is read raw and fed verbatim to scev_term_write —
 * it can be a VT escape script, a sixel file, a ReGIS DCS payload,
 * or any combination.
 */

#include "scev_term.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

static int read_file(const char *path, unsigned char **out, size_t *out_len) {
  FILE *fp = fopen(path, "rb");
  if (!fp) { perror(path); return 0; }
  fseek(fp, 0, SEEK_END);
  long n = ftell(fp);
  fseek(fp, 0, SEEK_SET);
  unsigned char *buf = malloc((size_t)n);
  if (!buf) { fclose(fp); return 0; }
  if (fread(buf, 1, (size_t)n, fp) != (size_t)n) { free(buf); fclose(fp); return 0; }
  fclose(fp);
  *out = buf;
  *out_len = (size_t)n;
  return 1;
}

int main(int argc, char **argv) {
  if (argc != 4) {
    fprintf(stderr, "usage: %s <font.bdf> <input.bytes> <output.png>\n", argv[0]);
    return 1;
  }

  fprintf(stderr, "[smoke] init_once(%s) ...\n", argv[1]);
  if (!scev_term_init_once(argv[1])) {
    fprintf(stderr, "[smoke] init_once failed\n");
    return 2;
  }

  fprintf(stderr, "[smoke] new(80x24) ...\n");
  scev_term_t *t = scev_term_new("xterm", 80, 24);
  if (!t) {
    fprintf(stderr, "[smoke] scev_term_new failed\n");
    return 3;
  }
  int w = scev_term_pixel_w(t);
  int h = scev_term_pixel_h(t);
  fprintf(stderr, "[smoke] term geometry: %dx%d px\n", w, h);

  /* Feed input bytes the same way the JVM render thread does. */
  unsigned char *bytes;
  size_t blen;
  if (!read_file(argv[2], &bytes, &blen)) return 4;
  fprintf(stderr, "[smoke] write %zu bytes\n", blen);
  scev_term_write(t, bytes, blen);
  free(bytes);

  /* Give the worker enough wall time to drain + parse + render +
   * publish. Worker tick is ~16ms; 30 ticks (~500ms) is plenty for
   * a few hundred bytes including a ReGIS DCS that the parser has
   * to write to a temp file + the in-process regis_render_file
   * decode + the blit onto the screen grid. */
  fprintf(stderr, "[smoke] settling for 500ms ...\n");
  struct timespec sleep_for = { 0, 500 * 1000 * 1000 };
  nanosleep(&sleep_for, NULL);

  /* Snapshot the latest published frame. */
  fprintf(stderr, "[smoke] render snapshot ...\n");
  uint32_t *frame = malloc((size_t)w * h * sizeof(uint32_t));
  if (!frame) return 5;
  scev_term_render(t, frame, w);

  /* Write PNG. scev_term_render returns ARGB ints (0xAARRGGBB on LE,
   * memory bytes B,G,R,A). Convert to RGBA byte order for stb_image_write. */
  unsigned char *rgba = malloc((size_t)w * h * 4);
  if (!rgba) return 6;
  for (int i = 0; i < w * h; i++) {
    uint32_t p = frame[i];
    rgba[i*4 + 0] = (p >> 16) & 0xff;  /* R */
    rgba[i*4 + 1] = (p >>  8) & 0xff;  /* G */
    rgba[i*4 + 2] =  p        & 0xff;  /* B */
    rgba[i*4 + 3] = (p >> 24) & 0xff;  /* A */
  }
  if (!stbi_write_png(argv[3], w, h, 4, rgba, w * 4)) {
    fprintf(stderr, "[smoke] stbi_write_png failed\n");
    return 7;
  }
  fprintf(stderr, "[smoke] wrote %s (%dx%d)\n", argv[3], w, h);
  free(rgba);
  free(frame);

  /* Clean teardown — same order MltermBackend.close() + the JVM
   * shutdown hook do. */
  fprintf(stderr, "[smoke] destroy ...\n");
  scev_term_destroy(t);
  fprintf(stderr, "[smoke] shutdown ...\n");
  scev_term_shutdown();
  fprintf(stderr, "[smoke] done\n");
  return 0;
}
