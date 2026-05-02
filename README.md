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

> **Pre-alpha quality** — here be dragons.

## Why the Fork?

LekKit's *Scalar Evolution* targets Minecraft 1.7.10 and hadn't been
brought forward. We didn't fork because of any disagreement — pufit
started the port to bring it to a current Minecraft, and Sol joined for
co-development. The mod's core ideas (in-world emulated computers, real
guest Linux, customisable hardware, persistence) are LekKit's; everything
since is a port + extensions on top of that.

## What's in it today

- **Real RISC-V Linux per computer.** Buildroot or Alpine boots inside
  RVVM; persistent disks travel with NVMe items between cases.
- **VT100 / VT220 / VT340 / VT420 / VT520** terminal blocks driven by
  mlterm-fb-embed. ReGIS and Sixel render correctly; period-correct
  BootRom Setup pages (SET-UP A / B / CRT FX / MOD) on F3.
- **CRT FX shader** with scanlines, phosphor bloom, chroma shift,
  vignette, and slight curvature on the in-world block face.
- **Sound card** end-to-end: guest `aplay` reaches every nearby player's
  OpenAL through Opus over the Minecraft network layer.
- **CC: Tweaked integration**, scev-as-computer: the guest Linux can
  enumerate, introspect, and call CC peripherals over `/dev/ttyS1` —
  including peripherals attached through wired modems.
- **MCU boards** for tiny SoC + flash workflows; bare-metal blinky
  firmware ships as the hello-world.
- **Processing-machine chain** for in-game paper/ink/ribbon production
  feeding a teletype that prints the kernel console as it boots.

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
