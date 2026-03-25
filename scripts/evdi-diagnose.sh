#!/bin/bash
################################################################################
# WBeam EVDI Diagnostic Tool
# 
# Detects issues preventing EVDI capture backend from working
# Cross-platform: supports Arch, Debian, Ubuntu, Fedora, RHEL, CentOS
#
# Usage: ./scripts/evdi-diagnose.sh [--verbose] [--fix-recommendations]
################################################################################

set -u

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Global flags
VERBOSE=0
FIX_RECOMMENDATIONS=0
ERRORS=0
WARNINGS=0

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --verbose|-v) VERBOSE=1; shift ;;
        --fix|-f) FIX_RECOMMENDATIONS=1; shift ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

print_header() {
    echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
}

print_check() {
    echo -ne "${BLUE}→${NC} $1 ... "
}

pass() {
    echo -e "${GREEN}✓${NC}"
    [[ $VERBOSE == 1 ]] && echo "  Details: $1"
}

fail() {
    echo -e "${RED}✗${NC}"
    ((ERRORS++))
    echo -e "  ${RED}Error: $1${NC}"
}

warn() {
    echo -e "${YELLOW}⚠${NC}"
    ((WARNINGS++))
    echo -e "  ${YELLOW}Warning: $1${NC}"
}

# Detect OS
detect_os() {
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        echo "$ID"
    else
        uname -s
    fi
}

# Detect package manager
detect_package_manager() {
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

# Get package name for different distros
get_package_name() {
    local pkg=$1
    local pm=$2
    
    case "$pm" in
        pacman)
            case "$pkg" in
                evdi) echo "evdi-dkms" ;;
                modprobe) echo "kmod" ;;
                *) echo "$pkg" ;;
            esac
            ;;
        apt)
            case "$pkg" in
                evdi) echo "evdi-dkms" ;;
                dkms) echo "dkms" ;;
                linux-headers) echo "linux-headers-$(uname -r)" ;;
                *) echo "$pkg" ;;
            esac
            ;;
        dnf|yum)
            case "$pkg" in
                evdi) echo "evdi-dkms" ;;
                dkms) echo "dkms" ;;
                linux-headers) echo "kernel-devel" ;;
                *) echo "$pkg" ;;
            esac
            ;;
    esac
}

# Check if package is installed
is_package_installed() {
    local pkg=$1
    local pm=$2
    
    case "$pm" in
        pacman)
            pacman -Q "$pkg" &> /dev/null
            ;;
        apt)
            dpkg -l | grep -q "^ii.*$pkg"
            ;;
        dnf|yum)
            rpm -q "$pkg" &> /dev/null
            ;;
        *)
            return 1
            ;;
    esac
}

# Main diagnostic flow
print_header "WBeam EVDI Capture Backend Diagnostics"

OS=$(detect_os)
PM=$(detect_package_manager)

echo ""
echo "System Information:"
echo "  OS: $OS"
echo "  Package Manager: $PM"
echo "  Kernel: $(uname -r)"
echo "  User: $(whoami)"
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# 1. Module Installation Check
# ─────────────────────────────────────────────────────────────────────────────

print_header "1. EVDI Module Installation"

print_check "EVDI module package installed"
if is_package_installed "evdi-dkms" "$PM"; then
    EVDI_VERSION=$(pacman -Q evdi-dkms 2>/dev/null | awk '{print $2}' || \
                   dpkg -l | grep evdi-dkms | awk '{print $3}' || \
                   rpm -q evdi-dkms 2>/dev/null | sed 's/.*-//' || echo "unknown")
    pass "EVDI DKMS version $EVDI_VERSION"
else
    fail "EVDI DKMS package not installed"
    if [[ $FIX_RECOMMENDATIONS == 1 ]]; then
        echo ""
        echo "  📦 To install EVDI DKMS:"
        case "$PM" in
            pacman) echo "    sudo pacman -S --noconfirm evdi-dkms" ;;
            apt) echo "    sudo apt install -y evdi-dkms dkms linux-headers-\$(uname -r)" ;;
            dnf) echo "    sudo dnf install -y evdi-dkms dkms kernel-devel" ;;
            yum) echo "    sudo yum install -y evdi-dkms dkms kernel-devel" ;;
        esac
    fi
fi

print_check "Linux headers installed (needed for DKMS build)"
case "$PM" in
    pacman)
        is_package_installed "linux-headers" "$PM" && pass "linux-headers" || warn "linux-headers not installed"
        ;;
    apt)
        if dpkg -l | grep -q "^ii.*linux-headers"; then
            pass "linux-headers"
        else
            warn "linux-headers not installed"
        fi
        ;;
    dnf|yum)
        if rpm -q kernel-devel &> /dev/null; then
            pass "kernel-devel"
        else
            warn "kernel-devel not installed"
        fi
        ;;
esac

# ─────────────────────────────────────────────────────────────────────────────
# 2. Module Loading Check
# ─────────────────────────────────────────────────────────────────────────────

print_header "2. Module Loading & Device Creation"

print_check "EVDI module loaded"
if grep -q "^evdi " /proc/modules 2>/dev/null; then
    DEVICE_COUNT=$(cat /sys/module/evdi/parameters/initial_device_count 2>/dev/null || echo "unknown")
    pass "Module loaded (initial_device_count=$DEVICE_COUNT)"
else
    fail "EVDI module not loaded"
    if [[ $FIX_RECOMMENDATIONS == 1 ]]; then
        echo ""
        echo "  🔧 To load EVDI module:"
        echo "    sudo modprobe evdi initial_device_count=1"
        echo ""
        echo "  🔧 To make it persistent across reboots:"
        echo "    echo 'options evdi initial_device_count=1' | sudo tee /etc/modprobe.d/evdi.conf"
    fi
fi

print_check "EVDI device nodes exist"
if [ -e /dev/dri/card0 ]; then
    pass "/dev/dri/card0 exists"
    # Check if it's EVDI
    if ls -la /sys/class/drm/card0 2>/dev/null | grep -q evdi; then
        pass "  card0 is controlled by EVDI"
    fi
else
    fail "/dev/dri/card0 does not exist"
    if [[ $FIX_RECOMMENDATIONS == 1 ]]; then
        echo ""
        echo "  ℹ️  Possible causes:"
        echo "    1. EVDI module not loaded"
        echo "    2. Module loaded with initial_device_count=0"
        echo "    3. udev rules issue"
    fi
fi

# ─────────────────────────────────────────────────────────────────────────────
# 3. Library Check
# ─────────────────────────────────────────────────────────────────────────────

print_header "3. EVDI Library Check"

print_check "libevdi library installed"
if [ -f /usr/lib/libevdi.so ] || [ -f /usr/lib64/libevdi.so ]; then
    LIBEVDI_VERSION=$(strings /usr/lib/libevdi.so 2>/dev/null | grep "^1\." | head -1 || strings /usr/lib64/libevdi.so 2>/dev/null | grep "^1\." | head -1 || echo "unknown")
    pass "libevdi $LIBEVDI_VERSION"
else
    fail "libevdi.so not found"
fi

print_check "libevdi can be loaded"
if LD_LIBRARY_PATH=/usr/lib:/usr/lib64 ldd /usr/lib/libevdi.so 2>/dev/null | grep -q "not found"; then
    fail "libevdi has unresolved dependencies"
else
    pass "All dependencies satisfied"
fi

# ─────────────────────────────────────────────────────────────────────────────
# 4. Permissions Check
# ─────────────────────────────────────────────────────────────────────────────

print_header "4. User Permissions"

CURRENT_USER=$(whoami)
print_check "User in 'video' group"
if groups "$CURRENT_USER" | grep -q "\bvideo\b"; then
    pass "User '$CURRENT_USER' is in video group"
else
    fail "User '$CURRENT_USER' is NOT in video group"
    if [[ $FIX_RECOMMENDATIONS == 1 ]]; then
        echo ""
        echo "  🔧 To add user to video group:"
        echo "    sudo usermod -a -G video $CURRENT_USER"
        echo ""
        echo "  ℹ️  After running this, you need to:"
        echo "    1. Log out and log back in, OR"
        echo "    2. Run: newgrp video"
    fi
fi

print_check "Device node readable/writable"
if [ -e /dev/dri/card0 ]; then
    if [ -r /dev/dri/card0 ] && [ -w /dev/dri/card0 ]; then
        pass "Permissions OK"
    else
        fail "Cannot read/write /dev/dri/card0"
        if [[ $FIX_RECOMMENDATIONS == 1 ]]; then
            echo ""
            echo "  🔧 Check current permissions:"
            echo "    ls -la /dev/dri/card0"
            echo ""
            echo "  🔧 Add to video group (usually fixes this):"
            echo "    sudo usermod -a -G video \$(whoami)"
        fi
    fi
else
    warn "Cannot check - /dev/dri/card0 does not exist"
fi

# ─────────────────────────────────────────────────────────────────────────────
# 5. Functional Test
# ─────────────────────────────────────────────────────────────────────────────

print_header "5. Functional Test"

print_check "EVDI device accessible from user code"
if command -v modinfo &> /dev/null; then
    if modinfo evdi &> /dev/null; then
        pass "modinfo can read EVDI module"
    else
        warn "modinfo evdi failed"
    fi
else
    warn "modinfo not available"
fi

print_check "Test with libevdi directly"
if [ -f /usr/lib/libevdi.so ] || [ -f /usr/lib64/libevdi.so ]; then
    # Try to compile and run test
    cat > /tmp/test_evdi_minimal.c << 'CEOF'
#include <evdi_lib.h>
#include <stdio.h>
#include <stdlib.h>
int main() {
    for (int i = 0; i < 2; i++) {
        enum evdi_device_status status = evdi_check_device(i);
        if (status == 0) { /* AVAILABLE */
            evdi_handle h = evdi_open(i);
            if (h) {
                evdi_close(h);
                exit(0); /* Success */
            }
        }
    }
    exit(1); /* No device found or failed to open */
}
CEOF
    if gcc -o /tmp/test_evdi_minimal /tmp/test_evdi_minimal.c -levdi 2>/dev/null && \
       /tmp/test_evdi_minimal 2>/dev/null; then
        pass "Successfully opened EVDI device with libevdi"
    else
        if [ -e /dev/dri/card0 ]; then
            warn "Could not open EVDI device (may need permissions or module reload)"
        else
            fail "No EVDI device found"
        fi
    fi
    rm -f /tmp/test_evdi_minimal /tmp/test_evdi_minimal.c
else
    warn "Cannot test - libevdi.so not found"
fi

# ─────────────────────────────────────────────────────────────────────────────
# 6. WBeam Configuration Check
# ─────────────────────────────────────────────────────────────────────────────

print_header "6. WBeam Configuration"

print_check "WBeam config file exists"
if [ -f ~/.config/wbeam/wbeam.conf ]; then
    pass "Config exists"
    
    print_check "EVDI backend configuration"
    if grep -q "WBEAM_CAPTURE_BACKEND.*evdi" ~/.config/wbeam/wbeam.conf; then
        pass "EVDI explicitly configured"
    else
        echo -e "${YELLOW}ℹ${NC}  Not configured as default (will use ?capture_backend=evdi parameter)"
    fi
else
    pass "Using defaults"
fi

print_check "WBeam daemon executable exists"
if [ -f ./wbeam ]; then
    pass "wbeam script found"
else
    fail "wbeam script not found in current directory"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Summary
# ─────────────────────────────────────────────────────────────────────────────

print_header "Summary"

echo ""
if [ $ERRORS -eq 0 ] && [ $WARNINGS -eq 0 ]; then
    echo -e "${GREEN}✅ All checks passed! EVDI is ready to use.${NC}"
    echo ""
    echo "Quick start:"
    echo "  1. ./wbeam host run"
    echo "  2. curl -X POST 'http://localhost:5001/v1/start?capture_backend=evdi'"
elif [ $ERRORS -eq 0 ]; then
    echo -e "${YELLOW}⚠️  $WARNINGS warning(s) found - EVDI should mostly work${NC}"
else
    echo -e "${RED}❌ $ERRORS error(s) found - EVDI will not work${NC}"
    echo ""
    echo "Action required:"
    if [[ $FIX_RECOMMENDATIONS == 0 ]]; then
        echo "  Run with --fix flag to see repair suggestions:"
        echo "    $0 --fix"
    fi
fi

echo ""
echo "Report:"
echo "  Errors: $ERRORS"
echo "  Warnings: $WARNINGS"
echo ""

exit $ERRORS
