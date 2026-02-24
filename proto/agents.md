# Proto — Agent Context

## What This Is

WBeam is a **USB second-screen project** for Android. The goal is a SuperDisplay-like experience where an Android tablet/phone becomes a wired extended display for an Ubuntu KDE Wayland desktop — low latency, stable 60 fps, no wireless jitter.

**How it compares to a regular screen-share or casting app:**

| Regular cast/mirror app | WBeam proto |
|---|---|
| Wi-Fi only, high latency (~100–300 ms) | USB only, sub-20 ms target |
| OS screen recorder API (rate-limited) | Direct Wayland XDG Desktop Portal capture |
| Software H264 decode in Java | Hardware-accelerated decode via libturbojpeg + ANativeWindow |
| Re-establishes on Wi-Fi drop | ADB tunnel — stable over USB, auto-reconnect |
| No host-side control | Rust daemon on host manages capture, encode, pacing, health |
| Vendor-locked (Miracast, Chromecast) | Open protocol, framed JPEG (WBJ1) or MJPEG over TCP |

The Android app is not a viewer — it is a **rendering terminal**. It hands the surface directly to native code, does hardware JPEG decode via libturbojpeg (NDK), and blits to an `ANativeWindow` with no Bitmap copy. The host controls all timing and frame pacing.

---

## Architecture

```
Ubuntu KDE Wayland
  └─ XDG Desktop Portal (screencast)
       └─ /dev/shm/proto-portal-frames/   ← debug JPEG frames written here
            └─ Rust daemon  proto-host-image
                 ├─ captures latest frame
                 ├─ encodes to JPEG
                 └─ pushes over TCP → 127.0.0.1:5006
                                           │
                              adb forward tcp:5006 tcp:5006
                                           │
                                    Android device :5006
                                      └─ MainActivity.java
                                           ├─ TCP server (ADB push listener)
                                           ├─ frame queue
                                           └─ libwbeam.so (NDK)
                                                └─ libturbojpeg.so
                                                     └─ ANativeWindow render
```

Key env vars that wire everything together:
- `PROTO_ADB_PUSH=1` — use ADB push transport (host connects to device)
- `PROTO_ADB_PUSH_ADDR=127.0.0.1:5006` — host endpoint (tunneled by adb forward)
- `PROTO_PORTAL_JPEG_SOURCE=debug` — read JPEG frames from `/dev/shm/proto-portal-frames/`
- `PROTO_REQUIRE_TURBO=0` — allow emulator even if turbojpeg stub detected
- `SERIAL` / `PROTO_SERIAL` — target ADB device serial

---

## What Has Been Done

### Environment / Tooling
- Confirmed Gradle wrapper already pinned to `gradle-9.0-milestone-1` (compatible with Java 21)
- Created `WBeam_Tablet` AVD — API 36.1, x86_64
- Created `WBeam_Tablet_API17` AVD — API 17, x86, Google APIs (for legacy test machine)
- Installed `system-images;android-17;google_apis;x86` + `platforms;android-17`
- Lowered `minSdk` default from 19 → 17 in `android/app/build.gradle`
- Set up 3 VS Code launch profiles (`.vscode/launch.json`): emulator, connected device, attach

### Build — x86 Native Libs
- Added `x86` to `abiFilters` in `proto/front/app/build.gradle`
- Added `build_one "x86"` to `proto/front/scripts/build_turbojpeg_android.sh`
- Fixed duplicate `libturbojpeg.so` merge conflict with `packagingOptions { pickFirst }`
- Deleted stale `.cxx/` CMake configure cache so x86 turbojpeg was properly detected
- All 6 native SOs now in APK: `lib/{arm64-v8a,armeabi-v7a,x86}/{libturbojpeg,libwbeam}.so`

### Protocol / Transport Fixes
- `PROTO_PORTAL_JPEG_SOURCE=file` was broken (ffmpeg H264 chain failing) — switched default to `debug` (reads JPEGs from `/dev/shm/proto-portal-frames/`)
- Set `PROTO_REQUIRE_TURBO=0` for emulator runs so host doesn't self-terminate
- Added `PROTO_SERIAL` passthrough to host binary in `run.sh` (both `cargo run` and direct binary paths)
- Added device picker to `proto/rr` — auto-selects if 1 ADB device, numbered menu if multiple, respects pre-set `$SERIAL`

### New Features Added
- **Android log poller** — Rust thread in `main.rs` runs every 5 s: `adb logcat -d` → appends to `proto/logs/android-TIMESTAMP.log` → clears buffer. Constants: `ANDROID_LOG_POLL_SECS`, `ANDROID_LOG_DIR`.
- **Preflight splash screen** — full-screen dark overlay in `activity_main.xml`. `runPreflightThenStream()` runs 4 sequential checks before streaming: native lib loaded, turbojpeg available, transport mode, host HTTP `/health` reachable. Fades out (350 ms alpha animation) then calls `startStreaming()`.

### Cleanup Fix (latest)
- Identified that `cargo run --release &` makes `cargo` the tracked `$HOST_PID` but `proto-host-image` is a **grandchild** — Ctrl+C killed `cargo` but left the binary running stale (without a valid `adb forward`)
- Fixed `cleanup()` in `run.sh` to also `pkill -f proto-host-image`
- Added `pkill -f proto-host-image` at the **top** of `run.sh` to kill any survivor from a previous session before setting up ADB forwarding

---

## Current Problem — Black Screen / rx=0

**Symptom:** App launches, splash shows all preflight checks OK, then black screen. Logcat shows `rx=0 latestIdx=-1` throughout — the app is running but never receives a frame.

**What was confirmed working:**
- `adb forward tcp:5006 tcp:5006` in list ✓
- `ss -tlnp | grep 5006` → adb listening on `127.0.0.1:5006` ✓
- `nc -q1 127.0.0.1 5006` → TCP tunnel passes data ✓
- App logcat: `ADB PUSH listening on port 5006` ✓
- Host env: `PROTO_ADB_PUSH=1`, `PROTO_ADB_PUSH_ADDR=127.0.0.1:5006` ✓
- Host PID 50184 running, 9 threads active ✓

**Root cause identified:**
The stale `proto-host-image` (pid 50184) had been running for a long time **without** a valid `adb forward`. Its `start_adb_push_sender` thread accumulated connect failures and hit max backoff (2 s sleep), or may have silently panicked. After the forward was manually restored, the stale host was not making new TCP connections (`ss -tnp | grep 5006` showed nothing), so `rx` stayed 0.

**Fix applied (needs fresh run to verify):**
- `pkill -f proto-host-image` added at startup of `run.sh` — stale process no longer persists
- `cleanup()` now kills both `cargo` wrapper and the actual binary on exit
- Kill stale process manually, then run `./start-fast.sh` fresh → host starts clean with `adb forward` already set

### Investigation into tt/9 snapshot (Feb 23 2026)

Deep-dive was run on `tt/9` (a snapshot containing H264 + latency optimizations). Three root causes were identified:

#### Fix 1 — HOST_TS_STALE_MS clock drift (APPLIED to current proto)
The `HOST_TS_STALE_MS` check compared host wall-clock (`SystemTime::now()` in Rust) against device wall-clock (`System.currentTimeMillis()` in Java). On an emulator the clocks are not NTP-synced — even a 100 ms skew causes **every single frame** to be classified as stale and dropped before reaching the decode queue. The check was changed to log-only (non-dropping).

#### Fix 2 — H264 NAL Unit Dropping via "latest-only" ring buffer (FUTURE — not in current code)
The 3-slot ring buffer (`slotData` / `atomicLatest`) is designed for stateless JPEG frames and drops older slots for latency. H264 SPS + PPS + I-frame arrive in rapid succession and the decoder only sees the last slot — missing SPS/PPS → `MediaCodec` cannot init → permanent black screen. Fix: use a `LinkedBlockingQueue` for H264 payloads to bypass the drop policy.

#### Fix 3 — MediaCodec input buffer size (FUTURE — not in current code)
`MediaFormat.createVideoFormat("video/avc", 960, 540)` allocates buffers too small for 720p I-frames from the host. Fix: allocate at `1920x1080` and let the hardware scaler downsample. Not applicable until H264 path is added.

---

## Files of Interest

| File | Role |
|---|---|
| `proto/rr` | Top-level launcher (device picker → presets → `run.sh`) |
| `proto/run.sh` | Core orchestration: build APK, install, adb forward, start host + app |
| `proto/start-fast.sh` | Thin wrapper that calls `run.sh` with fast preset |
| `proto/host/src/main.rs` | Rust host daemon: capture, encode, ADB push sender, log poller |
| `proto/front/app/src/main/java/com/proto/demo/MainActivity.java` | Android app: TCP server, frame queue, preflight splash, render |
| `proto/front/app/src/main/cpp/native_wbeam.cpp` | NDK JNI bridge: libturbojpeg decode → ANativeWindow |
| `proto/front/app/src/main/res/layout/activity_main.xml` | UI layout (SurfaceView + splash overlay) |
| `proto/front/app/build.gradle` | Android build config (abiFilters, packagingOptions) |
| `proto/front/scripts/build_turbojpeg_android.sh` | Builds libturbojpeg.so for armeabi-v7a, arm64-v8a, x86 |
| `proto/logs/` | Runtime logs: `android-TIMESTAMP.log` from logcat poller |
