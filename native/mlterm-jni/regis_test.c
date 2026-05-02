/* SPDX-License-Identifier: MPL-2.0
 *
 * Standalone test harness for the in-process ReGIS interpreter.
 * Reads an .rgs file, calls regis_render_file(), writes result as
 * a PPM (P6, RGB) to disk. Lets us iterate on the interpreter
 * without launching Minecraft.
 *
 * Build: see build_regis_test.sh in this directory.
 *
 * Usage:
 *   ./regis_test <input.rgs> <output.ppm>
 */

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include "regis_render.h"

int main(int argc, char **argv) {
  if (argc != 3) {
    fprintf(stderr, "usage: %s <in.rgs> <out.ppm>\n", argv[0]);
    return 1;
  }

  fprintf(stderr, "[regis_test] regis_render_file(%s) ...\n", argv[1]);
  fflush(stderr);

  struct timespec t0, t1;
  clock_gettime(CLOCK_MONOTONIC, &t0);

  regis_image_t img = { NULL, 0, 0 };
  if (!regis_render_file(argv[1], &img)) {
    fprintf(stderr, "[regis_test] FAILED\n");
    return 2;
  }

  clock_gettime(CLOCK_MONOTONIC, &t1);
  long long ms = (long long)(t1.tv_sec - t0.tv_sec) * 1000
               + (long long)(t1.tv_nsec - t0.tv_nsec) / 1000000;
  fprintf(stderr, "[regis_test] OK %dx%d in %lld ms\n", img.w, img.h, ms);

  /* Dump as PPM (P6, binary RGB). */
  FILE *fp = fopen(argv[2], "wb");
  if (!fp) { perror("fopen"); regis_image_free(&img); return 3; }
  fprintf(fp, "P6\n%d %d\n255\n", img.w, img.h);
  for (int i = 0; i < img.w * img.h; i++) {
    /* regis_render_file returns RGBA byte order (R at byte 0 on LE). */
    uint32_t p = img.pixels[i];
    unsigned char r =  p        & 0xff;
    unsigned char g = (p >> 8)  & 0xff;
    unsigned char b = (p >> 16) & 0xff;
    fwrite(&r, 1, 1, fp);
    fwrite(&g, 1, 1, fp);
    fwrite(&b, 1, 1, fp);
  }
  fclose(fp);
  fprintf(stderr, "[regis_test] wrote %s\n", argv[2]);

  regis_image_free(&img);
  return 0;
}
