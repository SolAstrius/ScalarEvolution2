# Scalar Evolution

*Scalar Evolution* is a Minecraft mod about computer technology and
electronics. Each placeable computer block runs its own real Linux on an
emulated RISC-V CPU; framebuffers stream to clients as H.264, audio as
Opus, terminal blocks render through a JNI port of [mlterm] for full
xterm-class VT fidelity (Sixel, ReGIS, the lot).

This is the **1.21.1 / NeoForge** continuation of [LekKit's original]
(Minecraft 1.7.10), maintained jointly by
[@pufit](https://github.com/pufit) and
[@SolAstrius](https://github.com/SolAstrius).

The underlying VM is [RVVM], also by LekKit, used here through
[pufit's fork](https://github.com/pufit/RVVM) which carries the JNI bridge
and a handful of patches the mod depends on.

> **Alpha quality** — hic sunt dracones.

## Why the Fork?

LekKit's *Scalar Evolution* targets Minecraft 1.7.10 and hadn't been
brought forward. We didn't fork because of any disagreement — pufit
started the port to bring it to a current Minecraft, and Sol joined for
co-development.

In practice "port" undersells it. The only code carried verbatim from
upstream is the `lekkit.rvvm.*` JNI binding package (~30 files, ~280
LOC) that talks to RVVM. The Minecraft side — blocks, block entities,
menus, screens, network, recipes, datagen, rendering, peripheral bus,
componentry, codecs, machine integration — is a from-scratch Kotlin
reimplementation of roughly **31k LOC across 270+ files**. The mod's
*conceptual* DNA (in-world emulated computers, real guest Linux,
customisable hardware, persistence) is LekKit's; the actual shipping
codebase is overwhelmingly new.

## What's in it today

### Emulation core
- **Real RISC-V Linux per computer.** Buildroot or Alpine boots inside
  RVVM; persistent disks travel with NVMe items between cases. Disk
  images are GC'd when no longer referenced.
- **CPU tier 1 / 2 / 4 hart progression** with branded part names; SoC
  items expose tier as part of their type.
- **RAM tier 5** at the top end of the memory ladder.
- **Audio sync** — guest audio is anchored to a PTS wall-clock so
  `aplay` doesn't drift against the framebuffer over long sessions.

### Display & terminals
- **VT100 / VT220 / VT340 / VT420 / VT520** terminal blocks driven by
  a JNI port of mlterm (mlterm-fb-embed). Full xterm-class fidelity:
  ReGIS and Sixel both render. Period-correct BootRom Setup pages
  (SET-UP A / B / CRT FX / MOD) on F3.
- **CRT FX shader** with scanlines, phosphor bloom, chroma shift,
  vignette, and slight curvature on the in-world block face.
- **Framebuffer streaming** as H.264 via a vendored OpenH264 build
  (`libscev_h264`, cross-built with `zig cc`).

### Audio
- **Sound card** end-to-end: HDA → Opus → OpenAL. Guest `aplay`
  reaches every nearby player through the Minecraft network layer with
  per-listener spatialisation.

### Peripherals & I/O
- **Peripheral bus** with a typed component API; compat modules can
  declare components against the upcoming `/sys/scev/` filesystem via
  annotation + DSL.
- **GPIO**, **I²C**, and an **MCU board** for tiny SoC + flash
  workflows. Bare-metal blinky firmware (RISC-V assembly) ships as the
  hello-world recipe.
- **CC: Tweaked integration**, scev-as-computer: the guest Linux can
  enumerate, introspect, and call CC peripherals over `/dev/ttyS1` —
  including peripherals attached through wired modems.

### In-world progression
- **Processing-machine chain** for paper / ink / ribbon production
  feeding a teletype that prints the kernel console as it boots.
- **Sectioned creative tab** with computing / motherboards / storage /
  expansion / cases & peripherals / crafting buckets.

### Client & integration
- **owo-ui screens** for the computer case, motherboard, and other
  configurators.
- **Jade HUD providers** for at-a-glance machine state without opening
  the GUI.
- **Zig guest agent** (`tools/scev-guest-zig/`) — a small RISC-V Linux
  userland that speaks RPC over a serial port for host↔guest
  introspection and orchestration.

## Building

```sh
./gradlew build
```

The build clones [pufit/RVVM] and the [mlterm-fb-embed] fork, compiles
`librvvm`, `libscev_h264`, and `libscev_term` for the host platform via
`zig cc`, fetches a sys-install Alpine image from
[scev-alpine]'s rolling release, and ships everything inside the mod jar.

## If you want to help

- File issues at [SolAstrius/ScalarEvolution2/issues].
- Models and textures could use love. Open a PR; be aware that proposals
  might be rejected on art-direction grounds.
- The peripheral / component API is open for compat modules — the
  `lekkit.scev.component` package documents the annotation + DSL for
  declaring components against the upcoming `/sys/scev/` filesystem.
- Consider contributing to [RVVM] or [pufit's fork].

## Platform

- Minecraft **1.21.1**
- NeoForge **21.1.226+**
- Java **21**

## License

LGPL-3.0-or-later, matching upstream.

[LekKit's original]: https://github.com/LekKit/ScalarEvolution
[RVVM]: https://github.com/LekKit/RVVM
[pufit/RVVM]: https://github.com/pufit/RVVM
[mlterm]: https://github.com/arakiken/mlterm
[mlterm-fb-embed]: https://github.com/SolAstrius/mlterm-fb-embed
[scev-alpine]: https://github.com/SolAstrius/scev-alpine
[SolAstrius/ScalarEvolution2/issues]: https://github.com/SolAstrius/ScalarEvolution2/issues
