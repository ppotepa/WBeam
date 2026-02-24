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

### Script-first start (recommended)

- `./start-fast.sh` — lowest latency, lower quality (default for weak machines).
- `./start-balanced.sh` — middle ground.
- `./start-quality.sh` — best quality, heavier.
- `./rr fast|balanced|quality` — same as above, one launcher with preset argument.

Current `fast` tuning: `960x540`, `12000 kbps`, `22 capture fps`, `24 mjpeg fps`.

These scripts set JAVA/SDK defaults, build/install app when device is connected, start host, and launch app.

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
