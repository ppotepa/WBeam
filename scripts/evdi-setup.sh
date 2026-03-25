#!/bin/bash
################################################################################
# WBeam EVDI Setup & Repair Script
#
# Automatically detects OS and installs/fixes EVDI capture backend
# Supports: Arch, Debian, Ubuntu, Fedora, RHEL, CentOS
#
# Usage: ./scripts/evdi-setup.sh [--dry-run] [--skip-diagnostic]
################################################################################

set -u

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Flags
DRY_RUN=0
SKIP_DIAGNOSTIC=0
EXIT_ON_ERROR=0

# Detect OS and package manager
detect_os() {
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        echo "$ID"
    else
        echo "unknown"
    fi
}

detect_pm() {
    if command -v pacman &> /dev/null; then
        echo "pacman"
    elif command -v apt &> /dev/null; then
        echo "apt"
    elif command -v dnf &> /dev/null; then
        echo "dnf"
    elif command -v yum &> /dev/null; then
        echo "yum"
    else
        echo "unknown"
    fi
}

# Require root
require_root() {
    if [ "$(id -u)" != "0" ]; then
        echo -e "${RED}✗ This script requires root privileges${NC}"
        echo "  Run with: sudo $0"
        exit 1
    fi
}

# Execute command with error handling
exec_cmd() {
    local desc="$1"
    shift
    
    echo -ne "${BLUE}→${NC} $desc ... "
    
    if [[ $DRY_RUN == 1 ]]; then
        echo -e "${YELLOW}(would run: $@)${NC}"
        return 0
    fi
    
    if output=$("$@" 2>&1); then
        echo -e "${GREEN}✓${NC}"
        [[ -n "$output" ]] && echo "  $output"
        return 0
    else
        echo -e "${RED}✗${NC}"
        echo "  Error: $output"
        return 1
    fi
}

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --dry-run) DRY_RUN=1; shift ;;
        --skip-diagnostic) SKIP_DIAGNOSTIC=1; shift ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

# Header
cat << 'EOF'
╔════════════════════════════════════════════════════════════════════════════╗
║                                                                            ║
║            WBeam EVDI Capture Backend - Setup & Repair Tool              ║
║                                                                            ║
╚════════════════════════════════════════════════════════════════════════════╝

EOF

# System detection
OS=$(detect_os)
PM=$(detect_pm)
KERNEL=$(uname -r)

echo "System Information:"
echo "  OS: $OS"
echo "  Package Manager: $PM"
echo "  Kernel: $KERNEL"
echo ""

if [ "$PM" = "unknown" ]; then
    echo -e "${RED}✗ Could not detect package manager${NC}"
    echo "  Supported: pacman (Arch), apt (Debian/Ubuntu), dnf/yum (Fedora/RHEL)"
    exit 1
fi

if [[ $DRY_RUN == 1 ]]; then
    echo -e "${YELLOW}DRY RUN MODE - No changes will be made${NC}"
    echo ""
fi

# Step 1: Run diagnostics (optional)
if [[ $SKIP_DIAGNOSTIC == 0 ]]; then
    echo ""
    echo "Step 1: Running diagnostics..."
    if [ -f scripts/evdi-diagnose.sh ]; then
        bash scripts/evdi-diagnose.sh --verbose || true
    fi
    echo ""
fi

# Require root from here on
require_root

# Step 2: Update system
echo ""
echo "Step 2: Updating package databases..."
case "$PM" in
    pacman)
        exec_cmd "Update package database" pacman -Sy || true
        ;;
    apt)
        exec_cmd "Update package database" apt-get update || true
        ;;
    dnf)
        exec_cmd "Refresh package cache" dnf check-update || true
        ;;
    yum)
        exec_cmd "Update package database" yum check-update || true
        ;;
esac

# Step 3: Install kernel headers
echo ""
echo "Step 3: Installing kernel headers and build tools..."
case "$PM" in
    pacman)
        exec_cmd "Install linux-headers" pacman -S --noconfirm linux-headers
        exec_cmd "Install base-devel" pacman -S --noconfirm base-devel
        ;;
    apt)
        LINUX_HEADERS="linux-headers-$(uname -r)"
        exec_cmd "Install $LINUX_HEADERS" apt-get install -y "$LINUX_HEADERS"
        exec_cmd "Install build-essential" apt-get install -y build-essential
        ;;
    dnf)
        exec_cmd "Install kernel-devel" dnf install -y kernel-devel
        exec_cmd "Install gcc make" dnf install -y gcc make
        ;;
    yum)
        exec_cmd "Install kernel-devel" yum install -y kernel-devel
        exec_cmd "Install gcc make" yum install -y gcc make
        ;;
esac

# Step 4: Install DKMS
echo ""
echo "Step 4: Installing DKMS..."
case "$PM" in
    pacman)
        exec_cmd "Install dkms" pacman -S --noconfirm dkms
        ;;
    apt)
        exec_cmd "Install dkms" apt-get install -y dkms
        ;;
    dnf)
        exec_cmd "Install dkms" dnf install -y dkms
        ;;
    yum)
        exec_cmd "Install dkms" yum install -y dkms
        ;;
esac

# Step 5: Install EVDI DKMS
echo ""
echo "Step 5: Installing EVDI DKMS module..."
case "$PM" in
    pacman)
        exec_cmd "Install evdi-dkms" pacman -S --noconfirm evdi-dkms || {
            echo -e "${YELLOW}ℹ Note: evdi-dkms might need to be installed from AUR${NC}"
            echo "  Command: yay -S evdi-dkms"
        }
        ;;
    apt)
        # Try official repo first
        if exec_cmd "Install evdi-dkms" apt-get install -y evdi-dkms; then
            true
        else
            echo -e "${YELLOW}ℹ Note: evdi-dkms not in standard repo${NC}"
            echo "  Visit: https://github.com/displaylink-rpm/evdi"
        fi
        ;;
    dnf)
        if exec_cmd "Install evdi-dkms" dnf install -y evdi-dkms; then
            true
        else
            echo -e "${YELLOW}ℹ Note: evdi-dkms needs to be installed from COPR${NC}"
            echo "  Command: sudo dnf copr enable displaylink-rpm/displaylink"
            echo "          sudo dnf install evdi-dkms"
        fi
        ;;
    yum)
        if exec_cmd "Install evdi-dkms" yum install -y evdi-dkms; then
            true
        else
            echo -e "${YELLOW}ℹ Note: evdi-dkms needs to be installed from COPR${NC}"
            echo "  Command: sudo yum copr enable displaylink-rpm/displaylink"
            echo "          sudo yum install evdi-dkms"
        fi
        ;;
esac

# Step 6: Unload and reload EVDI module
echo ""
echo "Step 6: Loading EVDI kernel module..."

if grep -q "^evdi " /proc/modules 2>/dev/null; then
    exec_cmd "Unload EVDI module" modprobe -r evdi
fi

exec_cmd "Load EVDI module with devices" modprobe evdi initial_device_count=1 || {
    echo -e "${RED}✗ Failed to load EVDI module${NC}"
    echo ""
    echo "Possible causes:"
    echo "  1. EVDI DKMS package installation failed"
    echo "  2. Module not compiled for this kernel"
    echo "  3. Secure Boot is preventing module loading"
    echo ""
    echo "To check module build status:"
    echo "  dkms status"
    exit 1
}

# Step 7: Verify device creation
echo ""
echo "Step 7: Verifying device node..."

if [ -e /dev/dri/card0 ]; then
    echo -e "${GREEN}✓${NC} /dev/dri/card0 device created"
else
    echo -e "${RED}✗${NC} /dev/dri/card0 device NOT created"
    echo "  Try: dmesg | tail -20"
    exit 1
fi

# Step 8: Persistent module loading
echo ""
echo "Step 8: Making EVDI load on boot..."

MODPROBE_CONF="/etc/modprobe.d/evdi.conf"

if [[ $DRY_RUN == 1 ]]; then
    echo -e "${YELLOW}(would write to $MODPROBE_CONF)${NC}"
else
    cat > "$MODPROBE_CONF" << 'MODEOF'
# EVDI kernel module options
options evdi initial_device_count=1
MODEOF
    echo -e "${GREEN}✓${NC} Created $MODPROBE_CONF"
fi

# Step 9: User group permissions
echo ""
echo "Step 9: Setting up user permissions..."

CURRENT_USER="${SUDO_USER:-$USER}"
if [ -z "$CURRENT_USER" ]; then
    CURRENT_USER=$(whoami)
fi

if groups "$CURRENT_USER" | grep -q "\bvideo\b"; then
    echo -e "${GREEN}✓${NC} User '$CURRENT_USER' already in video group"
else
    exec_cmd "Add user to video group" usermod -a -G video "$CURRENT_USER"
    
    if [[ $DRY_RUN == 0 ]]; then
        echo -e "${YELLOW}ℹ Important: User group changes take effect on next login${NC}"
        echo "  You need to either:"
        echo "    1. Log out and log back in, OR"
        echo "    2. Run: newgrp video"
    fi
fi

# Step 10: udev rules (if needed)
echo ""
echo "Step 10: Checking udev configuration..."

if [ -d /etc/udev/rules.d ]; then
    EVDI_RULE="/etc/udev/rules.d/50-evdi.rules"
    
    if [ ! -f "$EVDI_RULE" ] || ! grep -q "evdi" "$EVDI_RULE"; then
        if [[ $DRY_RUN == 0 ]]; then
            cat > "$EVDI_RULE" << 'RULEEOF'
# EVDI DRM device rule
KERNEL=="card[0-9]*", SUBSYSTEM=="drm", ATTRS{vendor}=="0x1d0f", MODE="0666"
RULEEOF
            echo -e "${GREEN}✓${NC} Created udev rule"
            exec_cmd "Reload udev rules" udevadm control --reload-rules || true
            exec_cmd "Trigger udev" udevadm trigger || true
        fi
    else
        echo -e "${GREEN}✓${NC} udev rules already configured"
    fi
fi

# Step 11: Verify libevdi
echo ""
echo "Step 11: Checking libevdi library..."

if [ -f /usr/lib/libevdi.so ] || [ -f /usr/lib64/libevdi.so ]; then
    echo -e "${GREEN}✓${NC} libevdi library found"
else
    echo -e "${RED}✗${NC} libevdi.so not found"
    echo "  This should have been installed with evdi-dkms"
    exit 1
fi

# Final summary
echo ""
echo "╔════════════════════════════════════════════════════════════════════════════╗"
echo "║                                                                            ║"

if [[ $DRY_RUN == 1 ]]; then
    echo "║                        ✅ DRY RUN COMPLETE                              ║"
    echo "║                                                                            ║"
    echo "║  Run without --dry-run to apply changes:                                ║"
    echo "║    sudo $0                                                              ║"
else
    echo "║                    ✅ SETUP COMPLETE - EVDI READY                       ║"
fi

echo "║                                                                            ║"
echo "╚════════════════════════════════════════════════════════════════════════════╝"

echo ""
echo "Next steps:"
echo "  1. Start WBeam daemon:"
echo "     cd ~/git/WBeam && ./wbeam host run"
echo ""
echo "  2. Use EVDI capture backend:"
echo "     curl -X POST 'http://localhost:5001/v1/start?capture_backend=evdi'"
echo ""
echo "  3. Or set as default in ~/.config/wbeam/wbeam.conf:"
echo "     [streamer]"
echo "     WBEAM_CAPTURE_BACKEND=evdi"
echo ""

if [[ $DRY_RUN == 0 ]] && ! groups "$CURRENT_USER" | grep -q "\bvideo\b"; then
    echo -e "${YELLOW}⚠️  IMPORTANT: User group changes require logout/login${NC}"
    echo ""
fi

echo "For troubleshooting, run:"
echo "  bash scripts/evdi-diagnose.sh --verbose"
echo ""

exit 0
