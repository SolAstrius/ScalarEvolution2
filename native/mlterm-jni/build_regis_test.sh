#!/usr/bin/env bash
# Build + run the standalone ReGIS interpreter test.
#
# Run from inside `nix develop` (so freetype + zig cc resolve). Will
# rebuild mlterm if needed via the gradle pipeline first.
#
# Usage:
#   ./build_regis_test.sh                # build + run on the bundled fixture
#   ./build_regis_test.sh some.rgs       # use a different input file
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$SCRIPT_DIR"

MLTERM_SRC="${MLTERM_SRC:-$REPO_ROOT/build/mlterm-src}"
if [ ! -d "$MLTERM_SRC" ]; then
  echo "MLTERM_SRC=$MLTERM_SRC missing — run gradle buildMltermNative first" >&2
  exit 1
fi

# Force a fresh build of the fork's libs so any iteration on
# tool/registobmp/main.c gets compiled in.
make -C "$MLTERM_SRC/baselib"
make -C "$MLTERM_SRC/encodefilter"
make -C "$MLTERM_SRC/vtemu"
make -C "$MLTERM_SRC/uitoolkit"

PKG_LIBS=$(pkg-config --libs freetype2 fontconfig libpng libssh2 2>/dev/null)
PKG_CFLAGS=$(pkg-config --cflags freetype2 fontconfig libpng libssh2 2>/dev/null)

zig cc -O2 -g -Wall \
  -DUSE_FRAMEBUFFER -DUSE_FB_EMBED \
  -I"$MLTERM_SRC/baselib/include" \
  -I"$MLTERM_SRC/baselib/src" \
  -I"$MLTERM_SRC/encodefilter/include" \
  -I"$MLTERM_SRC/encodefilter/src" \
  -I"$MLTERM_SRC/vtemu" \
  -I"$MLTERM_SRC/uitoolkit" \
  -I"$MLTERM_SRC/uitoolkit/fb" \
  -I"$MLTERM_SRC/tool/registobmp" \
  -I"$MLTERM_SRC/main" \
  $PKG_CFLAGS \
  regis_test.c \
  -Wl,--start-group \
    "$MLTERM_SRC/uitoolkit/libuitoolkit.a" \
    "$MLTERM_SRC/vtemu/libmlterm.a" \
    "$MLTERM_SRC/vtemu/libmlterm_core.a" \
    $(ls "$MLTERM_SRC/vtemu/libctl/libctl_iscii.a" 2>/dev/null) \
    $(ls "$MLTERM_SRC/libind/libind.a" 2>/dev/null) \
    "$MLTERM_SRC/encodefilter/src/.libs/libmef.a" \
    "$MLTERM_SRC/baselib/src/.libs/libpobl.a" \
  -Wl,--end-group \
  $PKG_LIBS -lz -lm -lpthread -ldl \
  -o regis_test

INPUT="${1:-$MLTERM_SRC/tests/inputs/regis-shapes.regis}"
OUT="${2:-/tmp/regis-out.ppm}"

echo "--- run ---"
timeout 10 ./regis_test "$INPUT" "$OUT" || {
  echo "regis_test failed or timed out (exit $?)"
  exit 1
}

echo "--- output ---"
ls -la "$OUT"
file "$OUT"
