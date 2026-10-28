#!/usr/bin/env bash
# Regression for installing a daemon user unit without requiring an attached
# Android device or a live systemd instance. No root, ADB or network required.
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
unit_template="$ROOT_DIR/host/rust/systemd/wbeamd-rust.service.template"
installer="$ROOT_DIR/host/rust/scripts/install_systemd_user.sh"

# A failed ADB reverse prestart must not block ExecStart; do not ignore a failed
# actual daemon start. The "-" modifier is systemd's nonfatal command prefix.
grep -Fxq 'ExecStartPre=-%h/.local/bin/wbeam-usb-reverse.sh 5000 5001' "$unit_template"
grep -Fxq 'ExecStart=%h/.local/bin/wbeamd-rust --control-port 5001 --stream-port 5000 --root @ROOT@' "$unit_template"
if grep -Eq '^ExecStart=-' "$unit_template"; then
  echo "[systemd][FAIL] daemon start was incorrectly marked nonfatal" >&2
  exit 1
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
runtime="$tmp/runtime"
home="$tmp/home"
mkdir -p "$runtime/host/rust/systemd" "$runtime/host/rust/target/release" \
         "$tmp/bin" "$home"
cp "$unit_template" "$runtime/host/rust/systemd/wbeamd-rust.service.template"

# Installed native executable fixture; no Cargo checkout required.
cat > "$runtime/host/rust/target/release/wbeamd-server" <<'BIN'
#!/usr/bin/env bash
exit 0
BIN
chmod +x "$runtime/host/rust/target/release/wbeamd-server"

# Mock systemctl so we only inspect generated unit, not systemd state.
cat > "$tmp/bin/systemctl" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$@" >> "${WBEAM_TEST_SYSTEMCTL_LOG:?}"
MOCK
chmod +x "$tmp/bin/systemctl"

HOME="$home" WBEAM_ROOT="$runtime" PATH="$tmp/bin:$PATH" \
WBEAM_TEST_SYSTEMCTL_LOG="$tmp/systemctl.log" \
  bash "$installer" 5501 5500 > "$tmp/installer.stdout" 2>&1

unit="$home/.config/systemd/user/wbeamd-rust.service"
[[ -s "$unit" ]]
[[ -x "$home/.local/bin/wbeamd-rust" ]]
[[ -x "$home/.local/bin/wbeam-usb-reverse.sh" ]]
grep -Fxq 'ExecStartPre=-%h/.local/bin/wbeam-usb-reverse.sh 5500 5501' "$unit"
grep -Fxq "ExecStart=%h/.local/bin/wbeamd-rust --control-port 5501 --stream-port 5500 --root $runtime" "$unit"
grep -Fxq 'WorkingDirectory='"$runtime" "$unit"
grep -Fxq -- '--user' "$tmp/systemctl.log"
grep -Fxq -- 'enable' "$tmp/systemctl.log"
grep -Fxq -- '--now' "$tmp/systemctl.log"
grep -Fxq -- 'wbeamd-rust.service' "$tmp/systemctl.log"

echo '[systemd][PASS] offline-safe ADB prestart and generated user service port forwarding'
