# Prototype: Host image server + Android client

 Host (Rust) at `proto/host`: captures desktop and serves frames over HTTP.
   - Endpoint: `GET /image` / `GET /image.jpg` on port 5005 (latest JPEG snapshot from memory).
   - Endpoint: `GET /mjpeg` for multipart MJPEG stream (legacy-browser friendly).
  - Health: `GET /health`.
  - Run: `cargo run --release` from `proto/host`.
 - Android client at `proto/front`: minimal app that fetches and shows the image from the host over tether.
  - Min SDK 17. Uses `HttpURLConnection` + `AsyncTask` to load `http://<host>:5005/image.jpg` continuously.
  - Default host prefilled: `192.168.42.170` (typical USB tether gateway).

## Usage

## Fresh Clone Setup (Arch Linux)

From repo root:

```bash
# Installs missing dependencies only (main + proto)
./install-deps
```

### Single launcher

- `./run.sh` — Python entrypoint (`proto/run.py`) uruchamiający cały flow.
- Optional custom config path: `./run.sh --config /path/to/proto.conf`.
- JSON config is also supported: `./run.sh --config /path/to/proto.json`.
- Zmiana backendu/frontu/presetu odbywa się wyłącznie przez config file (`.conf` lub `.json`):
  - `RUN_BACKEND=rust`
  - `RUN_DEVICE=adb`
  - `PROTO_ANDROID_BUILD_TYPE=debug|release`

ENV overrides są zablokowane (np. `PROTO_*=... ./run.sh` zakończy się błędem).

`run.sh` robi flow w tej kolejności: build APK -> install APK -> launch Android app -> start backend (`rust`).

Network note:
- `RUN_DEVICE=adb` expects Android USB tethering; host/device routing may differ by OS.

1. Start host server
   ```bash
   cd proto/host
   cargo run --release
   # listens on 0.0.0.0:5005
   ```

2. Build/Install APK (legacy-compatible)
   - Open `proto/front` in Android Studio or run `./gradlew :app:assembleDebug` (wrapper not included).
   - Install the debug APK on the device.

3. On the device (USB tether on, Wi‑Fi off for simplicity)
   - Launch the app; it starts receiving frame updates automatically in a continuous loop.

Notes:
- The Android project allows cleartext (network_security_config) for the host IP.
- If your tether IP differs, edit the text field before loading.
- Host desktop capture needs either `grim` (Wayland) or `import` from ImageMagick (X11).
- `PROTO_MJPEG_FPS` controls `/mjpeg` push rate (default 15).
- `PROTO_CAPTURE_SIZE` controls portal capture resolution (default `1280x720`).
- `PROTO_CAPTURE_BITRATE_KBPS` controls portal bitrate target (default `16000`).
