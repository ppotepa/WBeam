# proto_x11

Focused X11-only prototype lane for real virtual monitor extension.

Goal:
- validate true X11 extended desktop path (real RandR output/provider)
- keep flow independent from main desktop UI wiring
- iterate quickly on one backend and one APK identity (`com.wbeam.x11`)

Non-goals in this lane:
- Wayland virtual monitor
- `xrandr --setmonitor` fallback as production path
- multi-OS abstractions

## layout

- `proto_x11/host/` Rust CLI orchestrator for X11 proto flow
- `proto_x11/android/` scripts for building/deploying APK with package `com.wbeam.x11`
- `proto_x11/run` canonical convenience runner (with wrappers)

## host CLI

Run from repo root:

```bash
./proto_x11/run probe-host
./proto_x11/run doctor --serial <SERIAL> --stream-port 5002
./proto_x11/run start --serial <SERIAL> --stream-port 5002
./proto_x11/run stop --serial <SERIAL> --stream-port 5002
./proto_x11/run smoke --serial <SERIAL> --stream-port 5002
./proto_x11/run acceptance --serial <SERIAL> --stream-port 5002
```

Behavior:
- defaults to strict real-output mode (`monitor-object` fallback disabled)
- `start` requires `resolver=linux_x11_real_output`
- sink policy for real-output is non-NVIDIA only (Intel/AMD/modesetting)
- fails fast with explicit dependency hint when real output is not ready
- `acceptance` asserts topology changed (`xrandr --query`) after start and reverts on stop

Wrappers are also available:
- `proto_x11/probe-host.sh`, `proto_x11/doctor.sh`, `proto_x11/status.sh`
- `proto_x11/start.sh`, `proto_x11/stop.sh`, `proto_x11/smoke.sh`, `proto_x11/acceptance.sh`
- `proto_x11/deploy-and-start.sh` (single-device deploy + start)
- `proto_x11/android-build.sh`, `proto_x11/android-deploy.sh`
- `proto_x11/service-status.sh`, `proto_x11/service-restart.sh`, `proto_x11/logs.sh`

`deploy-and-start.sh` now writes a per-run log file under `logs/`:
- `YYYYMMDD-HHMMSS.proto-x11-deploy.<pid>.log`

Compatibility note:
- `proto_x11/run.sh` is kept as alias to `proto_x11/run`.

Policy file:
- `~/.config/wbeam/x11-virtual-policy.conf` (written by `proto_x11/run`)
- keys:
  - `ENABLE_SETMONITOR_FALLBACK=0|1`
  - `ALLOW_MONITOR_OBJECT=0|1`
  - `DISABLE_REAL_OUTPUT_BACKEND=0|1`
  - `REQUIRE_VIRTUAL_SOURCE_PROVIDER=0|1` (default in proto flow: `1`)
  - `BLOCK_PROVIDER_LINK_WHEN_NVIDIA_PRESENT=0|1` (default in proto flow: `1`)

## android x11 APK

Build/deploy separate app id:

```bash
./proto_x11/android/build_x11_apk.sh
./proto_x11/android/deploy_x11.sh
```

These scripts set:
- `WBEAM_ANDROID_PACKAGE=com.wbeam.x11`
- `WBEAM_ANDROID_APP_ID_SUFFIX=.x11`
- `WBEAM_ANDROID_APP_NAME="WBeam X11"`

## expected preflight for X11 real output

Before `start` succeeds:
- `evdi` module loaded (`lsmod | grep ^evdi`)
- X11 provider topology exposes virtual source + sink pairing
- `/v1/virtual/doctor` returns `ok=true` and `resolver=linux_x11_real_output`

If module is installed but not loaded:

```bash
sudo modprobe evdi initial_device_count=1
systemctl --user restart wbeam-daemon.service
```

`deploy-and-start.sh` tries to auto-load `evdi` by default (sudo prompt if needed).
Use `--no-auto-load-evdi` to disable this behavior.
