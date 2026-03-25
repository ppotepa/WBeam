# EVDI Capture Backend - Complete Setup Guide

## Table of Contents

1. [Quick Start](#quick-start)
2. [System Requirements](#system-requirements)
3. [Automated Setup](#automated-setup)
4. [Manual Setup](#manual-setup)
5. [Verification](#verification)
6. [Troubleshooting](#troubleshooting)
7. [API Usage](#api-usage)
8. [Advanced Configuration](#advanced-configuration)

---

## Quick Start

If you just want EVDI working now:

```bash
# Run automated setup (requires sudo)
cd ~/git/WBeam
sudo bash scripts/evdi-setup.sh

# After completion, verify:
bash scripts/evdi-diagnose.sh

# Then use:
./wbeam host run
# (In another terminal)
curl -X POST "http://localhost:5001/v1/start?capture_backend=evdi"
```

---

## System Requirements

### Supported Distributions

| Distribution | Package Manager | Support Level |
|-------------|-----------------|---------------|
| Arch Linux | pacman | ✅ Full |
| Debian 11+ | apt | ✅ Full |
| Ubuntu 20.04+ | apt | ✅ Full |
| Fedora 38+ | dnf | ✅ Full |
| RHEL 8+ | yum | ✅ Full |
| CentOS 8+ | yum | ✅ Full |

### Hardware Requirements

- Any x86_64 processor
- UEFI or BIOS
- At least 2GB RAM for compilation
- Active internet for package downloads

### Software Requirements

- Linux kernel 4.14 or newer
- Root/sudo access for installation
- 500MB free disk space

### Optional But Recommended

- Secure Boot disabled (if module loading fails)
- Active network connectivity
- Time synchronized with NTP

---

## Automated Setup

The `evdi-setup.sh` script handles everything automatically across all supported distributions.

### Usage

```bash
# Standard setup
sudo bash scripts/evdi-setup.sh

# Dry run (shows what would be done)
sudo bash scripts/evdi-setup.sh --dry-run

# Skip initial diagnostics
sudo bash scripts/evdi-setup.sh --skip-diagnostic
```

### What It Does

1. ✅ Detects OS and package manager
2. ✅ Updates package databases
3. ✅ Installs kernel headers
4. ✅ Installs build tools (gcc, make, etc.)
5. ✅ Installs DKMS (Dynamic Kernel Module Support)
6. ✅ Installs EVDI DKMS package
7. ✅ Compiles and loads EVDI kernel module
8. ✅ Creates device nodes
9. ✅ Configures persistent loading
10. ✅ Sets user group permissions
11. ✅ Configures udev rules

### Success Indicators

Script exits with:
- `0` = Success, EVDI is ready
- `>0` = Errors occurred, see output above

---

## Manual Setup

If you prefer manual control or the script doesn't work for your setup.

### Step 1: Update System

**Arch:**
```bash
sudo pacman -Sy
sudo pacman -S --noconfirm linux-headers
```

**Debian/Ubuntu:**
```bash
sudo apt-get update
sudo apt-get install -y linux-headers-$(uname -r)
```

**Fedora:**
```bash
sudo dnf check-update
sudo dnf install -y kernel-devel gcc make
```

**RHEL/CentOS:**
```bash
sudo yum check-update
sudo yum install -y kernel-devel gcc make
```

### Step 2: Install Build Tools

**Arch:**
```bash
sudo pacman -S --noconfirm base-devel dkms
```

**Debian/Ubuntu:**
```bash
sudo apt-get install -y build-essential dkms
```

**Fedora/RHEL/CentOS:**
```bash
sudo dnf install -y dkms  # or yum install -y dkms
```

### Step 3: Install EVDI

The method depends on your distribution. EVDI is not in all standard repos.

**Arch (via AUR):**
```bash
# If you have yay installed:
yay -S evdi-dkms

# Or manually:
git clone https://aur.archlinux.org/evdi-dkms.git
cd evdi-dkms
makepkg -si
```

**Debian/Ubuntu (official packages):**
```bash
sudo apt-get install -y evdi-dkms
```

**Fedora/RHEL/CentOS (via COPR):**
```bash
# Enable COPR repo (displaylink-rpm project)
sudo dnf copr enable displaylink-rpm/displaylink
sudo dnf install -y evdi-dkms

# Or for yum:
sudo yum copr enable displaylink-rpm/displaylink
sudo yum install -y evdi-dkms
```

If COPR is not available, build from source:
```bash
git clone https://github.com/displaylink-rpm/evdi.git
cd evdi
sudo dkms add .
sudo dkms build -m evdi -v $(cat VERSION.txt)
sudo dkms install -m evdi -v $(cat VERSION.txt)
```

### Step 4: Load EVDI Module

```bash
# First, unload if already loaded
sudo modprobe -r evdi 2>/dev/null || true

# Load with device creation
sudo modprobe evdi initial_device_count=1

# Verify:
ls -la /dev/dri/card0
lsmod | grep evdi
```

### Step 5: Make EVDI Load on Boot

```bash
# Create modprobe configuration
echo "options evdi initial_device_count=1" | sudo tee /etc/modprobe.d/evdi.conf

# Verify (after reboot)
sudo modprobe evdi
ls -la /dev/dri/card0
```

### Step 6: User Permissions

```bash
# Check current user
whoami

# Add to video group (replace USER if needed)
sudo usermod -a -G video $(whoami)

# Apply group change (one of these):
# Option 1: Log out and log back in
# Option 2: Run this command:
newgrp video

# Verify:
groups | grep video
```

---

## Verification

Use the diagnostic script to verify everything is working:

```bash
# Quick check
bash scripts/evdi-diagnose.sh

# Verbose output
bash scripts/evdi-diagnose.sh --verbose

# With fix recommendations
bash scripts/evdi-diagnose.sh --fix
```

### Manual Verification

```bash
# Check module is loaded
lsmod | grep evdi
# Output: evdi                  122880  0

# Check device exists
ls -la /dev/dri/card0
# Output: crw-rw----+ 1 root video 226, 0 ...

# Check user can access
test -r /dev/dri/card0 && test -w /dev/dri/card0 && echo "✓ Readable and writable"

# Test libevdi directly
cat > /tmp/test_evdi.c << 'EOF'
#include <evdi_lib.h>
#include <stdio.h>
int main() {
    enum evdi_device_status s = evdi_check_device(0);
    printf("Device 0: %d (0=ready)\n", s);
    if (s == 0) {
        evdi_handle h = evdi_open(0);
        if (h) {
            printf("✓ Successfully opened\n");
            evdi_close(h);
            return 0;
        }
    }
    return 1;
}
EOF
gcc -o /tmp/test_evdi /tmp/test_evdi.c -levdi && /tmp/test_evdi
```

---

## Troubleshooting

### Problem: Module not loading after installation

**Symptoms:**
```
$ sudo modprobe evdi
modprobe: ERROR: could not insert 'evdi': No such file or directory
```

**Solutions:**

1. **Check DKMS status:**
```bash
dkms status
# Should show: evdi/X.X.XX, X.XX.X: added, built
```

2. **If status is "added" but not "built", rebuild:**
```bash
EVDI_VERSION=$(ls /usr/src/ | grep evdi | head -1 | sed 's/evdi-//')
sudo dkms build -m evdi -v $EVDI_VERSION
sudo dkms install -m evdi -v $EVDI_VERSION
```

3. **If compilation fails:**
```bash
# Check for errors
cat /var/lib/dkms/evdi/*/build/make.log

# Ensure kernel headers match kernel:
uname -r
dpkg -l | grep "linux-headers-$(uname -r)"  # Debian/Ubuntu
rpm -q kernel-devel-$(uname -r)             # Fedora/RHEL
```

4. **Secure Boot preventing loading:**
- Disable Secure Boot in BIOS/UEFI, OR
- Sign the module (advanced)

### Problem: Device not created

**Symptoms:**
```
$ ls /dev/dri/card0
ls: cannot access '/dev/dri/card0': No such file or directory
```

**Solutions:**

1. **Check module is actually loaded:**
```bash
lsmod | grep evdi  # Must show output
cat /sys/module/evdi/parameters/initial_device_count  # Should be 1
```

2. **If module not loaded, try:**
```bash
sudo modprobe evdi initial_device_count=1
```

3. **If still not showing, check kernel messages:**
```bash
dmesg | tail -50 | grep -i evdi
# Look for error messages
```

4. **Try manual device creation (advanced):**
```bash
sudo bash -c 'echo 1 > /sys/module/evdi/parameters/initial_device_count'
# Then:
sudo udevadm trigger
```

### Problem: Permission denied accessing device

**Symptoms:**
```
$ curl -X POST "http://localhost:5001/v1/start?capture_backend=evdi"
[libevdi] Failed to open a device: Permission denied
```

**Solutions:**

1. **Check file permissions:**
```bash
ls -la /dev/dri/card0
# Should have rw for group video
# crw-rw----+ 1 root video 226, 0
```

2. **Verify user is in video group:**
```bash
groups | grep video
# If not shown, run:
sudo usermod -a -G video $(whoami)
# Then log out and back in (or: newgrp video)
```

3. **Check group ID:**
```bash
id
# Should show: groups=...,983(video) or similar
```

4. **If still failing, check udev rules:**
```bash
cat /etc/udev/rules.d/50-evdi.rules
# Should contain EVDI device rules
```

### Problem: libevdi not found

**Symptoms:**
```
gcc: error while loading shared libraries: libevdi.so: cannot open shared object file
```

**Solutions:**

1. **Check library is installed:**
```bash
find /usr -name "libevdi.so*" 2>/dev/null
# Should find: /usr/lib/libevdi.so or /usr/lib64/libevdi.so
```

2. **If not found, reinstall EVDI:**
```bash
sudo pacman -S --noconfirm evdi-dkms   # Arch
sudo apt-get install --reinstall evdi-dkms  # Debian/Ubuntu
# etc.
```

3. **Update library cache:**
```bash
sudo ldconfig
```

### Problem: Stream starts but no video

**Symptoms:**
```
State: STARTING (stays there)
Stream process alive: true
But no video flowing to Android
```

**Solutions:**

1. **Check if EVDI is really being used:**
```bash
# Look for these log messages:
./wbeam host debug 2>&1 | grep -i "using evdi\|evdi.*capture"
```

2. **Ensure correct parameter:**
```bash
# Must use: capture_backend (not "backend")
curl -X POST "http://localhost:5001/v1/start?capture_backend=evdi"
```

3. **Check if display/framebuffer is active:**
```bash
# EVDI needs an active display. If in headless mode, add virtual display:
sudo Xvfb :99 -screen 0 1920x1080x24 &
export DISPLAY=:99
```

4. **Review daemon logs:**
```bash
tail -100 logs/*.host*.log | grep -i error
tail -100 logs/*host-rust*/wbeamd-rust.log* | grep -i evdi
```

---

## API Usage

### Starting a Stream with EVDI

```bash
# Via curl
curl -X POST "http://localhost:5001/v1/start?capture_backend=evdi"

# Or with additional parameters
curl -X POST "http://localhost:5001/v1/start?serial=default&capture_backend=evdi"

# With JSON body for encoder selection
curl -X POST http://localhost:5001/v1/start?capture_backend=evdi \
  -H "Content-Type: application/json" \
  -d '{
    "encoder": "h264",
    "profile": "adaptive",
    "bitrate_kbps": 20000
  }'
```

### Response Structure

```json
{
  "state": "STARTING",
  "effective_runtime_config": {
    "capture_backend": "evdi",
    "requested_encoder": "h264",
    "size": "1920x1080",
    "fps": 60
  },
  "stream_process_alive": true,
  "ok": true
}
```

### Checking Status

```bash
# Get health/status
curl -s http://localhost:5001/v1/health | jq '.state, .effective_runtime_config.capture_backend'

# Monitor real-time
watch -n 1 'curl -s http://localhost:5001/v1/health | jq ".state"'
```

---

## Advanced Configuration

### Make EVDI the Default Backend

Edit `~/.config/wbeam/wbeam.conf`:

```ini
[streamer]
WBEAM_CAPTURE_BACKEND=evdi
```

Then all streams automatically use EVDI without the query parameter.

### Set Device Count

If you need multiple virtual devices:

```bash
# At runtime
sudo modprobe -r evdi
sudo modprobe evdi initial_device_count=4

# Or persistent
echo "options evdi initial_device_count=4" | sudo tee /etc/modprobe.d/evdi.conf
```

### Enable EVDI Logging

For advanced debugging:

```bash
# Set logging level in daemon environment
export WBEAM_LOG_LEVEL=debug
./wbeam host run

# Check streamer output
RUST_LOG=debug ./wbeam host debug 2>&1 | grep -i evdi
```

### Monitor EVDI Device Status

```bash
# Watch EVDI in sysfs
watch -n 1 'cat /sys/module/evdi/parameters/initial_device_count && ls -la /dev/dri/card0'

# Check EDID
cat /sys/devices/platform/evdi.0/drm/card0/edid | od -x | head -10
```

---

## FAQs

**Q: Does EVDI work with Wayland?**  
A: Yes! EVDI captures at the kernel level, bypassing the display server entirely.

**Q: Will EVDI use more CPU?**  
A: No, direct kernel capture uses less CPU than portal/X11 methods.

**Q: Can I use both EVDI and Wayland portal?**  
A: Yes, switch between them dynamically without restarting the daemon.

**Q: Does EVDI survive sleep/suspend?**  
A: Module usually reloads on resume, but you may need to restart the daemon.

**Q: What resolution does EVDI support?**  
A: Fixed at EDID 1920×1080. Downstream pipeline scales to requested size.

**Q: Can I use EVDI with multiple monitors?**  
A: EVDI provides one virtual device. For multiple streams, run separate daemons.

---

## Getting Help

### If the scripts don't work for your distro:

1. **Run diagnostics with verbose output:**
```bash
bash scripts/evdi-diagnose.sh --verbose
```

2. **Check system details:**
```bash
cat /etc/os-release
uname -r
which pacman apt dnf yum 2>/dev/null
```

3. **Look at build logs:**
```bash
# For DKMS
dkms status
cat /var/lib/dkms/evdi/*/build/make.log

# For modprobe
dmesg | tail -50
journalctl -n 50
```

4. **Check existing issues:**
- https://github.com/displaylink-rpm/evdi/issues
- https://github.com/your-repo/WBeam/issues

---

## Summary

| Step | Automated | Manual | Time |
|------|-----------|--------|------|
| Detect OS | ✓ | Manual | <1s |
| Install headers | ✓ | Paste 1 cmd | 2-5min |
| Install DKMS | ✓ | Paste 1 cmd | <1min |
| Install EVDI | ✓ | Paste 1-5 cmds | 3-10min |
| Load module | ✓ | Paste 2 cmds | <1min |
| Permissions | ✓ | Paste 1 cmd | <1min |
| **Total** | **5-20min** | **5-25min** | - |

**Automated:** Run `sudo bash scripts/evdi-setup.sh`  
**Result:** EVDI ready to use with `?capture_backend=evdi` parameter
