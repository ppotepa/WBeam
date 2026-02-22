# Prototype: Host image server + Android client

- Host (Rust) at `proto/host`: captures desktop and serves a JPEG frame over HTTP.
  - Endpoint: `GET /image` on port 5005 (JPEG bytes).
  - Health: `GET /health`.
  - Run: `cargo run --release` from `proto/host`.
- Android client at `proto/front`: minimal app that fetches and shows the image from the host over tether.
   - Min SDK 17. Uses `HttpURLConnection` + `AsyncTask` to load `http://<host>:5005/image` every second automatically.
  - Default host prefilled: `192.168.42.170` (typical USB tether gateway).

## Usage

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
   - Launch the app; it starts receiving frame updates automatically once per second.

Notes:
- The Android project allows cleartext (network_security_config) for the host IP.
- If your tether IP differs, edit the text field before loading.
- Host desktop capture needs either `grim` (Wayland) or `import` from ImageMagick (X11).
