# Security boundaries and release review

WBeam uses ADB reverse for the normal USB workflow. Its control API and video
transport have separate listeners and neither provides application-level
authentication or TLS. USB use alone does not establish that every host
listener is restricted to loopback.

## Listeners

| Interface | Current behavior | Operational requirement |
| --- | --- | --- |
| Control HTTP API, port `5001` by default | Binds to `127.0.0.1`; non-loopback binds require explicit opt-in | Keep local for USB use; any LAN access must be restricted to trusted peers |
| Framed video over TCP, port `5000` by default | The streamer binds to `0.0.0.0` (all IPv4 interfaces) while streaming | Restrict inbound access to stream ports on untrusted networks; inspect per-device ports as well |

The video behavior is implemented in
[`transport/sender.rs`](../host/rust/crates/wbeamd-streamer/src/transport/sender.rs).
The control bind policy does **not** restrict that listener. Check the actual
sockets with `ss -ltnp`; do not infer exposure from the address the desktop or
Android client uses to connect.

### Explicit LAN control

The API includes unauthenticated session operations such as `/v1/start`,
`/v1/stop` and `/v1/apply`. To deliberately allow a trusted private LAN address,
set both options when launching the daemon from a source checkout:

```bash
WBEAM_CONTROL_BIND_ADDR=192.168.1.20 \
WBEAM_ALLOW_UNAUTHENTICATED_LAN_CONTROL=1 \
  bash host/scripts/run_wbeamd.sh 5001 5000
```

The direct binary equivalents are `--control-bind-address 192.168.1.20` and
`--allow-unauthenticated-lan-control`. A non-loopback address without opt-in is
rejected before binding. LAN mode adds no authentication, authorization or
encryption; do not expose these ports to the internet or untrusted peers.
Authenticated remote access requires protocol and client changes plus testing.

## Other boundaries

- **Desktop WebView:** the main-window capability grants `core:default`.
  Production CSP permits bundled scripts and Tauri IPC; inline styles and
  specified image/font sources remain allowed. Vite/HMR and `unsafe-eval`
  occur only in `devCsp`. Native commands still require review independently
  of WebView permissions.
- **Native commands:** the desktop invokes ADB, systemctl, curl and setup
  helpers. Device serials in daemon URLs are percent-encoded query components;
  process arguments are passed through Rust `Command` APIs. Preserve these
  boundaries when adding commands.
- **Android networking:** HTTP control and framed TCP video use ADB reverse
  by default, with configurable host addresses. `usesCleartextTraffic` and
  the network security `base-config` allow cleartext globally; they are not
  a private-address allowlist.
- **EVDI setup:** package/module installation can require sudo or pkexec.
  Elevation belongs to an explicit installation action. Secure Boot enrollment
  remains a user/firmware action; see the [EVDI guide](EVDI_SETUP_GUIDE.md).
- **Diagnostics:** logs can contain raw device serials, host names, addresses,
  paths and command output. Installed-mode logs belong in user state storage.
  Redact evidence before sharing it publicly.
- **APK signing:** use protected keys and verify the certificate. A signature
  mismatch must not silently delete user data; see
  [Android release signing](ANDROID_RELEASE_SIGNING.md).

## Release verification

1. Test a production desktop build on Linux WebKit: rendering, Tauri `invoke`,
   service actions and modal actions. Separately check development HMR.
2. Test serials containing `&`, `#`, `=`, whitespace and non-ASCII; the daemon
   must receive one serial without injected query parameters. Check missing
   tools and timeouts for native commands.
3. Inspect **both** control and video listeners during a USB session and any
   explicitly configured LAN session. Verify firewall restrictions where needed.
4. Confirm settings, daemon logs and effective-runtime snapshots stay outside
   the read-only package tree; follow the [release checklist](release-checklist.md).
5. Review Android's exported USB receiver, enabled backup policy and collected
   logs before approving the release.

These checks do not constitute a pentest, kernel-driver audit or proof of
complete log redaction. Record unresolved findings with the release evidence.

Source contracts:
[Tauri configuration](../desktop/apps/desktop-tauri/src-tauri/tauri.conf.json),
[capability](../desktop/apps/desktop-tauri/src-tauri/capabilities/default.json),
[Android network policy](../android/app/src/main/res/xml/network_security_config.xml),
[manifest](../android/app/src/main/AndroidManifest.xml),
[control bind policy](../host/rust/crates/wbeamd-server/src/main.rs).
