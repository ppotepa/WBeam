wbeam is an open-source usb second-screen project.
idea is simple: plug an android tablet/phone into linux and use it like an extra display with low lag.

this repo has two active lanes:

1) root/main lane
- `android/` has the main app (`com.wbeam`) with preflight checks, status overlay, live metrics, and framed h264 playback.
- `host/rust/` has the rust daemon crates: `wbeamd-server`, `wbeamd-core`, `wbeamd-streamer`, `wbeamd-api`.
- `host/daemon/wbeamd.py` is a python fallback daemon when rust is not available.
- `protocol/rust/` has transport crates (`wbtp-core`, `wbtp-sender`, `wbtp-host`, receivers) used for framing and protocol tests.
- `wbeam` and `wbgui` are repo-level runners for host/android/service flows.

2) `proto/` lane
- fast iteration sandbox for old hardware (api17 class devices).
- used to test risky changes quickly: startup flow, reconnect behavior, queue sizing, pacing, decoder compatibility.
- defaults in `proto/run.sh` are tuned for repeatable real-device runs.

proto streaming path right now:
wayland -> xdg-desktop-portal + pipewire -> gstreamer h264 pipeline (`host/scripts/stream_wayland_portal_h264.py`) -> framed bridge (`proto/host/src/main.rs`) -> adb tunnel -> android `MediaCodec` decode/render.
frames are carried with explicit headers (magic/seq/timestamp/len) so parsing and stats are deterministic.

what is already working:
- usb second-screen flow runs end-to-end on real devices.
- framed h264 transport with reconnect/watchdog logic.
- host daemon control api (`/v1/status`, `/v1/start`, `/v1/stop`, metrics/config paths).
- android preflight + runtime diagnostics.
- `run.sh` startup hardening (no more silent hangs on early adb shell calls).
- pipewire/portal/queue tuning: less flicker, fewer stale-frame bursts, lower buffer lag.
- host/app observability for fps, drops, transport health.

current focus:
cut interaction delay further, keep visual stability under portal jitter, and upstream proto learnings into the root lane.

quick start:
- run `./install-deps` once from repo root.
- main lane: `./wbeam ...` or `./wbgui`.
- proto lane: `proto/run.sh`.
