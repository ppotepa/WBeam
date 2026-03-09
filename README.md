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

3) `proto_x11/` lane (focused X11 virtual monitor)
- strict real-output X11 path (no default `--setmonitor` fallback)
- separate APK identity for experiments: `com.wbeam.x11`
- host entrypoint: `proto_x11/run.sh`

## repo layout (after src migration)

- `src/apps/desktop-egui/` -> desktop control app (rust + egui)
- `src/assets/` -> shared app assets/icons
- `src/host/` -> host daemon/runtime/scripts
- `src/protocol/` -> protocol crates/scripts
- `src/compat/` -> api-level policy packs (`api17`, `api21`, `api29`)
- `proto/` -> sandbox lane kept separate on purpose

## quick start (main command: `./wbeam`)

- check command list:

```bash
./wbeam --help
```

- most common local flow:

```bash
./wbeam service install
./wbeam service start
./wbeam android deploy-all
./wbgui
```

- check runtime health:

```bash
./wbeam daemon status
./wbeam version doctor
```

## remote usage (why `start-remote` exists)

`start-remote` exists for remote work: host machine has USB-connected Android devices, but you control it from another PC (RDP/remote session).  
It automates host/service/android prep in one command.

```bash
./start-remote <remote-user>
```

If you only need to run one app in a remote user session, use:

```bash
./runas-remote <remote-user> <command>
```

- run proto lane (sandbox):

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
