#!/bin/bash
# Build the RV64 Linux kernel + initramfs shipped with Scalar Evolution.
#
# Runs inside a Docker Ubuntu 24.04 container (Buildroot isn't officially
# supported on macOS). Outputs an ~33 MiB `Image` (raw kernel + embedded
# BusyBox initramfs) + `rootfs.cpio.gz` to /output.
#
# Expected Docker invocation (from the repo root):
#
#     docker volume create scev-buildroot-cache
#     mkdir -p build-output
#     docker run --rm \
#         -v "$(pwd)/tools/buildroot:/work" \
#         -v scev-buildroot-cache:/buildroot \
#         -v "$(pwd)/build-output:/output" \
#         --memory=8g --cpus=6 \
#         ubuntu:24.04 bash /work/build-kernel.sh
#
# Then drop the artefacts into the mod's resource tree:
#
#     cp build-output/Image \
#        src/main/resources/assets/scev/firmware/Image
#
# See docs/BUILDROOT.md for the full story (why this recipe, how to bump
# the kernel, how to regenerate fw_jump.bin, etc.).
#
# Copyright (c) 2026 Scalar Evolution contributors.
# SPDX-License-Identifier: LGPL-3.0-or-later

set -euo pipefail

export DEBIAN_FRONTEND=noninteractive

# Expected mount points inside the container.
WORK_DIR=${WORK_DIR:-/work}                # this directory (tools/buildroot)
CACHE_DIR=${CACHE_DIR:-/buildroot}         # Buildroot source + toolchain cache
OUTPUT_DIR=${OUTPUT_DIR:-/output}          # where Image / rootfs.cpio.gz land
BR_TAG=${BR_TAG:-2026.02}                  # pinned Buildroot tag

echo "=== Installing host dependencies ==="
apt-get update -qq
apt-get install -y -qq \
    build-essential git python3 rsync bc wget cpio unzip patch perl file bsdmainutils \
    libncurses5-dev libssl-dev gawk bison flex \
    >/dev/null
echo "    ...deps installed"

echo ""
echo "=== Cloning Buildroot ${BR_TAG} ==="
if [ ! -d "${CACHE_DIR}/.git" ]; then
    git clone --depth 1 --branch "${BR_TAG}" https://github.com/buildroot/buildroot "${CACHE_DIR}"
fi

cd "${CACHE_DIR}"
echo "    Buildroot version: $(git describe --tags --always)"

echo ""
echo "=== Applying qemu_riscv64_virt_defconfig ==="
make qemu_riscv64_virt_defconfig

echo ""
echo "=== Installing kernel config fragment ==="
# Copy the version-controlled fragment into the Buildroot tree.
cp "${WORK_DIR}/linux_fragment.config" "${CACHE_DIR}/linux_fragment.config"

# Re-register the fragment with Buildroot's .config (idempotent).
sed -i '/^BR2_LINUX_KERNEL_CONFIG_FRAGMENT_FILES/d' .config
echo "BR2_LINUX_KERNEL_CONFIG_FRAGMENT_FILES=\"${CACHE_DIR}/linux_fragment.config\"" >> .config

echo ""
echo "=== Switching rootfs to embedded initramfs ==="
# Remove the default ext2/tar rootfs outputs — we ship a single self-
# contained kernel Image with the BusyBox userspace baked in via cpio
# → CONFIG_INITRAMFS_SOURCE.
sed -i '/^BR2_TARGET_ROOTFS_EXT2/d' .config
sed -i '/^BR2_TARGET_ROOTFS_TAR/d' .config
sed -i '/^BR2_TARGET_ROOTFS_CPIO/d' .config
echo 'BR2_TARGET_ROOTFS_CPIO=y' >> .config
echo 'BR2_TARGET_ROOTFS_CPIO_GZIP=y' >> .config
sed -i '/^BR2_TARGET_ROOTFS_INITRAMFS/d' .config
echo 'BR2_TARGET_ROOTFS_INITRAMFS=y' >> .config

echo ""
echo "=== Enabling ALSA userspace (aplay + amixer) ==="
# Guest-side audio tooling so the player can `aplay foo.wav` through
# RVVM's HDA emulator into the mod's server-side stream pipeline.
sed -i '/^BR2_PACKAGE_ALSA_UTILS/d' .config
sed -i '/^BR2_PACKAGE_ALSA_LIB/d' .config
echo 'BR2_PACKAGE_ALSA_LIB=y' >> .config
echo 'BR2_PACKAGE_ALSA_UTILS=y' >> .config
echo 'BR2_PACKAGE_ALSA_UTILS_APLAY=y' >> .config
echo 'BR2_PACKAGE_ALSA_UTILS_AMIXER=y' >> .config

echo ""
echo "=== Wiring up rootfs overlay ==="
# Any files placed under tools/buildroot/rootfs_overlay/ land in the
# guest rootfs at the corresponding absolute paths. Empty by default;
# useful for shipping init scripts, test assets, etc.
sed -i '/^BR2_ROOTFS_OVERLAY/d' .config
echo "BR2_ROOTFS_OVERLAY=\"${WORK_DIR}/rootfs_overlay\"" >> .config

make olddefconfig

echo ""
echo "=== Buildroot-side config summary ==="
for opt in BR2_TARGET_ROOTFS_CPIO BR2_TARGET_ROOTFS_INITRAMFS \
           BR2_LINUX_KERNEL_CONFIG_FRAGMENT_FILES BR2_TARGET_ROOTFS_EXT2 \
           BR2_ROOTFS_OVERLAY; do
    grep -E "^${opt}=|^# ${opt} " .config || echo "  (${opt} not set)"
done

echo ""
echo "=== Forcing kernel rebuild so initramfs is re-embedded ==="
rm -rf "${CACHE_DIR}/output/build/linux-"[0-9]* 2>/dev/null || true
rm -rf "${CACHE_DIR}/output/images/Image" 2>/dev/null || true
make linux-dirclean 2>/dev/null || true
make rootfs-cpio-dirclean 2>/dev/null || true

echo ""
echo "=== Running full build (incremental — cross toolchain cached) ==="
date
make -j"$(nproc)" 2>&1 | tail -150
date

echo ""
echo "=== Verifying required CONFIG options were enabled ==="
LINUX_CONFIG=$(find "${CACHE_DIR}/output/build" -maxdepth 2 -name ".config" -path "*linux-*" | head -1)
if [ -n "$LINUX_CONFIG" ]; then
    for opt in CONFIG_FB_SIMPLE CONFIG_FRAMEBUFFER_CONSOLE CONFIG_USB_HID CONFIG_USB_XHCI_HCD \
               CONFIG_DEVTMPFS_MOUNT CONFIG_INPUT_EVDEV CONFIG_BLK_DEV_INITRD \
               CONFIG_INITRAMFS_SOURCE CONFIG_I2C CONFIG_I2C_OCORES CONFIG_I2C_HID \
               CONFIG_I2C_HID_CORE CONFIG_I2C_HID_OF \
               CONFIG_SND_HDA_INTEL CONFIG_SND_HDA_GENERIC CONFIG_SND_HDA_CODEC_CMEDIA; do
        if grep -q "^${opt}=y" "$LINUX_CONFIG" || grep -q "^${opt}=\"" "$LINUX_CONFIG"; then
            echo "  OK: $(grep "^${opt}=" "$LINUX_CONFIG")"
        else
            echo "  WARN: $opt not set!"
            grep "$opt" "$LINUX_CONFIG" | head -3 || true
        fi
    done
else
    echo "  WARN: could not find kernel .config"
fi

echo ""
echo "=== Collecting outputs ==="
ls -la "${CACHE_DIR}/output/images/"
mkdir -p "${OUTPUT_DIR}"
cp "${CACHE_DIR}/output/images/Image" "${OUTPUT_DIR}/Image"
cp -f "${CACHE_DIR}/output/images/rootfs.cpio.gz" "${OUTPUT_DIR}/" 2>/dev/null || \
cp -f "${CACHE_DIR}/output/images/rootfs.cpio"    "${OUTPUT_DIR}/" 2>/dev/null || true
ls -lh "${OUTPUT_DIR}/"

echo ""
echo "=== First 128 bytes of Image (expect 'RISCV\\0\\0\\0' at offset 0x30=48) ==="
xxd -l 128 "${OUTPUT_DIR}/Image"

echo ""
echo "=== Image size: $(stat -c '%s' "${OUTPUT_DIR}/Image") bytes ==="
echo "=== DONE ==="
