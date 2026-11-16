#!/usr/bin/env bash
# ==============================================================================
# DebTerm Runner - Starts Debian under PRoot
# Emulates root (UID 0), binds necessary host pseudo-filesystems (/dev, /proc, /sys)
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_DIR="$(dirname "$SCRIPT_DIR")"
ROOTFS_DIR="${BASE_DIR}/rootfs/debian"

# Check if bootstrap has run
if [ ! -f "$ROOTFS_DIR/bin/bash" ]; then
    echo "[!] Debian rootfs not found. Running bootstrap..."
    bash "$SCRIPT_DIR/bootstrap.sh"
fi

# Ensure proot exists
if ! command -v proot &>/dev/null; then
    echo "[-] proot command not found! Please install proot (e.g. apt install proot)."
    exit 1
fi

# Ensure compatibility across modern Linux kernels and Android environments
export PROOT_NO_SECCOMP=1

# Setup DNS inside container
if [ ! -f "$ROOTFS_DIR/etc/resolv.conf" ] || [ -L "$ROOTFS_DIR/etc/resolv.conf" ]; then
    rm -f "$ROOTFS_DIR/etc/resolv.conf"
    printf "nameserver 1.1.1.1\nnameserver 8.8.8.8\n" > "$ROOTFS_DIR/etc/resolv.conf"
fi

# Base bindings
BINDS=(
    -b /dev
    -b /proc
    -b /sys
    -b /dev/pts
    -b /dev/shm
)

# Hardware Direct Access: GPU, DMA Heaps, Ashmem, Ion
HW_NODES=(
    /dev/kgsl-3d0      # Qualcomm Adreno GPU
    /dev/mali0         # ARM Mali GPU
    /dev/mali          # ARM Mali legacy
    /dev/dri           # Direct Rendering Infrastructure
    /dev/nvhost-gpu    # Nvidia Tegra GPU
    /dev/pvr_sync      # PowerVR GPU
    /dev/ashmem        # Android Shared Memory
    /dev/ion           # Android ION memory
    /dev/dma_heap      # DMA-BUF heaps
    /dev/graphics      # Display Framebuffer
)
for node in "${HW_NODES[@]}"; do
    if [ -e "$node" ]; then
        BINDS+=(-b "$node")
    fi
done

# Android System, Vendor Drivers & Apex
ANDROID_DIRS=(
    /system
    /vendor
    /apex
    /sys/devices/system/cpu
    /sys/class/thermal
    /sys/class/power_supply
)
for adir in "${ANDROID_DIRS[@]}"; do
    if [ -d "$adir" ]; then
        BINDS+=(-b "$adir")
    fi
done

# Storage mounts
if [ -d "/sdcard" ]; then
    BINDS+=(-b /sdcard:/sdcard)
fi
if [ -d "$HOME" ]; then
    BINDS+=(-b "$HOME":/root/host-home)
fi

# Environment variables
ENV_VARS=(
    HOME=/root
    USER=root
    LOGNAME=root
    TERM="${TERM:-xterm-256color}"
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    LANG=C.UTF-8
    SHELL=/bin/bash
)

# PRoot execution command
# -0 : Emulate root UID 0
# -r : Root directory
# -w : Working directory inside rootfs
# -b : Bind mounts
exec proot \
    -0 \
    -r "$ROOTFS_DIR" \
    -w /root \
    "${BINDS[@]}" \
    /usr/bin/env -i "${ENV_VARS[@]}" \
    /bin/bash --login "$@"
