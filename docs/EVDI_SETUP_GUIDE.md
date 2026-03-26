# EVDI Capture Backend - Setup Guide

## Quick Start

```bash
cd ~/git/WBeam
sudo bash scripts/evdi-setup.sh    # install (requires sudo)
bash scripts/evdi-diagnose.sh      # verify
./wbeam host run                   # start daemon
curl -X POST "http://localhost:5001/v1/start?capture_backend=evdi"
```

## Supported Distributions

| Distribution | Package Manager | Notes |
|---|---|---|
| Arch Linux | pacman | Official repos or AUR (`yay -S evdi-dkms`) |
| Debian 11+ / Ubuntu 20.04+ | apt | May require universe repo |
| Fedora 38+ | dnf | Via COPR (`displaylink-rpm/displaylink`) |
| RHEL 8+ / CentOS 8+ | yum | COPR or source build; may need `epel-release` |

Requirements: x86_64, kernel 4.14+, sudo access, ~500 MB disk.

## Automated Setup

```bash
sudo bash scripts/evdi-setup.sh                # standard install
sudo bash scripts/evdi-setup.sh --dry-run       # preview only
sudo bash scripts/evdi-setup.sh --skip-diagnostic  # headless/CI
```

The script detects your distro, installs kernel headers and build tools,
installs EVDI via DKMS, loads the module, creates device nodes, configures
persistent loading, sets user group permissions, and writes udev rules.

Exit 0 on success; non-zero on error.

## Manual Setup

### Arch

```bash
sudo pacman -Sy
sudo pacman -S --noconfirm linux-headers base-devel dkms
yay -S evdi-dkms          # or: git clone https://aur.archlinux.org/evdi-dkms.git && cd evdi-dkms && makepkg -si
```

### Debian / Ubuntu

```bash
sudo apt-get update
sudo apt-get install -y linux-headers-$(uname -r) build-essential dkms evdi-dkms
```

### Fedora

```bash
sudo dnf check-update
sudo dnf install -y kernel-devel gcc make dkms
sudo dnf copr enable displaylink-rpm/displaylink
sudo dnf install -y evdi-dkms
```

### RHEL / CentOS

```bash
sudo yum check-update
sudo yum install -y kernel-devel gcc make dkms
sudo yum copr enable displaylink-rpm/displaylink
sudo yum install -y evdi-dkms
```

If COPR is unavailable, build from source:

```bash
git clone https://github.com/displaylink-rpm/evdi.git && cd evdi
sudo dkms add . && sudo dkms build -m evdi -v $(cat VERSION.txt) && sudo dkms install -m evdi -v $(cat VERSION.txt)
```

### Load Module and Persist

```bash
sudo modprobe -r evdi 2>/dev/null || true
sudo modprobe evdi initial_device_count=1
echo "options evdi initial_device_count=1" | sudo tee /etc/modprobe.d/evdi.conf
```

### User Permissions

```bash
sudo usermod -a -G video $(whoami)
newgrp video    # or log out and back in
```

## Verification

```bash
bash scripts/evdi-diagnose.sh              # quick
bash scripts/evdi-diagnose.sh --verbose    # detailed
bash scripts/evdi-diagnose.sh --fix        # with fix recommendations
```

Manual checks:

```bash
lsmod | grep evdi                          # module loaded
ls -la /dev/dri/card0                      # device exists (crw-rw---- root video)
groups | grep video                        # user in video group
```

## Troubleshooting

### Module not loading

```
modprobe: ERROR: could not insert 'evdi': No such file or directory
```

1. Check DKMS: `dkms status` -- should show `evdi` as built/installed.
2. If only "added", rebuild:
   ```bash
   EVDI_VERSION=$(ls /usr/src/ | grep evdi | head -1 | sed 's/evdi-//')
   sudo dkms build -m evdi -v $EVDI_VERSION && sudo dkms install -m evdi -v $EVDI_VERSION
   ```
3. Build failure -- verify headers match running kernel:
   `uname -r` vs `dpkg -l | grep linux-headers-$(uname -r)` (Debian) or `rpm -q kernel-devel-$(uname -r)` (RPM).
4. Secure Boot may block unsigned modules. Disable it or sign the module.

### Device not created

```bash
lsmod | grep evdi                    # confirm module loaded
cat /sys/module/evdi/parameters/initial_device_count   # should be 1
sudo modprobe evdi initial_device_count=1              # reload if needed
dmesg | tail -50 | grep -i evdi                        # check for errors
sudo udevadm trigger                                   # force device creation
```

### Permission denied

```bash
ls -la /dev/dri/card0        # should show group "video" with rw
groups | grep video           # user must be in video group
sudo usermod -a -G video $(whoami) && newgrp video
cat /etc/udev/rules.d/50-evdi.rules   # verify udev rules exist
```

### libevdi not found

```bash
find /usr -name "libevdi.so*" 2>/dev/null   # locate library
sudo ldconfig                                # refresh cache
```

If missing, reinstall the EVDI package for your distro.

### Stream starts but no video

1. Verify parameter name is `capture_backend` (not `backend`).
2. Check logs: `tail -100 logs/*.host*.log | grep -i error`
3. Headless host may need a virtual display:
   ```bash
   sudo Xvfb :99 -screen 0 1920x1080x24 &
   export DISPLAY=:99
   ```

## API Usage

```bash
# Start stream
curl -X POST "http://localhost:5001/v1/start?capture_backend=evdi"

# With encoder options
curl -X POST "http://localhost:5001/v1/start?capture_backend=evdi" \
  -H "Content-Type: application/json" \
  -d '{"encoder":"h264","profile":"adaptive","bitrate_kbps":20000}'

# Check status
curl -s http://localhost:5001/v1/health | jq '.state, .effective_runtime_config.capture_backend'
```

Response (example):

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

## Advanced Configuration

Set EVDI as default backend in `~/.config/wbeam/wbeam.conf`:

```ini
[streamer]
WBEAM_CAPTURE_BACKEND=evdi
```

Multiple virtual devices:

```bash
echo "options evdi initial_device_count=4" | sudo tee /etc/modprobe.d/evdi.conf
```

Debug logging:

```bash
RUST_LOG=debug ./wbeam host debug 2>&1 | grep -i evdi
```

## FAQ

**Does EVDI work with Wayland?**
Yes. It captures at the kernel level, bypassing the display server.

**Does it use more CPU?**
No. Direct kernel capture uses less CPU than portal/X11 methods.

**Can I switch between EVDI and other backends?**
Yes, dynamically via the `capture_backend` query parameter, without restarting the daemon.

**Does EVDI survive sleep/suspend?**
The module usually reloads on resume; the daemon may need a restart.

**What resolution does EVDI support?**
Fixed at EDID 1920x1080. The downstream pipeline scales to the requested size.

**Do I need to re-run setup after kernel updates?**
Usually not. DKMS recompiles automatically for new kernels.

**Does EVDI work in VMs or WSL?**
Generally no. VMs lack the required passthrough; WSL does not support kernel modules.

**How do I uninstall EVDI?**
`sudo dkms remove -m evdi --all` then remove the package via your distro's package manager.
