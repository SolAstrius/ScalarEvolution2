# FreeType

- **Upstream:** https://gitlab.freedesktop.org/freetype/freetype (mirror:
  https://download.savannah.gnu.org/releases/freetype/)
- **Pinned release:** `2.14.2`
- **Tarball:** `freetype-2.14.2.tar.xz`
- **SHA-256:** `4b62dcab4c920a1a860369933221814362e699e26f55792516d671e6ff55b5e1`
- **License:** dual FTL / GPLv2 (FreeType uses the FTL here); see the
  tarball's `LICENSE.TXT` / `docs/FTL.TXT`.
- **Local modifications:** none — the upstream release tarball verbatim.

## Role

The *only* external C dependency of `libscev_term` (the mlterm JNI
native). In `--enable-fb-embed` mode the mlterm fork sheds everything
else: fontconfig defaults off (the host injects the font path via
`ui_customize_font_file()`), image loading uses bundled stb_image,
ReGIS renders in-process, Sixel is built-in, libssh2 is disabled, and
SDL2 / the libexec helpers are gone. FreeType remains for TrueType
glyph rasterisation.

## Build

`native/mlterm-jni/build-freetype.sh` extracts this tarball and
cross-builds a **minimal static** `libfreetype.a` per target with
`zig cc` — no harfbuzz, png, zlib, bzip2, or brotli — into
`build/freetype/<target>/`. `native/mlterm-jni/Makefile` then points
mlterm's `PKG_CHECK_MODULES(FT, freetype2)` at that prefix via
`PKG_CONFIG_LIBDIR` and static-links the archive into the shim.

The tarball is vendored (not fetched) so the native build is
offline-reproducible, matching the `vendor/openh264/` convention.
