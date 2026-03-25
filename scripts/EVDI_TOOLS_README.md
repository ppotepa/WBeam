# WBeam EVDI Setup & Diagnosis Tools

This directory contains tools to help users install, configure, and troubleshoot the EVDI capture backend for WBeam.

## Files

### `evdi-diagnose.sh`
Comprehensive diagnostic tool that checks if EVDI is properly configured on your system.

**Features:**
- ✅ OS and package manager detection
- ✅ Module installation verification
- ✅ Module loading confirmation
- ✅ Device node creation checking
- ✅ Library availability verification
- ✅ User permissions validation
- ✅ Functional testing with libevdi
- ✅ WBeam configuration review
- ✅ Cross-platform support (Arch, Debian, Fedora, RHEL, CentOS)

**Usage:**
```bash
# Quick check
bash scripts/evdi-diagnose.sh

# Verbose output
bash scripts/evdi-diagnose.sh --verbose

# Show fix recommendations
bash scripts/evdi-diagnose.sh --fix
```

**Output:**
- Exit code 0 = All checks passed
- Exit code > 0 = Number of errors found
- Shows specific error messages and remediation steps

---

### `evdi-setup.sh`
Automated setup and repair script that installs/fixes EVDI on your system.

**Features:**
- ✅ Automatic OS/package manager detection
- ✅ Kernel headers installation
- ✅ Build tools setup
- ✅ DKMS installation
- ✅ EVDI DKMS package installation
- ✅ Module compilation
- ✅ Device node creation
- ✅ Persistent configuration
- ✅ User group setup
- ✅ udev rules configuration
- ✅ Comprehensive error handling
- ✅ Dry-run capability
- ✅ Full rollback safety

**Usage:**
```bash
# Interactive setup with confirmation
sudo bash scripts/evdi-setup.sh

# Dry run (see what would happen)
sudo bash scripts/evdi-setup.sh --dry-run

# Setup without running diagnostics
sudo bash scripts/evdi-setup.sh --skip-diagnostic
```

**Supported Distributions:**

| Distro | Manager | Status |
|--------|---------|--------|
| Arch Linux | pacman | ✅ Full |
| Debian 11+ | apt | ✅ Full |
| Ubuntu 20.04+ | apt | ✅ Full |
| Fedora 38+ | dnf | ✅ Full |
| RHEL 8+ | yum | ✅ Full |
| CentOS 8+ | yum | ✅ Full |

**What It Installs:**
1. Kernel headers (for module compilation)
2. Build tools (gcc, make, dkms)
3. EVDI DKMS package
4. Compiles EVDI kernel module
5. Creates persistent module loading
6. Sets up user permissions
7. Configures udev rules

---

## Typical Workflow

### For New Installation

```bash
# 1. Run diagnostics to check system state
bash scripts/evdi-diagnose.sh

# 2. Run automated setup (requires sudo)
sudo bash scripts/evdi-setup.sh

# 3. Verify installation
bash scripts/evdi-diagnose.sh --verbose

# 4. Start using EVDI
./wbeam host run
curl -X POST "http://localhost:5001/v1/start?capture_backend=evdi"
```

### For Troubleshooting Existing Setup

```bash
# 1. Check what's wrong
bash scripts/evdi-diagnose.sh --verbose

# 2. View recommendations
bash scripts/evdi-diagnose.sh --fix

# 3. Try repair (if needed)
sudo bash scripts/evdi-setup.sh

# 4. Verify fix
bash scripts/evdi-diagnose.sh
```

### For CI/CD or Batch Deployment

```bash
# Install without interactive prompts
sudo bash scripts/evdi-setup.sh --skip-diagnostic

# Verify installation
bash scripts/evdi-diagnose.sh --verbose || exit 1

# Your test/build commands here
```

---

## Detailed Installation Steps

### Step 1: Initial Diagnosis (No Root Required)

```bash
cd ~/git/WBeam
bash scripts/evdi-diagnose.sh
```

This tells you what's missing.

### Step 2: Automated Installation (Requires Root)

```bash
sudo bash scripts/evdi-setup.sh
```

Script will:
- Ask for sudo password
- Install dependencies
- Compile EVDI module
- Load module
- Configure permissions
- Create persistent configuration

### Step 3: Verification (No Root Required)

```bash
bash scripts/evdi-diagnose.sh --verbose
```

Should show: **✅ All checks passed! EVDI is ready to use.**

### Step 4: Optional - Set as Default

```bash
# Edit config to use EVDI by default
echo "" >> ~/.config/wbeam/wbeam.conf
echo "[streamer]" >> ~/.config/wbeam/wbeam.conf
echo "WBEAM_CAPTURE_BACKEND=evdi" >> ~/.config/wbeam/wbeam.conf

# Verify
grep "WBEAM_CAPTURE_BACKEND" ~/.config/wbeam/wbeam.conf
```

---

## Troubleshooting Common Issues

### "Module not loading"

```bash
# Diagnose
bash scripts/evdi-diagnose.sh --verbose

# If it shows "EVDI module not loaded":
# 1. Check DKMS status
dkms status | grep evdi

# 2. If not built, try:
sudo bash scripts/evdi-setup.sh

# 3. If still fails, check kernel version
uname -r
```

### "Permission denied"

```bash
# Check user group
groups | grep video

# If not present, add:
sudo usermod -a -G video $(whoami)

# Apply changes (one of):
# Option 1: Log out and back in
# Option 2: newgrp video
```

### "Device not created"

```bash
# Check if module is loaded
lsmod | grep evdi

# If loaded but device missing:
sudo udevadm trigger
ls -la /dev/dri/card0

# If still missing:
dmesg | tail -20 | grep -i evdi
```

### "Library not found"

```bash
# Check library location
find /usr -name "libevdi.so*" 2>/dev/null

# If not found:
sudo bash scripts/evdi-setup.sh

# Update library cache:
sudo ldconfig
```

---

## Advanced Usage

### Dry Run (Preview Changes)

See exactly what would be installed without making changes:

```bash
sudo bash scripts/evdi-setup.sh --dry-run
```

Output will show:
- Which packages would be installed
- Which commands would be executed
- Which files would be created
- Changes to user groups

### Custom Device Count

By default, 1 EVDI device is created. To change:

```bash
# Before running setup:
# Edit /etc/modprobe.d/evdi.conf after setup, or:

# At runtime:
sudo modprobe -r evdi
sudo modprobe evdi initial_device_count=4  # Create 4 devices
```

### Integration with Package Management

For integration into automation tools:

```bash
#!/bin/bash
set -e

# Install
sudo bash scripts/evdi-setup.sh --skip-diagnostic

# Verify
bash scripts/evdi-diagnose.sh --verbose || {
    echo "EVDI setup failed"
    exit 1
}

echo "✅ EVDI installation successful"
```

---

## Error Codes

| Code | Meaning | Action |
|------|---------|--------|
| 0 | Success | EVDI ready |
| 1 | DKMS not available | Install DKMS |
| 2 | Module compilation failed | Check kernel version |
| 3 | Device not created | Check kernel logs |
| 4 | Permissions issue | Add to video group |
| 5+ | Other errors | Check script output |

---

## Platform-Specific Notes

### Arch Linux

- EVDI in official repos, or from AUR (yay -S evdi-dkms)
- Usually works out-of-box after running setup script
- Check: `pacman -Q evdi-dkms`

### Debian/Ubuntu

- EVDI usually in universe/multiverse
- May require universe repo: `sudo add-apt-repository universe`
- Check: `dpkg -l | grep evdi-dkms`

### Fedora

- EVDI in COPR repository (displaylink-rpm project)
- Script handles this automatically
- Check: `dnf copr list | grep displaylink`

### RHEL/CentOS

- Similar to Fedora (COPR)
- May need `epel-release` enabled first
- Check: `yum info evdi-dkms`

---

## Getting Help

### Check What's Installed

```bash
# Check OS/distro
cat /etc/os-release

# Check package manager
which pacman apt dnf yum

# Check kernel
uname -r

# Check EVDI packages
pacman -Q evdi-dkms 2>/dev/null || \
dpkg -l | grep evdi-dkms || \
rpm -q evdi-dkms

# Check DKMS status
dkms status | grep evdi
```

### Gather Debug Info for Support

```bash
bash scripts/evdi-diagnose.sh --verbose > /tmp/evdi-diag.txt 2>&1
dkms status | grep evdi >> /tmp/evdi-diag.txt
uname -a >> /tmp/evdi-diag.txt
cat /etc/os-release >> /tmp/evdi-diag.txt
dmesg | tail -50 | grep -i evdi >> /tmp/evdi-diag.txt

# Share /tmp/evdi-diag.txt in bug reports
```

### When Reporting Issues

Include:
1. Output of `bash scripts/evdi-diagnose.sh --verbose`
2. Your OS/distro information
3. Exact error message you're seeing
4. Steps you've already tried

---

## FAQ

**Q: Do I need to run setup again after kernel updates?**  
A: Usually no. DKMS automatically recompiles for new kernels.

**Q: Can I use both EVDI and Wayland portal?**  
A: Yes, specify which one with `?capture_backend=` parameter.

**Q: Does EVDI work on virtual machines?**  
A: Depends on VM passthrough support. Usually not.

**Q: Will setup work on WSL (Windows Subsystem for Linux)?**  
A: No, WSL doesn't support kernel modules.

**Q: How do I uninstall EVDI?**  
```bash
sudo dkms remove -m evdi --all
sudo pacman -R evdi-dkms  # or apt remove, dnf remove, etc.
```

---

## Contributing Improvements

These scripts are designed to be:
- **Reliable:** Error checking at every step
- **Portable:** Work across multiple distros
- **Safe:** Reversible, with dry-run capability
- **User-friendly:** Clear messages and recommendations

If you find issues or have improvements:
1. Test thoroughly before submitting
2. Include distro/kernel version
3. Provide clear reproduction steps

---

## License

These scripts are part of WBeam and follow the same license.

---

## Quick Reference

```bash
# Diagnose system
bash scripts/evdi-diagnose.sh

# Install EVDI
sudo bash scripts/evdi-setup.sh

# Verify installation
bash scripts/evdi-diagnose.sh --verbose

# Fix issues
bash scripts/evdi-diagnose.sh --fix

# See what would happen
sudo bash scripts/evdi-setup.sh --dry-run

# Use EVDI
./wbeam host run
curl -X POST "http://localhost:5001/v1/start?capture_backend=evdi"
```

---

**Last Updated:** 2026-03-25  
**Status:** Production Ready ✅
