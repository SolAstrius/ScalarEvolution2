# Scalar Evolution

An obscure Minecraft mod about computer technology and electronics, based on
[RVVM](https://github.com/LekKit/RVVM) — a real RISC-V virtual machine
running per in-world computer.

> **Pre-alpha quality** — here be dragons.

This is the **1.21.1 / NeoForge** port of [LekKit's
original](https://github.com/LekKit/ScalarEvolution) (Minecraft 1.7.10),
maintained as a co-development between [@pufit](https://github.com/pufit)
and [@SolAstrius](https://github.com/SolAstrius).

## What it does

Each placeable computer block runs its own real Linux (Buildroot or Alpine)
on an emulated RISC-V CPU. The framebuffer streams to clients over the
network as H.264; sound piggy-backs through Opus. VT100 / VT220 / VT340 /
VT420 / VT520 terminal blocks render through a JNI port of
[mlterm](https://github.com/arakiken/mlterm) for full xterm-class VT
fidelity (Sixel, ReGIS, the lot). Disks persist per-NVMe-item across power
cycles and follow the stack between cases. Optional integration with
[CC: Tweaked](https://github.com/cc-tweaked/CC-Tweaked) lets the guest
Linux talk to adjacent peripheral networks via `/dev/ttyS1`.

## If you want to help

- See [issues](https://github.com/SolAstrius/ScalarEvolution2/issues).
- Models and textures could use love — be aware that proposals might be
  rejected.
- Consider contributing to [RVVM](https://github.com/LekKit/RVVM) (or
  [pufit's fork](https://github.com/pufit/RVVM), which carries the JNI
  bridge + a few other patches the mod depends on).

## Platform

- Minecraft **1.21.1**
- NeoForge **21.1.226** or newer
- Java **21**

## Building

```sh
./gradlew build
```

The build clones [pufit/RVVM](https://github.com/pufit/RVVM) and the
[mlterm-fb-embed](https://github.com/SolAstrius/mlterm-fb-embed) fork,
compiles `librvvm`, `libscev_h264`, and `libscev_term` for the host
platform via `zig cc`, and ships the artefacts in the mod jar.

## Is this an OC2 ripoff?

No. The idea predates OC2 by a fair margin, but took a long time to reach
a working state. Originally inspired by the long-forgotten
[OCMIPS](https://github.com/Vexatos/OCMIPS) and `lunatic86` projects;
[OC2](https://github.com/fnuecke/oc2) was a simultaneous invention.

When OC2 appeared, the original plan was to propose RVVM as a faster
backend, but RVVM wasn't mature enough for that to be worth proposing.
Continuing here was the better path — the goals are different anyway.

## License

LGPL-3.0-or-later, matching upstream.
