#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
UNIT_TEMPLATE="$ROOT_DIR/src/host/rust/systemd/wbeamd-rust.service.template"

CONTROL_PORT="${1:-5001}"
STREAM_PORT="${2:-5000}"

if ! command -v cargo >/dev/null 2>&1; then
  echo "cargo is required" >&2
  exit 1
fi
if ! command -v systemctl >/dev/null 2>&1; then
  echo "systemctl is required" >&2
  exit 1
fi

BIN_DIR="$HOME/.local/bin"
UNIT_DIR="$HOME/.config/systemd/user"
mkdir -p "$BIN_DIR" "$UNIT_DIR"

echo "[wbeam] building release binary..."
cargo build --release --manifest-path "$ROOT_DIR/src/host/rust/Cargo.toml" -p wbeamd-server

install -m 755 "$ROOT_DIR/src/host/rust/target/release/wbeamd-server" "$BIN_DIR/wbeamd-rust"

cat > "$BIN_DIR/wbeam-usb-reverse.sh" <<USBREV
#!/usr/bin/env bash
set -euo pipefail
STREAM_PORT="\${1:-$STREAM_PORT}"
CONTROL_PORT="\${2:-$CONTROL_PORT}"
"$ROOT_DIR/src/host/scripts/usb_reverse.sh" "\$STREAM_PORT"
adb reverse "tcp:\${CONTROL_PORT}" "tcp:\${CONTROL_PORT}"
USBREV
chmod +x "$BIN_DIR/wbeam-usb-reverse.sh"

UNIT_FILE="$UNIT_DIR/wbeamd-rust.service"
sed \
  -e "s|@ROOT@|$ROOT_DIR|g" \
  -e "s|wbeam-usb-reverse.sh 5000 5001|wbeam-usb-reverse.sh $STREAM_PORT $CONTROL_PORT|g" \
  -e "s|--control-port 5001 --stream-port 5000|--control-port $CONTROL_PORT --stream-port $STREAM_PORT|g" \
  "$UNIT_TEMPLATE" > "$UNIT_FILE"

echo "[wbeam] reloading user systemd and enabling service..."
systemctl --user daemon-reload
systemctl --user enable --now wbeamd-rust.service

echo "[wbeam] service status:"
systemctl --user --no-pager --full status wbeamd-rust.service || true

echo "[wbeam] installed. For auto-start without active login session run once:"
echo "  loginctl enable-linger $USER"
