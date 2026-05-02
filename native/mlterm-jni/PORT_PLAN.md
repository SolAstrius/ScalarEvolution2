# mlterm-fb → JNI port plan

Target: replace the jexer terminal backend with a JNI-bound port of
mlterm's framebuffer renderer. End state: every `Vt100Screen` open in
MC drives a native `vt_term_t` instance that renders to a memory
buffer we upload as a `DynamicTexture`, with full Sixel + ReGIS
support, full xterm-class VT fidelity, and zero pure-Java VT
emulation code in the mod.

The jexer branch (`vendor/jexer-terminal`) is abandoned — kept around
for reference, not merged.

## Why mlterm specifically

It's the only terminal emulator that meets all three of:
- Sixel **and** ReGIS in the same codebase
- Designed-for-embedding library mode (libvte-compat, libvterm-compat)
- BSD-style license compatible with our MPL setup

xterm is more featureful but architecturally unembeddable (Xt is
woven through every file). st is small but lacks ReGIS. mlterm hits
the sweet spot.

## Source layout (upstream)

| Dir | Vendor? | Why |
|-----|---------|-----|
| `vtemu/` | Yes | VT parser + state machine. Backend-agnostic. ~28k LOC. |
| `baselib/src/` | Yes | mem/str/conf/dlfcn/pty/util support. Required by everything. |
| `uitoolkit/fb/` | Yes (port) | Framebuffer rendering. Linux-only as-is — we strip the device-node IO and replace with "render to a `uint32_t*` buffer". |
| `uitoolkit/ui_*.c` | Partial | Backend-agnostic UI layer (color, font, image). Need most of it. |
| `encodefilter/` | Yes | iconv-style encoding conversion. |
| `libvterm/` | No | We're not exposing the libvterm-compat API; we want raw vtemu. |
| `gtk/` `cocoa/` `win32/` `sdl2/` `wayland/` `quartz/` `beos/` `android/` `java/` | **No** | Other backends. |
| `inputmethod/` | No | X11 IME, irrelevant. |
| `libptyssh/` `libptymosh/` | No | We bring our own data path (kernelUart eventually). |
| `libctl/` | Maybe | Indic / bidi shaping. Defer until needed. |
| `scrollbar/` `tool/` `script/` `etc/` `man/` `doc/` | No | Non-source / not relevant. |

Estimated vendored LOC: ~60k. Big, but bounded.

## Port milestones

Each milestone ends with something tangible. Mid-milestone state
might not compile; that's expected.

### M0 — Scaffolding
- [x] New branch `vendor/mlterm-fb`
- [x] `native/mlterm-jni/` directory
- [x] This plan committed
- [ ] mlterm source vendored under `vendor/mlterm/` at a pinned commit
- [ ] Stub `Makefile` (mirrors `openh264-jni`'s pattern)
- [ ] JNI header `scev_term.h` sketching the API surface

### M1 — Build mlterm-fb on Linux x86_64 unmodified
Get the upstream framebuffer build working as-is, against /dev/fb0.
No JNI yet. Goal: prove the source compiles in our build harness.
- [ ] Vendor source
- [ ] Wire Makefile to invoke mlterm's autotools `./configure`
- [ ] Resolve any missing build-time deps (likely `iconv`, possibly
  `gettext`, `libfreetype`, `libfontconfig`)
- [ ] Run resulting binary in a Linux VT, confirm it works

### M2 — Replace device IO with a buffer API
The actual port. `uitoolkit/fb/ui_display.c` opens `/dev/fb0` and
mmaps it; that becomes "we hand you a `uint32_t* buffer, int stride,
int w, int h`, you render into it." Input events come from
`/dev/input/eventN` via libinput; that becomes a `feed_event(int
type, int code, int value)` entry point.

Functions to refactor:
- `open_display()` / `close_display()` → "no-op, we own the buffer"
- `update_screen()` → render to our buffer instead of mmap'd fb
- Event reading loop in `ui_event_source.c` → driven externally
- Font loading: keep as-is (TTF via FreeType, hopefully)

End state: `libmlterm_fb.a` plus a tiny C entry point exposing
`term_new(cols, rows) → handle`, `term_write(handle, bytes)`,
`term_render(handle, buffer)`, `term_event(handle, kind, ...)`,
`term_destroy(handle)`.

### M3 — JNI wrapper + Kotlin side  ✅ in progress
- [x] `scev_term.h` — stable C API: new/destroy/write/pixel_w/pixel_h/render
- [x] `scev_term.c` — wraps mlterm's main_loop + screen_manager + the
      embed entry points. One `scev_term_t` owns the whole mlterm
      runtime (the screen manager is process-global).
- [x] `scev_term_jni.c` — JNI shim, Java_lekkit_mlterm_Mlterm_*
- [x] `lekkit.mlterm.Mlterm` (Java) — JNI declarations
- [x] `lekkit.scev.client.terminal.MltermBackend` (Kotlin) — handle
      class, drop-in for the abandoned `Terminal` class shape
- [x] `Makefile` — invokes mlterm's `./configure --enable-fb-embed`
      then links its static archives + our shim into one .so;
      copies `mlimgloader` + `registobmp` next to the .so for the
      runtime dlopen lookup
- [ ] Gradle `buildMltermNative` task that drives the Makefile,
      points `MLTERM_SRC` at a SolAstrius/mlterm-fb-embed checkout
- [ ] First end-to-end smoke: spin up `MltermBackend(80, 24)`, feed
      "hello\\n", render to int[], dump as PNG via a JUnit test

### M4 — Per-platform native builds
mlterm's autotools build is Linux-friendly but works on macOS and
MSYS2 with patience. Three build dimensions:
- Linux x86_64 / aarch64
- macOS x86_64 / aarch64 (Cocoa/Quartz dependencies need to be
  optional-ed out of our build — we use the fb backend only)
- Windows x86_64 (MSYS2 + zig cc; mlterm's win32 backend is a
  separate codepath we won't touch)

Cross-compile via zig, mirroring `openh264-jni` and `librvvm`.

### M5 — Production switchover
Re-introduce the VT100 block (currently unmodified on `main`),
point its renderer at the new `MltermBackend`, drop all jexer
references.

## Open questions
- **Font rendering**: mlterm uses Xft/FreeType for TTF on its fb
  backend. Do we keep that or have GlyphMaker-style font selection on
  our side? Easiest is keep mlterm's font pipeline; we just need to
  bundle a fallback font (Terminus already shipped under
  `src/main/resources/terminus-ttf-4.49.1/`) and configure mlterm to
  load it.
- **Input encoding**: mlterm expects raw key codes; we'd marshal
  GLFW → linux/input.h evdev codes on the JVM side or do the
  translation in C. Probably C — closer to the original event loop.
- **Threading**: mlterm assumes a single-threaded event loop. Our
  JNI calls happen on MC's render thread. Need to ensure no
  background threads inside mlterm escape that.
