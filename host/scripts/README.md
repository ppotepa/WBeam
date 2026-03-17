# WBeam Host Utility Scripts

This directory contains standalone utility scripts for testing, debugging, and profiling the WBeam host environment.

## Scripts

### probe_host.py
**Purpose:** Cross-platform host fingerprint probe for WBeam diagnostics.

**Usage:**
```bash
./probe_host.py
```

**Output:** JSON describing:
- OS/kernel/session/desktop environment information
- Key tool availability (xrandr, ffmpeg, etc.)
- Display stack details (X11/Wayland on Linux, desktop session info on Windows)
- XRandR providers/outputs snapshot when available

**Use cases:**
- Troubleshooting display configuration issues
- Gathering system information for bug reports
- Verifying WBeam host requirements

---

### x11_virtual_smoke.py
**Purpose:** Automated smoke test for X11 virtual monitor functionality.

**Usage:**
```bash
./x11_virtual_smoke.py [OPTIONS]
```

**Options:**
- Connects to WBeam daemon API to test virtual monitor operations
- Validates monitor creation, configuration, and teardown

**Use cases:**
- CI/CD automated testing of virtual monitor features
- Regression testing after display stack changes
- Validating daemon API endpoints

---

### x11_testcard.py
**Purpose:** Simple X11 test card window for validating virtual-monitor capture regions.

**Usage:**
```bash
./x11_testcard.py [--x X] [--y Y] [--w WIDTH] [--h HEIGHT] [--title TITLE]
```

**Options:**
- `--x`: X position of window (default: 1920)
- `--y`: Y position of window (default: 0)
- `--w`: Width of window (default: 1200)
- `--h`: Height of window (default: 2000)
- `--title`: Window title (default: "WBeam X11 Testcard")

**Example:**
```bash
./x11_testcard.py --x 0 --y 0 --w 1920 --h 1080 --title "Test Card 1"
```

**Use cases:**
- Visual verification of capture regions
- Testing virtual monitor positioning
- Debugging window placement issues
- Validating multi-monitor setups

## Requirements

All scripts require Python 3.7 or later. Individual scripts may have additional dependencies:
- `probe_host.py`: No external dependencies (uses stdlib only)
- `x11_virtual_smoke.py`: No external dependencies (uses stdlib only)
- `x11_testcard.py`: Requires `tkinter` (typically pre-installed with Python)

## Integration

These scripts are intended for manual testing and debugging. They are not automatically integrated into the WBeam build or runtime workflows.
