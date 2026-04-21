# Buildroot recipe — RV64 Linux kernel for Scalar Evolution

This directory produces the `Image` + `rootfs.cpio.gz` artefacts that the
mod ships under `src/main/resources/assets/scev/firmware/`.

**If you just want to hack on the mod, you don't need this.** The
pre-built kernel is already committed as a resource. Only touch this
directory if you want to:

- Bump the Linux kernel or Buildroot version
- Add / remove kernel driver config
- Ship new files in the guest rootfs (drop them under `rootfs_overlay/`)
- Audit the build pipeline before trusting the shipped kernel blob

## Quick start

From the repository root, on a machine with Docker:

```bash
docker volume create scev-buildroot-cache   # one-time, caches toolchain
mkdir -p build-output

docker run --rm \
    -v "$(pwd)/tools/buildroot:/work" \
    -v scev-buildroot-cache:/buildroot \
    -v "$(pwd)/build-output:/output" \
    --memory=8g --cpus=6 \
    ubuntu:24.04 bash /work/build-kernel.sh
```

First run ~25–45 min on a beefy laptop (downloads + cross toolchain
bootstrap). Incremental rebuilds (config changes only) are ~1–2 min.

Then install the artefacts:

```bash
cp build-output/Image \
   src/main/resources/assets/scev/firmware/Image
```

`build-output/` is gitignored — the binary artefacts live in the mod's
resource tree, not here.

## Files

| File | What it is |
| :--- | :--------- |
| `build-kernel.sh`          | The Docker entry point. Runs inside Ubuntu 24.04; see the header comment for the exact `docker run` invocation. |
| `linux_fragment.config`    | Kernel Kconfig fragment. Enables framebuffer console, I2C HID, sound HDA, etc. Applied on top of `qemu_riscv64_virt_defconfig`. |
| `rootfs_overlay/`          | Files to drop into the guest rootfs at absolute paths (e.g. `rootfs_overlay/etc/hostname` becomes `/etc/hostname` inside the guest). Empty by default. |

## Why all this lives in the repo

- The shipped `Image` is a 33 MiB kernel blob. Checking in the recipe
  that produced it lets anyone reproduce / audit the build, rather than
  trusting an opaque binary. LGPL + GPL hygiene also prefers this.
- The Kconfig fragment is genuinely project-specific — it's tuned to
  RVVM's emulated device set (simple-framebuffer, I2C HID, CM8888 HDA).
  Losing it would mean re-discovering the "why is typing silently
  dropped" and "why does DRM=m break the initramfs" gotchas from
  scratch.
- Future work will expand the overlay (test WAVs, init scripts). Those
  belong alongside the mod source that depends on them.

## Deeper docs

- `docs/BUILDROOT.md` — full reproducibility guide, troubleshooting, how
  to regenerate `fw_jump.bin` alongside the kernel.
- `docs/GOTCHAS.md` — known kernel-config traps (I2C HID, DRM, winding,
  JIT coherency).
- `docs/FIRMWARE.md` — how the shipped firmware + kernel pair up at
  runtime (0x80000000 / 0x80200000 layout).
