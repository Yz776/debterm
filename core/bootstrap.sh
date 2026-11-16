#!/usr/bin/env bash
# ==============================================================================
# DebTerm - Debian PRoot Bootstrap & Environment Initializer
# Supports x86_64 (Linux VPS/Desktop testing) and aarch64 (Android native)
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_DIR="$(dirname "$SCRIPT_DIR")"
ROOTFS_DIR="${BASE_DIR}/rootfs/debian"
ARCH="$(uname -m)"

case "$ARCH" in
    x86_64)
        LXC_ARCH="amd64"
        ;;
    aarch64|arm64)
        LXC_ARCH="arm64"
        ;;
    armv7l|armhf)
        LXC_ARCH="armhf"
        ;;
    *)
        echo "[-] Unsupported architecture: $ARCH"
        exit 1
        ;;
esac

echo "[*] Detected architecture: $ARCH (LXC target: $LXC_ARCH)"
echo "[*] RootFS destination: $ROOTFS_DIR"

# Check if rootfs is already installed
if [ -f "$ROOTFS_DIR/bin/bash" ] && [ -f "$ROOTFS_DIR/etc/debian_version" ]; then
    echo "[+] Debian rootfs is already installed at $ROOTFS_DIR"
    echo "[+] Version: $(cat "$ROOTFS_DIR/etc/debian_version")"
    exit 0
fi

mkdir -p "$ROOTFS_DIR"
TMP_TAR="/tmp/debian-rootfs-${LXC_ARCH}.tar.xz"

# Discover latest image timestamp from Linuxcontainers mirror
echo "[*] Discovering latest Debian Bookworm image for $LXC_ARCH..."
BASE_URL="https://images.linuxcontainers.org/images/debian/bookworm/${LXC_ARCH}/default"
LATEST_PATH="$(curl -sL "$BASE_URL/" | grep -oE '[0-9]{8}_[0-9]{2}:[0-9]{2}/' | tail -n 1)"

if [ -z "$LATEST_PATH" ]; then
    echo "[-] Failed to discover image version from index, using fallback snapshot..."
    DOWNLOAD_URL="https://images.linuxcontainers.org/images/debian/bookworm/${LXC_ARCH}/default/20261115_05%3A24/rootfs.tar.xz"
else
    # URL encode colon if needed or use directly
    ENCODED_PATH="$(echo "$LATEST_PATH" | sed 's/:/%3A/g')"
    DOWNLOAD_URL="${BASE_URL}/${ENCODED_PATH}rootfs.tar.xz"
fi

echo "[*] Downloading rootfs from: $DOWNLOAD_URL"
curl -# -L -o "$TMP_TAR" "$DOWNLOAD_URL"

echo "[*] Extracting rootfs to $ROOTFS_DIR..."
# Exclude problematic device files if extracting without root or in proot
tar -xJf "$TMP_TAR" -C "$ROOTFS_DIR" --exclude='./dev/*' || tar -xJf "$TMP_TAR" -C "$ROOTFS_DIR"

rm -f "$TMP_TAR"

# Configure DNS resolver (ensure dangling symlink is removed first)
echo "[*] Configuring DNS (resolv.conf)..."
rm -f "$ROOTFS_DIR/etc/resolv.conf"
cat << 'EOF' > "$ROOTFS_DIR/etc/resolv.conf"
nameserver 1.1.1.1
nameserver 8.8.8.8
EOF

# Setup policy-rc.d shim (enables 100% of all apt packages to install in PRoot)
echo "[*] Configuring policy-rc.d for 100% Debian package compatibility..."
cat << 'EOF' > "$ROOTFS_DIR/usr/sbin/policy-rc.d"
#!/bin/sh
exit 101
EOF
chmod +x "$ROOTFS_DIR/usr/sbin/policy-rc.d"

# Configure default hostname and hosts
echo "debterm" > "$ROOTFS_DIR/etc/hostname"
cat << 'EOF' > "$ROOTFS_DIR/etc/hosts"
127.0.0.1   localhost debterm
::1         localhost ip6-localhost ip6-loopback
EOF

# Ensure /tmp has correct permissions
chmod 1777 "$ROOTFS_DIR/tmp" || true

# Setup bash profile prompt for Termux feel
cat << 'EOF' >> "$ROOTFS_DIR/root/.bashrc"

# DebTerm Custom Prompt & Aliases
export TERM=xterm-256color
PS1='\[\033[01;32m\]root@debterm\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
alias ll='ls -la --color=auto'
alias ls='ls --color=auto'
alias update='apt update && apt upgrade -y'

echo -e "\033[1;36m========================================================\033[0m"
echo -e "\033[1;32m  Welcome to DebTerm (PRoot Debian 12 Environment)      \033[0m"
echo -e "\033[1;33m  Full Glibc & Debian APT Package Repository enabled!   \033[0m"
echo -e "\033[1;36m========================================================\033[0m"
EOF

echo "[+] Bootstrap complete! Ready to launch via PRoot."
