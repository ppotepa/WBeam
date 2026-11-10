# Linux Host

The host owns daemon lifecycle, HTTP control, capture/encode/transport,
per-device sessions, adaptation and runtime diagnostics. Desktop and Android
clients request changes through the API; they do not own host session state.

- [`rust/`](rust/Cargo.toml): daemon, core, API models, streamer and TUI tuner.
- [`scripts/`](scripts/): configuration loading, desktop-session environment,
  daemon launchers, ADB reverse and soak helpers.

From the repository root, after [setup](../docs/INSTALLER_WIZARD.md):

```bash
./wbeam host build
./wbeam host run       # foreground daemon
```

Use `./wbeam host status` in another terminal; `./wbeam host tuner` starts the
interactive autotuner. Persistent installations use the `wbeam-daemon` systemd
user service. Avoid starting a second daemon on the same control port.

Release capture paths are EVDI and Wayland Portal. X11 implementation remains
as legacy code, outside current release selection. See the
[architecture map](../docs/repo-structure.md),
[API and telemetry contract](../docs/telemetry_schema.md), and
[network exposure policy](../docs/SECURITY_RELEASE.md).
