# EVDI capture

EVDI exposes a virtual DRM display that the compositor renders into. WBeam reads
its framebuffer through libevdi and feeds the encoder directly, without the
ScreenCast portal/PipeWire capture path. A compositor still has to render to the
virtual display; a loaded module alone is insufficient.

The streamer generates an EDID from the requested size and frame rate, with an
even-sized landscape capture mode. It is not fixed at 1920×1080. The compositor,
encoder, USB link and Android decoder determine the effective result; latency
and CPU use need measurement on the target hardware.

## Installation

Run commands from the repository root. For a source checkout:

```bash
./install-wbeam --backend evdi
```

On Fedora 43, the dependency stage uses the Fedora helper, which can also be run
separately:

```bash
scripts/fedora-setup.sh --yes --with-evdi
```

See [Fedora setup](FEDORA_43_SETUP.md) for its package discovery, kernel-header
and Secure Boot flow. On Debian/Ubuntu, the wizard installs native host libraries
and EVDI/DKMS packages; the remaining source toolchain must be prepared as
described in the [installer guide](INSTALLER_WIZARD.md).

Fedora 43 and Ubuntu 24.04 are release acceptance targets, not a guarantee for
all kernels or drivers. Other distributions need their own compatible EVDI
packages and validation. Track tested combinations in the
[compatibility matrix](EVDI_COMPATIBILITY_MATRIX.md).

The older `scripts/evdi-setup.sh` remains available for legacy maintenance.
It uses limited device/library checks and is not the primary readiness test or
a substitute for distro-specific package instructions. See
[EVDI tools](../scripts/EVDI_TOOLS_README.md).

## Build requirements and runtime requirements

| Layer | Required evidence |
| --- | --- |
| Build a kernel module | Driver compatible with the running kernel, matching kernel headers and a working DKMS/akmod build. |
| Build the EVDI streamer | GStreamer development libraries, the Cargo `evdi` feature and linkable `libevdi.so`. |
| Run an installed streamer | Loaded EVDI module, usable EVDI DRM device, user access and the library ABI required by that binary. Kernel headers and an unversioned development symlink are not runtime requirements. |
| Display real frames | Active graphical session/compositor, encoder, ADB transport and Android decoder all working together. |

Debian Bookworm and Ubuntu Noble packages use different libevdi ABIs. Install the
package built for your distribution; do not redirect one SONAME to another.

`./wbeam host build` enables EVDI when its library probe finds it. To build
explicitly against a custom library directory:

```bash
WBEAM_EVDI_LIB_DIR=/path/to/libevdi-dir \
  cargo build --release --manifest-path host/rust/Cargo.toml \
  -p wbeamd-streamer --no-default-features --features evdi
```

That directory must contain `libevdi.so`. The build script adds it to the linker
search path and runtime rpath. The CLI's automatic feature probe is separate;
setting this variable alone does not guarantee `./wbeam host build` selects the
EVDI feature.

## Diagnostics

```bash
bash scripts/virtual-deps-check.sh --json --backend evdi
bash scripts/evdi-diagnose.sh --verbose --fix
```

The first command performs a preliminary runtime dependency check and returns a
nonzero exit status when `ok` is false. The detailed diagnostic also checks
build/linker concerns; `--fix` **prints repair suggestions**. Neither command
proves that the compositor is producing frames or that the Android device can
display them.

| Failure | Next check |
| --- | --- |
| Module missing | Compare `uname -r`, `dkms status` and the running kernel's headers. Inspect the matching DKMS `make.log`. |
| Module built but rejected | Inspect `modinfo evdi`, `journalctl -k` and Secure Boot/MOK state. Enroll the module's signing key and reboot if required. |
| `cannot find -levdi` | Install the appropriate development/DisplayLink package or use the explicit custom-library build above. This is independent of DKMS. |
| Runtime library missing | Use `ldd` on the actual streamer binary to verify its required SONAME resolves. The generic dependency probe only checks library presence. |
| Device or permission failure | Check the EVDI DRM devices under `/sys/devices/platform/evdi*/drm/` and `/dev/dri/`, then the active user's access/group membership. Do not assume `card0` is EVDI. |
| Stream starts without frames | Check effective backend/codec, compositor display configuration, daemon/streamer logs and Android telemetry. A running process is not frame acceptance. |

When the module is installed but not loaded:

```bash
sudo modprobe evdi initial_device_count=4
```

Changing module parameters requires a new load and may affect existing virtual
displays. Do not unload an in-use module as a routine diagnostic. The supplied
helpers use four initial devices; inspect the running state instead of assuming
that count applied to an already-loaded module.

After a group membership change, log out and back in. After a kernel update,
verify the module rebuilt, loaded and retained its signing trust before relying
on EVDI again.

If EVDI is unavailable, select **Wayland Portal** on a compatible Wayland
session. X11 capture and XRandR helpers are retained as legacy code; the current
normal start path does not select them automatically. Starting Xvfb is not a
repair for an EVDI/Portal capture failure.

## Select and inspect capture

For the first stream, select EVDI in the desktop UI after onboarding the Android
device. For API diagnostics on a single/default session with transport already
prepared:

```bash
curl -fsS -X POST 'http://127.0.0.1:5001/v1/start?capture_backend=evdi' \
  -H 'Content-Type: application/json' \
  -d '{"encoder":"h264","profile":"default","size":"1920x1080","fps":60,"bitrate_kbps":20000}'
curl -fsS 'http://127.0.0.1:5001/v1/status'
curl -fsS -X POST 'http://127.0.0.1:5001/v1/stop'
```

Use the same `serial` query parameter on start/status/stop when addressing a
particular device. `capture_backend` is a query parameter, while encoder/size/
frame-rate settings are JSON fields. Inspect `effective_runtime_config` after
startup; a start response alone is not proof of delivered frames.

For a persistent default, set `WBEAM_CAPTURE_BACKEND=evdi` in
`${XDG_CONFIG_HOME:-$HOME/.config}/wbeam/wbeam.conf`, then restart the daemon.
Process environment overrides the file; a per-start API/UI selection overrides
the daemon default. Stop and start the session to switch backends.

Record a real image-stream test and soak results using the
[release checklist](release-checklist.md) before declaring a configuration
supported.
