# EVDI Capture Backend - Complete Setup & Troubleshooting Index

## For Users Who Need EVDI Working Now

### Quick Start (5 minutes)
```bash
cd ~/git/WBeam
sudo bash scripts/evdi-setup.sh
./wbeam host run
```

Then in another terminal:
```bash
curl -X POST "http://localhost:5001/v1/start?capture_backend=evdi"
```

---

## Documentation Roadmap

### 1. **First Time Setup?**
   → Read: `docs/EVDI_SETUP_GUIDE.md` (Section: "Quick Start" + "Automated Setup")
   → Run: `sudo bash scripts/evdi-setup.sh`
   
### 2. **Having Trouble?**
   → Run: `bash scripts/evdi-diagnose.sh --verbose`
   → Check: `bash scripts/evdi-diagnose.sh --fix`
   → Read: `docs/EVDI_SETUP_GUIDE.md` (Section: "Troubleshooting")

### 3. **Want to Do It Manually?**
   → Read: `docs/EVDI_SETUP_GUIDE.md` (Section: "Manual Setup")
   → Follow step-by-step instructions for your distro

### 4. **CI/CD Integration?**
   → Read: `scripts/EVDI_TOOLS_README.md` (Section: "CI/CD Integration")
   → Use: `sudo bash scripts/evdi-setup.sh --skip-diagnostic`

### 5. **Understanding EVDI?**
   → Read: `docs/EVDI_SETUP_GUIDE.md` (Section: "Why EVDI is Better")
   → Read: `docs/EVDI_SETUP_GUIDE.md` (Section: "How It Works")

---

## Available Tools

| Tool | Purpose | Root Required? | Time |
|------|---------|---|---|
| `scripts/evdi-diagnose.sh` | Check what's wrong | ❌ No | 30 sec |
| `scripts/evdi-setup.sh` | Install EVDI | ✅ Yes (sudo) | 15-20 min |
| `docs/EVDI_SETUP_GUIDE.md` | Reference guide | ❌ No | Self-paced |
| `scripts/EVDI_TOOLS_README.md` | Tool documentation | ❌ No | 5-10 min |

---

## Common Scenarios

### "EVDI doesn't work"
1. Run: `bash scripts/evdi-diagnose.sh`
2. See error? → Run: `bash scripts/evdi-diagnose.sh --fix`
3. Follow recommendations or try: `sudo bash scripts/evdi-setup.sh`

### "I just installed but it's not working"
1. Check: `bash scripts/evdi-diagnose.sh --verbose`
2. Most likely: User needs to log out and back in (group changes)
3. Quick fix: `newgrp video`

### "Module loading fails"
1. Run: `bash scripts/evdi-diagnose.sh --verbose`
2. Look for: DKMS build status
3. Check: `dkms status`
4. Fix: `sudo bash scripts/evdi-setup.sh`

### "Permission denied"
1. Check: `groups | grep video`
2. If not shown: `sudo usermod -a -G video $(whoami)`
3. Apply: `newgrp video` or log out/in

### "Device not created"
1. Check: `lsmod | grep evdi`
2. If loaded: `sudo udevadm trigger`
3. Check: `ls -la /dev/dri/card0`
4. If still missing: Check kernel version compatibility

---

## File Organization

```
WBeam/
├── scripts/
│   ├── evdi-diagnose.sh           ← Run this first
│   ├── evdi-setup.sh              ← Run this to install
│   └── EVDI_TOOLS_README.md       ← Tool documentation
│
├── docs/
│   └── EVDI_SETUP_GUIDE.md        ← Complete reference
│
└── EVDI_SETUP_INDEX.md            ← You are here
```

---

## Supported Distros

✅ **Fully Supported:**
- Arch Linux (pacman)
- Debian 11+ (apt)
- Ubuntu 20.04+ (apt)
- Fedora 38+ (dnf)
- RHEL 8+ (yum)
- CentOS 8+ (yum)

Scripts auto-detect your distro and use the right package manager.

---

## What Gets Installed

`sudo bash scripts/evdi-setup.sh` will:
1. ✅ Install kernel headers
2. ✅ Install build tools (gcc, make)
3. ✅ Install DKMS
4. ✅ Install EVDI DKMS package
5. ✅ Compile EVDI module
6. ✅ Load module with device
7. ✅ Create persistent configuration
8. ✅ Set user permissions
9. ✅ Configure udev rules

All changes survive reboots.

---

## Next Steps After Setup

### 1. Verify Installation
```bash
bash scripts/evdi-diagnose.sh --verbose
# Should show: ✅ All checks passed!
```

### 2. Set as Default (Optional)
```bash
echo "[streamer]" >> ~/.config/wbeam/wbeam.conf
echo "WBEAM_CAPTURE_BACKEND=evdi" >> ~/.config/wbeam/wbeam.conf
```

### 3. Start Using
```bash
./wbeam host run
# Terminal 2:
curl -X POST "http://localhost:5001/v1/start?capture_backend=evdi"
```

---

## Troubleshooting Quick Links

See full troubleshooting in `docs/EVDI_SETUP_GUIDE.md`:

- **Module not loading** → Section: "Troubleshooting" → "Module not loading after installation"
- **Permission denied** → Section: "Troubleshooting" → "Permission denied accessing device"
- **Device not created** → Section: "Troubleshooting" → "Device not created"
- **libevdi not found** → Section: "Troubleshooting" → "libevdi not found"
- **Stream not working** → Section: "Troubleshooting" → "Stream starts but no video"

---

## If Something Goes Wrong

### Step 1: Gather Information
```bash
bash scripts/evdi-diagnose.sh --verbose > /tmp/evdi-diag.txt
dkms status >> /tmp/evdi-diag.txt
uname -a >> /tmp/evdi-diag.txt
cat /etc/os-release >> /tmp/evdi-diag.txt
```

### Step 2: Check the Logs
```bash
# Look at /tmp/evdi-diag.txt
# Check specific sections for error messages
```

### Step 3: Get Help
Include `/tmp/evdi-diag.txt` when asking for help. It contains:
- ✅ What's installed
- ✅ What's not working
- ✅ Exact error messages
- ✅ System configuration

---

## API Usage Examples

Once EVDI is set up, use it with:

```bash
# Start stream with EVDI
curl -X POST "http://localhost:5001/v1/start?capture_backend=evdi"

# Check if using EVDI
curl -s http://localhost:5001/v1/health | grep capture_backend

# Switch to different backend
curl -X POST "http://localhost:5001/v1/start?capture_backend=wayland_portal"
```

---

## Performance Notes

EVDI provides:
- ✅ Lower latency (direct kernel capture)
- ✅ Better quality (bypasses compositor)
- ✅ Higher refresh rates (not capped at 60 Hz)
- ✅ Lower CPU usage (no portal overhead)

See `docs/EVDI_SETUP_GUIDE.md` for technical details.

---

## Advanced Users

- **Custom device count**: Edit `/etc/modprobe.d/evdi.conf`
- **Debugging**: `RUST_LOG=debug ./wbeam host run`
- **Source build**: Clone from GitHub displaylink-rpm/evdi

See `docs/EVDI_SETUP_GUIDE.md` (Section: "Advanced Configuration")

---

## Support Matrix

| Issue | Severity | Solution Time |
|-------|----------|---|
| Need to install EVDI | High | 15-20 min (automated) |
| Module not loading | High | 5-10 min (diagnostics + fix) |
| Permission issues | Medium | 2-3 min (one command) |
| Device not created | High | 5 min (reload udev) |
| Distro not supported | Low | Manual compile (30+ min) |

---

## Summary

1. **For New Installation:** 
   - `sudo bash scripts/evdi-setup.sh`
   
2. **For Troubleshooting:**
   - `bash scripts/evdi-diagnose.sh --fix`
   
3. **For Learning:**
   - `docs/EVDI_SETUP_GUIDE.md`
   
4. **For Integration:**
   - `scripts/EVDI_TOOLS_README.md`

---

**Last Updated:** 2026-03-25  
**Status:** Production Ready ✅  
**Supports:** Arch, Debian, Ubuntu, Fedora, RHEL, CentOS
