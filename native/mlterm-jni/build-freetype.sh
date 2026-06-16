#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
#
# Cross-build a MINIMAL static libfreetype.a for one target with zig cc.
# FreeType is the only external C dependency of libscev_term (see
# vendor/freetype/VENDOR.md); everything else is shed in fb-embed mode.
#
# Inputs (env):
#   FT_TARBALL   path to vendor/freetype/freetype-*.tar.xz          (required)
#   FT_PREFIX    install prefix; libfreetype.a lands in $FT_PREFIX/lib (required)
#   TARGET       zig -target triple (e.g. x86_64-linux-gnu); empty = host
#   HOST_TRIPLE  autotools --host triple (e.g. x86_64-w64-mingw32);  empty = native
#   JOBS         parallelism for make                                (default: 4)
#
# zig's -target triple is NOT the autotools --host triple: config.sub
# rejects e.g. "x86_64-windows-gnu", so callers pass the canonical
# "x86_64-w64-mingw32" as HOST_TRIPLE while keeping the LLVM triple in
# TARGET for the compiler. The two are deliberately decoupled.
#
# Idempotent: a present $FT_PREFIX/lib/libfreetype.a short-circuits.

set -euo pipefail

: "${FT_TARBALL:?build-freetype.sh: FT_TARBALL must point at the vendored tarball}"
: "${FT_PREFIX:?build-freetype.sh: FT_PREFIX must be set}"
JOBS="${JOBS:-4}"

if [ ! -f "$FT_TARBALL" ]; then
  echo "build-freetype.sh: FT_TARBALL not found: $FT_TARBALL" >&2
  exit 1
fi

if [ -f "$FT_PREFIX/lib/libfreetype.a" ]; then
  echo "build-freetype.sh: libfreetype.a already built for ${TARGET:-host} — skipping"
  exit 0
fi

cc="zig cc"
host_arg=""
if [ -n "${TARGET:-}" ]; then
  cc="zig cc -target $TARGET"
fi
if [ -n "${HOST_TRIPLE:-}" ]; then
  host_arg="--host=$HOST_TRIPLE"
fi

# On Windows targets, FreeType's libtool drives the static-archive step
# through MSVC's `lib`. zig ships a drop-in (`zig lib`), so expose it as
# `lib` on PATH for the duration of this build. Harmless on other targets.
shimdir="$(mktemp -d)"
cat > "$shimdir/lib" <<'SHIM'
#!/bin/sh
exec zig lib "$@"
SHIM
chmod +x "$shimdir/lib"
export PATH="$shimdir:$PATH"
cleanup() { rm -rf "$shimdir" "$work"; }

work="$FT_PREFIX/.src"
rm -rf "$work"
mkdir -p "$work"
trap cleanup EXIT
tar -xJf "$FT_TARBALL" -C "$work" --strip-components=1

cd "$work"
# Minimal static build: no optional deps. zig provides cc/ar/ranlib so no
# external cross-toolchain is needed for any target.
CC="$cc" AR="zig ar" RANLIB="zig ranlib" \
  ./configure $host_arg --prefix="$FT_PREFIX" \
    --enable-static --disable-shared \
    --without-harfbuzz --without-png --without-zlib --without-bzip2 --without-brotli

make -j"$JOBS"
make install

# On Windows the install names the archive libfreetype.lib; alias it so a
# gnu-style `-lfreetype` link resolves. No-op elsewhere.
if [ ! -f "$FT_PREFIX/lib/libfreetype.a" ] && [ -f "$FT_PREFIX/lib/libfreetype.lib" ]; then
  cp "$FT_PREFIX/lib/libfreetype.lib" "$FT_PREFIX/lib/libfreetype.a"
fi

test -f "$FT_PREFIX/lib/libfreetype.a"
echo "build-freetype.sh: built $FT_PREFIX/lib/libfreetype.a for ${TARGET:-host}"
