#!/usr/bin/env bash
# Build + run the full-stack scev_term smoke test.
#
# Mirrors the in-game lifecycle exactly: scev_term_init_once →
# scev_term_new (worker spawns) → scev_term_write (bytes go through
# parser + DCS handler + image loader) → scev_term_render snapshot
# → scev_term_destroy → scev_term_shutdown. PNG of the framebuffer
# lands at $OUT.
#
# Usage:
#   ./build_scev_smoke.sh                # full pipeline w/ bundled regis demo
#   ./build_scev_smoke.sh some.bytes some.png
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$SCRIPT_DIR"

MLTERM_SRC="${MLTERM_SRC:-$REPO_ROOT/build/mlterm-src}"
FONT="${FONT:-$REPO_ROOT/src/main/resources/scev/mlterm-fonts/cozette.bdf}"

# Make sure the .so prerequisites built.
make -C "$MLTERM_SRC/baselib" >/dev/null
make -C "$MLTERM_SRC/encodefilter" >/dev/null
[ -d "$MLTERM_SRC/libind" ] && make -C "$MLTERM_SRC/libind" >/dev/null || true
make -C "$MLTERM_SRC/vtemu" >/dev/null
[ -d "$MLTERM_SRC/vtemu/libctl" ] && make -C "$MLTERM_SRC/vtemu/libctl" >/dev/null || true
make -C "$MLTERM_SRC/uitoolkit" >/dev/null
make -C "$MLTERM_SRC/main" main_loop.o daemon.o >/dev/null

PKG_LIBS=$(pkg-config --libs freetype2 fontconfig libpng libssh2 2>/dev/null)
PKG_CFLAGS=$(pkg-config --cflags freetype2 fontconfig libpng libssh2 2>/dev/null)

# Need scev_term.o too (the JNI doesn't help us in a CLI test).
zig cc -O2 -g -Wall -fPIC \
  -DUSE_FRAMEBUFFER -DUSE_FB_EMBED \
  -I"$MLTERM_SRC/baselib/include" -I"$MLTERM_SRC/baselib/src" \
  -I"$MLTERM_SRC/encodefilter/include" -I"$MLTERM_SRC/encodefilter/src" \
  -I"$MLTERM_SRC/vtemu" -I"$MLTERM_SRC/uitoolkit" \
  -I"$MLTERM_SRC/uitoolkit/fb" -I"$MLTERM_SRC/main" \
  -I"$MLTERM_SRC/tool/registobmp" \
  $PKG_CFLAGS \
  -c scev_term.c -o scev_term.smoke.o

zig cc -O2 -g -Wall \
  -DUSE_FB_EMBED \
  -I"$MLTERM_SRC/baselib/include" -I"$MLTERM_SRC/baselib/src" \
  -I"$MLTERM_SRC/uitoolkit" -I"$MLTERM_SRC/uitoolkit/fb" \
  -I"$MLTERM_SRC/tool/registobmp" \
  $PKG_CFLAGS \
  scev_smoke.c scev_term.smoke.o \
  -Wl,--start-group \
    "$MLTERM_SRC/main/main_loop.o" \
    "$MLTERM_SRC/main/daemon.o" \
    "$MLTERM_SRC/uitoolkit/libuitoolkit.a" \
    "$MLTERM_SRC/vtemu/libmlterm.a" \
    "$MLTERM_SRC/vtemu/libmlterm_core.a" \
    $(ls "$MLTERM_SRC/vtemu/libctl/libctl_iscii.a" 2>/dev/null) \
    $(ls "$MLTERM_SRC/libind/libind.a" 2>/dev/null) \
    "$MLTERM_SRC/encodefilter/src/.libs/libmef.a" \
    "$MLTERM_SRC/baselib/src/.libs/libpobl.a" \
  -Wl,--end-group \
  $PKG_LIBS -lz -lm -lpthread -ldl \
  -o scev_smoke

# Default to the BootDemo bytes — generate them on the fly to
# match what Vt100Screen feeds at JVM open.
if [ $# -eq 0 ]; then
  IN="/tmp/scev-smoke-in.bytes"
  OUT="/tmp/scev-smoke-out.png"
  python3 - << 'PYEOF' > "$IN"
import sys
ESC = chr(0x1b)
Q = chr(39)  # single quote — building inside a bash heredoc
out = (
    f"{ESC}[0m{ESC}[2J{ESC}[H"
    f"{ESC}[1;36m  SCALAR EVOLUTION TERMINAL\r\n"
    f"{ESC}[0;33m  jexer ECMA-48 emulator (vendored)\r\n{ESC}[0m\r\n"
    # ReGIS demo: bg defaults to terminal bg (mlterm prepends
    # S(I(RxGyBz))S(E) for us), shapes + text on top.
    f"{ESC}P1p"
    f"W(I7)P[100,40]V[260,40][260,80][100,80][100,40]"
    f"W(I3)P[180,60]C[210,60]"
    f"W(I6)P[120,68]T(S2){Q}ReGIS{Q}"
    f"{ESC}\\\r\n"
    f"  vt100$ "
).encode("utf-8")
sys.stdout.buffer.write(out)
PYEOF
else
  IN="$1"
  OUT="${2:-/tmp/scev-smoke-out.png}"
fi

echo "--- run ---"
timeout 10 ./scev_smoke "$FONT" "$IN" "$OUT" 2>&1 || {
  echo "scev_smoke failed/timed out (exit $?)"
  exit 1
}
echo "--- output ---"
ls -la "$OUT"
file "$OUT"
