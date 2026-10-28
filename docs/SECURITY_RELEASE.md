# WBeam release security review — 0.1.3 candidate

## Threat boundaries

- **Tauri WebView** invokes a narrow set of native commands. The configured
  main-window capability exposes `core:default`, not blanket shell/filesystem
  access. A production CSP limits scripts/resources to bundled assets and
  explicitly allows only Tauri IPC. Vite HMR and `unsafe-eval` are allowed only
  through `devCsp` during development. See Tauri v2 CSP documentation.
- **Native commands** may start ADB, systemctl, curl and EVDI/pkexec setup.
  Inputs from device names and serials are data, never shell source. When sent
  through a daemon URL, a serial is percent-encoded as a query component.
  Arguments are passed using `Command::arg`/`args`.
- **ADB/Android transport** uses local HTTP over the ADB reverse tunnel
  (control port 5001, stream port 5000) and has an optional LAN host fallback.
  `android:usesCleartextTraffic` and the current Android network security
  `base-config` are intentionally retained for this transport; removing them
  without replacing the protocol would break existing clients.
- **EVDI setup** may require elevation via pkexec. Only the user-requested
  install path should ask for elevation. Do not automatically install system
  kernel patches or disable Secure Boot.
- **Diagnostics** may include raw serials, host names, internal addresses and
  command output. Logs are stored in XDG state when installed. Redact personal
  details, ADB serials and paths from attachments before posting publicly. Do
  not log/reveal signing secrets, key passwords or private keystore data.
- **Release signing** uses protected CI secrets and `apksigner`. Never publish
  a debug-signed APK or silently uninstall an old APK on signature mismatch.

## Manual verification required

1. Build desktop under production CSP; verify Solid renders, scripts/styles,
   Tauri `invoke` and modal actions work on Linux WebKit.
2. Build with Vite and ensure HMR still works with separate `devCsp`.
3. Simulate ADB serial containing `&`, `#`, `=`, whitespace and non-ASCII.
   Verify the daemon receives one correct serial and no extra query parameters.
4. Test disabled/hidden or unavailable service/ADB tools; no command injection,
   no unbounded child process or stuck modal.
5. Inspect listeners with `ss -ltnp` and check control API exposure and LAN
   binding; **do not** assume loopback is enforced just because the desktop
   sends requests to `127.0.0.1`.
6. Run a USB session and the documented LAN fallback on the Android client.
7. Confirm user settings and logs are not written under read-only
   `/usr/share/wbeam`.
8. Review exported Android receiver, backup policy, debug logs and user consent
   for future hardening. Tighter network origin rules require a separately
   tested transport contract.

## Limitations / risk register

This patch does not implement authenticated TCP control endpoints, TLS on
arbitrary LAN fallback, complete log redaction, a full pentest or kernel driver
audit. These remain release review decisions and require follow-up if any
endpoint is reachable by untrusted local-network peers. Do not treat passing
unit tests alone as security signoff.

References: `desktop/apps/desktop-tauri/src-tauri/tauri.conf.json`,
`desktop/apps/desktop-tauri/src-tauri/capabilities/default.json`,
`android/app/src/main/res/xml/network_security_config.xml`,
`android/app/src/main/AndroidManifest.xml`. Epic #101, issue #118.
