#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== WayBeam EVDI Wizard ==="
echo "This will install EVDI prerequisites, load the module if needed, and configure"
echo "four initial virtual displays plus user permissions for future boots."
echo "An already-active EVDI module will not be unloaded; changing its device count"
echo "may require a reboot after existing display sessions have been stopped."
echo
read -r -p "Proceed? [y/N] " answer
if [[ ! "${answer:-}" =~ ^[Yy]$ ]]; then
  echo "Aborted."
  exit 1
fi

sudo bash "$SCRIPT_DIR/evdi-setup.sh"
echo
echo "Next steps:"
echo "  1) Complete any requested reboot or new login."
echo "  2) Restart the host service: systemctl --user restart wbeam-daemon.service"
echo "  3) Reconnect devices in the desktop UI and verify real frames."
