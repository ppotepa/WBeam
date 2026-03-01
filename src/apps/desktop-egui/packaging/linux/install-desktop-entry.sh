#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../../../../.." && pwd)"
APP_DIR="${HOME}/.local/share/applications"
ICON_DIR="${HOME}/.local/share/icons/hicolor/512x512/apps"

mkdir -p "$APP_DIR" "$ICON_DIR"
install -m 0644 "$ROOT_DIR/src/assets/wbeam.png" "$ICON_DIR/wbeam-desktop.png"

cat > "$APP_DIR/wbeam-desktop.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=WBeam Desktop
Comment=WBeam desktop control panel
Exec=${ROOT_DIR}/desktop.sh
Icon=wbeam-desktop
Terminal=false
Categories=Utility;Development;
StartupWMClass=wbeam-desktop
X-GNOME-WMClass=wbeam-desktop
DESKTOP

if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "$APP_DIR" >/dev/null 2>&1 || true
fi

echo "Installed desktop entry: $APP_DIR/wbeam-desktop.desktop"
echo "Installed icon: $ICON_DIR/wbeam-desktop.png"
