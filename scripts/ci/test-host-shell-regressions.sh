#!/usr/bin/env bash
# Exercise real CLI/installer control flow with isolated build/network fixtures.
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
runtime="$tmp/runtime"
mkdir -p "$runtime/host/rust/target/release" "$runtime/host/scripts" "$runtime/scripts" \
  "$runtime/config" "$tmp/bin" "$tmp/config" "$tmp/lib"
cp "$ROOT_DIR/wbeam" "$runtime/wbeam"
cp "$ROOT_DIR/scripts/evdi-compat.sh" "$ROOT_DIR/scripts/android-lan-bind-policy.sh" "$runtime/scripts/"
cp "$ROOT_DIR/host/scripts/wbeam_config.sh" "$runtime/host/scripts/"
cp "$ROOT_DIR/config/wbeam.conf" "$runtime/config/"
if [[ -f "$ROOT_DIR/config/branding.sh" ]]; then cp "$ROOT_DIR/config/branding.sh" "$runtime/config/"; fi
export XDG_CONFIG_HOME="$tmp/config"
export WBEAM_TEST_CURL_LOG="$tmp/curl.log"
export WBEAM_TEST_CARGO_LOG="$tmp/cargo.log"
cat > "$tmp/bin/curl" <<'MOCK'
#!/bin/sh
printf '%s\n' "$@" >> "$WBEAM_TEST_CURL_LOG"
if [ "${WBEAM_TEST_CURL_FAILURE:-0}" = 1 ]; then exit 7; fi
printf '{"ok":true,"capture_mode":"wayland_portal","available_backends":["wayland_portal"]}\n'
MOCK
cat > "$tmp/bin/cargo" <<'MOCK'
#!/bin/sh
printf '%s\n' "$*" >> "$WBEAM_TEST_CARGO_LOG"
MOCK
cat > "$tmp/bin/pkg-config" <<'MOCK'
#!/bin/sh
exit 0
MOCK
cat > "$runtime/host/rust/target/release/wbeamd-server" <<'MOCK'
#!/bin/sh
printf '%s\n' "$@" > "$WBEAM_TEST_DAEMON_ARGS"
MOCK
cat > "$tmp/bin/adb" <<'MOCK'
#!/bin/sh
exit 0
MOCK
cat > "$tmp/bin/pkill" <<'MOCK'
#!/bin/sh
echo 'unsafe process-name kill' >&2
exit 93
MOCK
cat > "$tmp/bin/systemctl" <<'MOCK'
#!/bin/sh
printf 'inactive\n'
exit 3
MOCK
chmod +x "$tmp/bin/"* "$runtime/host/rust/target/release/wbeamd-server"
export PATH="$tmp/bin:$PATH"

# Probe returns daemon JSON, honors the configured endpoint and has a bounded
# request. It does not require the missing Python helper or start a daemon.
result="$(WBEAM_CONTROL_PORT=5511 "$runtime/wbeam" host probe --json)"
jq -e '.ok == true and .capture_mode == "wayland_portal"' <<<"$result" >/dev/null
grep -Fxq 'http://127.0.0.1:5511/v1/host-probe' "$WBEAM_TEST_CURL_LOG"
grep -Fxq -- '--max-time' "$WBEAM_TEST_CURL_LOG"
: > "$WBEAM_TEST_CURL_LOG"
WBEAM_CONTROL_BIND_ADDR=::1 "$runtime/wbeam" host probe > /dev/null
grep -Fxq 'http://[::1]:5001/v1/host-probe' "$WBEAM_TEST_CURL_LOG"
status=0
WBEAM_TEST_CURL_FAILURE=1 "$runtime/wbeam" host probe > "$tmp/probe.stdout" 2> "$tmp/probe.stderr" || status=$?
[[ "$status" -eq 1 && ! -s "$tmp/probe.stdout" ]]
grep -Fq 'Cannot read host capabilities' "$tmp/probe.stderr"
grep -Fq 'systemctl --user start wbeam-daemon.service' "$tmp/probe.stderr"
status=0
"$runtime/wbeam" host probe --unknown > /dev/null 2>&1 || status=$?
[[ "$status" -eq 2 ]]

# Explicit LAN control must reach the daemon CLI, not just its log line.
export WBEAM_TEST_DAEMON_ARGS="$tmp/daemon.args"
WBEAM_CONTROL_BIND_ADDR=192.0.2.10 WBEAM_ALLOW_UNAUTHENTICATED_LAN_CONTROL=1 \
  "$runtime/wbeam" host run > "$tmp/host-run.log"
grep -Fxq -- '--control-bind-address' "$WBEAM_TEST_DAEMON_ARGS"
grep -Fxq -- '192.0.2.10' "$WBEAM_TEST_DAEMON_ARGS"
grep -Fxq -- '--allow-unauthenticated-lan-control' "$WBEAM_TEST_DAEMON_ARGS"
: > "$WBEAM_TEST_CURL_LOG"
WBEAM_CONTROL_BIND_ADDR=192.0.2.10 "$runtime/wbeam" host status > /dev/null
grep -Fxq 'http://192.0.2.10:5001/status' "$WBEAM_TEST_CURL_LOG"
# A custom development directory selects EVDI; a runtime-only directory cannot
# silently produce a different capture binary for an explicit request.
touch "$tmp/lib/libevdi.so"
WBEAM_EVDI_LIB_DIR="$tmp/lib" "$runtime/wbeam" host build > "$tmp/build.log"
grep -Fq 'build --release -p wbeamd-streamer --no-default-features --features evdi' "$WBEAM_TEST_CARGO_LOG"
rm "$tmp/lib/libevdi.so"
touch "$tmp/lib/libevdi.so.1"
: > "$WBEAM_TEST_CARGO_LOG"
status=0
WBEAM_EVDI_LIB_DIR="$tmp/lib" "$runtime/wbeam" host build > "$tmp/runtime-only.log" 2>&1 || status=$?
[[ "$status" -ne 0 && ! -s "$WBEAM_TEST_CARGO_LOG" ]]
grep -Fq 'WBEAM_EVDI_LIB_DIR must contain libevdi.so' "$tmp/runtime-only.log"
touch "$tmp/lib/libevdi.so"
WBEAM_EVDI_LIB_DIR="$tmp/lib" WBEAM_HOST_STREAMER_EVDI=0 "$runtime/wbeam" host build > "$tmp/portal-build.log"
grep -Fxq 'build --release -p wbeamd-streamer --no-default-features' "$WBEAM_TEST_CARGO_LOG"
! grep -Fq -- '--features evdi' "$WBEAM_TEST_CARGO_LOG"

# The trusted LAN fallback needs independently opted-in API and stream binds.
# A remote address alone, or a single listener opt-in, is never sufficient.
source "$ROOT_DIR/scripts/android-lan-bind-policy.sh"
status=0
waybeam_require_trusted_lan_binds 192.0.2.10 2>"$tmp/lan-error.log" || status=$?
[[ "$status" -ne 0 ]]
grep -Fq 'WBEAM_CONTROL_BIND_ADDR' "$tmp/lan-error.log"
status=0
WBEAM_CONTROL_BIND_ADDR=192.0.2.10 WBEAM_ALLOW_UNAUTHENTICATED_LAN_CONTROL=1 \
WBEAM_STREAM_BIND_ADDR=127.0.0.1 WBEAM_ALLOW_UNAUTHENTICATED_LAN_STREAM=1 \
  waybeam_require_trusted_lan_binds 192.0.2.10 2>"$tmp/lan-stream-error.log" || status=$?
[[ "$status" -ne 0 ]]
grep -Fq 'WBEAM_STREAM_BIND_ADDR' "$tmp/lan-stream-error.log"
WBEAM_CONTROL_BIND_ADDR=0.0.0.0 WBEAM_ALLOW_UNAUTHENTICATED_LAN_CONTROL=1 \
WBEAM_STREAM_BIND_ADDR=192.0.2.10 WBEAM_ALLOW_UNAUTHENTICATED_LAN_STREAM=1 \
  waybeam_require_trusted_lan_binds 192.0.2.10

# Installer previews are non-mutating and show exact service overrides.
installer="$ROOT_DIR/scripts/install-wizard.sh"
bash "$installer" --dry-run --backend wayland --skip-system-deps --skip-build --skip-device > "$tmp/portal-unit.log"
grep -Fq 'Environment=WBEAM_CAPTURE_BACKEND=wayland_portal' "$tmp/portal-unit.log"
bash "$installer" --dry-run --backend evdi --skip-system-deps --skip-build --skip-device > "$tmp/evdi-unit.log"
grep -Fq 'Environment=WBEAM_CAPTURE_BACKEND=evdi' "$tmp/evdi-unit.log"
WBEAM_STREAM_BIND_ADDR=192.0.2.10 WBEAM_ALLOW_UNAUTHENTICATED_LAN_STREAM=1 \
WBEAM_CONTROL_BIND_ADDR=192.0.2.10 WBEAM_ALLOW_UNAUTHENTICATED_LAN_CONTROL=1 \
  bash "$installer" --dry-run --backend wayland --skip-system-deps --skip-build --skip-device > "$tmp/bind-unit.log"
grep -Fq 'Environment="WBEAM_STREAM_BIND_ADDR=192.0.2.10"' "$tmp/bind-unit.log"
grep -Fq 'Environment="WBEAM_ALLOW_UNAUTHENTICATED_LAN_STREAM=1"' "$tmp/bind-unit.log"
grep -Fq 'Environment="WBEAM_CONTROL_BIND_ADDR=192.0.2.10"' "$tmp/bind-unit.log"
grep -Fq 'Environment="WBEAM_ALLOW_UNAUTHENTICATED_LAN_CONTROL=1"' "$tmp/bind-unit.log"
status=0
WBEAM_STREAM_BIND_ADDR=$'127.0.0.1\n[Unit]' \
  bash "$installer" --dry-run --backend wayland --skip-system-deps --skip-build --skip-device > "$tmp/invalid-unit.log" 2>&1 || status=$?
[[ "$status" -ne 0 ]]
grep -Fq 'must not contain a newline' "$tmp/invalid-unit.log"
status=0
bash "$installer" --dry-run --backend x11 --skip-device > "$tmp/x11.log" 2>&1 || status=$?
[[ "$status" -eq 2 ]]
echo '[host-shell][PASS] live probe, explicit EVDI builds, backend pinning, safe opt-in propagation and retained X11 gate'
