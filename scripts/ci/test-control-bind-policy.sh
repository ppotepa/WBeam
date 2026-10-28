#!/usr/bin/env bash
# Regression for the host runner's control-plane bind arguments.
# No real daemon, network socket, ADB or kernel privileges required.
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runner="$ROOT_DIR/host/scripts/run_wbeamd.sh"
main="$ROOT_DIR/host/rust/crates/wbeamd-server/src/main.rs"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/config" "$tmp/home"

cat > "$tmp/mock-daemon" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$@" > "${WBEAM_TEST_ARGS:?}"
MOCK
chmod +x "$tmp/mock-daemon"

invoke_mock() {
  local bind_address="$1" insecure="$2"
  WBEAM_TEST_ARGS="$tmp/args" \
  WBEAM_DAEMON_BIN="$tmp/mock-daemon" \
  WBEAM_DAEMON_IMPL=rust \
  WBEAM_DAEMON_PRESTART_REVERSE=0 \
  WBEAM_CONTROL_BIND_ADDR="$bind_address" \
  WBEAM_ALLOW_UNAUTHENTICATED_LAN_CONTROL="$insecure" \
  XDG_CONFIG_HOME="$tmp/config" \
  HOME="$tmp/home" \
    bash "$runner" 5001 5000 >/dev/null
}

invoke_mock "" 0
if grep -Fxq -- '--control-bind-address' "$tmp/args" || grep -Fxq -- '--allow-unauthenticated-lan-control' "$tmp/args"; then
  echo "[control][FAIL] default launcher passed public-bind opt-in flags" >&2
  exit 1
fi
invoke_mock "192.168.1.10" 0
grep -Fxq -- '--control-bind-address' "$tmp/args"
grep -Fxq -- '192.168.1.10' "$tmp/args"
if grep -Fxq -- '--allow-unauthenticated-lan-control' "$tmp/args"; then
  echo "[control][FAIL] non-loopback address implicitly enabled unsafe mode" >&2
  exit 1
fi
invoke_mock "0.0.0.0" 1
grep -Fxq -- '--control-bind-address' "$tmp/args"
grep -Fxq -- '0.0.0.0' "$tmp/args"
grep -Fxq -- '--allow-unauthenticated-lan-control' "$tmp/args"

# Source guard prevents a future accidental return to binding on all
# interfaces by default. Real authorization is exercised by Rust unit tests.
grep -Fq 'default_value = "127.0.0.1"' "$main"
grep -Fq 'validate_control_bind_address(' "$main"
grep -Fq 'SocketAddr::new(bind_ip, args.control_port)' "$main"
# Network exposure must be rejected *before* lock files or app sessions are
# initialized, not merely before TcpListener::bind().
policy_line="$(grep -n 'let bind_ip = match validate_control_bind_address(' "$main" | head -n1 | cut -d: -f1)"
lock_line="$(grep -n 'let lock_path = PathBuf::from(&args.lock_file)' "$main" | head -n1 | cut -d: -f1)"
[[ -n "$policy_line" && -n "$lock_line" && "$policy_line" -lt "$lock_line" ]] || {
  echo "[control][FAIL] unsafe listener policy runs after daemon initialization" >&2
  exit 1
}
if grep -Fq 'SocketAddr::from(([0, 0, 0, 0], args.control_port))' "$main"; then
  echo "[control][FAIL] old open-by-default listener returned" >&2
  exit 1
fi
echo "[control][PASS] local control defaults and explicit LAN opt-in arguments"
