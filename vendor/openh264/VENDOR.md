# OpenH264

- **Upstream:** https://github.com/cisco/openh264
- **Pinned tag:** `v2.6.0`
- **Pinned commit:** `652bdb7719f30b52b08e506645a7322ff1b2cc6f`
- **License:** BSD-2-Clause (see `LICENSE` in this directory, unmodified
  from upstream).
- **Local modifications:** none.

## Role

Static H.264 encoder/decoder, linked into `libscev_h264.{so,dylib,dll}`
via the JNI wrapper at `native/openh264-jni/`. The wrapper exposes a
thin encoder/decoder surface to Kotlin (`lekkit.scev.codec.H264`); all
patent-sensitive decoding/encoding logic runs inside this vendored
tree.

## Build

Our `native/openh264-jni/Makefile` copies this tree to
`build/openh264-src/` on first build, runs OpenH264's own Makefile
against it with:

```
make CC="zig cc" CXX="zig c++" CCAS="zig cc" \
     USE_ASM=No ENABLE64BIT=Yes BUILDTYPE=Release \
     HAVE_GTEST=No USE_STACK_PROTECTOR=No \
     libopenh264.a
```

Then links the resulting `libopenh264.a` statically into our JNI
shared object via `zig c++ -shared`.

`USE_ASM=No` disables OpenH264's NASM-built SIMD kernels. The pure-C
fallback is measurably slower on encode (~2–3× on dense real-time
content), but portable across all our target platforms without
requiring NASM in the build environment. Revisit if encoder CPU
becomes a bottleneck on a dedicated-server workload.

## Trimmed content

The vendored copy excludes upstream's `res/`, `test/`, `autotest/`,
`testbin/`, `docs/`, `module/`, `subprojects/` directories, plus the
Gradle + Android harness (`build.gradle`, `gradlew*`, `gradle/`).
None of those are exercised by our static-lib build path. Full
upstream is ~144 MB including a test-video corpus under `res/`; the
vendored subset is ~8 MB.

To re-vendor, see `../README.md`.

## Patent notice

H.264 is patent-encumbered. Cisco operates a patent-licensing program
under which **they** pay MPEG-LA royalties for binaries they distribute
from the OpenH264 project — Firefox's runtime-downloaded
`libopenh264.so` is the canonical example. Cisco's patent coverage does
**not** automatically extend to third parties who recompile the same
source. In practice, enforcement at Minecraft-mod scale is unlikely,
but the licensing status of binaries built from this vendored tree is
distinct from the licensing status of binaries Cisco distributes.
Worth weighing before any "officially sanctioned" wider distribution.
