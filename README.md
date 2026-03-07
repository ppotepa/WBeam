# wbeam

wbeam is an open-source usb second-screen project.

main reason: i needed one more small display for terminals, and i had an old lenovo s6000-h tablet (android api 17), so this repo became both a real tool and a compatibility lab.

idea is simple: plug android phone/tablet into linux and use it as an extra display with low lag.

## current lanes

1) main lane (production-ish path)
- `android/` -> main app (`com.wbeam`)
- `src/host/rust/` -> rust daemon + streamer crates
- `src/host/daemon/wbeamd.py` -> python fallback daemon
- `src/protocol/rust/` -> protocol/transport crates
- root runner: `./devtool` (GUI + CLI)

2) `proto/` lane (fast iteration sandbox)
- tuned for older devices (api17 class)
- used for risky changes: startup flow, reconnect, queue sizing, pacing, decoder behavior
- main entrypoint: `proto/run.sh`

## repo layout (after src migration)

- `src/apps/desktop-egui/` -> desktop control app (rust + egui)
- `src/assets/` -> shared app assets/icons
- `src/host/` -> host daemon/runtime/scripts
- `src/protocol/` -> protocol crates/scripts
- `src/compat/` -> api-level policy packs (`api17`, `api21`, `api29`)
- `proto/` -> sandbox lane kept separate on purpose

full migration notes: `docs/repo_tree_src_layout.md`

## quick start

- install deps once:

```bash
./devtool deps install
```

- start main lane (GUI):

```bash
./devtool
```

- start main lane (cli build/deploy):

```bash
./devtool build
./devtool deploy
```

- run remote desktop GUI on another user session:
```bash
./runas-remote <user> ./devtool -- gui
```

- secret/env safety note for `runas-remote`:
```bash
# pass only selected secret env vars to target app (avoid putting secrets in CLI args)
RUNAS_REMOTE_PASSTHROUGH_ENV="WBEAM_API_TOKEN,WBEAM_SECRET" ./runas-remote <user> ./devtool -- gui

# or load KEY=VALUE from file (chmod 600 recommended)
RUNAS_REMOTE_ENV_FILE=/path/to/remote.env ./runas-remote <user> ./devtool -- gui
```

- run proto lane:

```bash
cd proto
./run.sh
```

## current proto streaming path

wayland -> xdg-desktop-portal + pipewire -> gstreamer h264 pipeline (`src/host/scripts/stream_wayland_portal_h264.py`) -> framed bridge (`proto/host/src/main.rs`) -> adb tunnel -> android `MediaCodec` decode/render.

frames are wrapped with explicit headers (magic/seq/timestamp/len), so parsing and stats are deterministic.

## resolver / compatibility

- `src/compat/` stores policy packs and resolver rules.
- android builds `client-hello` capability payloads (`com.wbeam.compat.*`, `com.wbeam.resolver.*`).
- rust host resolver lives in `src/host/rust/crates/wbeamd-core/src/resolver/`.
- host endpoint:
  - `POST /v1/client-hello` (also `/client-hello`)

## profile learning note

`proto/autotune.py` uses hyperparameter optimization (evolutionary/genetic search).

this is ml-style black-box optimization of streaming params (fps/bitrate/queue/transport), not neural network training.

i was gonna do it in trackmania, but that would be cheating.

## what works now

- end-to-end usb second-screen on real devices
- framed h264 transport with reconnect/watchdog logic
- host control api (`/v1/status`, `/v1/start`, `/v1/stop`, metrics/config)
- android preflight + runtime diagnostics
- portal/pipewire queue tuning with better stability and lower lag spikes
- desktop app for runtime monitoring + settings
