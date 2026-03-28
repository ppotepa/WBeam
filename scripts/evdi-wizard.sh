#!/usr/bin/env bash
set -euo pipefail

echo "=== WBeam EVDI Wizard (multi-monitor) ==="
echo "This will reload the EVDI module with multiple virtual devices (initial_device_count=4)"
echo "and persist the setting to /etc/modprobe.d/evdi.conf."
echo
read -r -p "Proceed? [y/N] " answer
if [[ ! "${answer:-}" =~ ^[Yy]$ ]]; then
  echo "Aborted."
  exit 1
fi

echo "--- Unloading existing evdi (ignore errors if not loaded) ---"
sudo modprobe -r evdi 2>/dev/null || true

echo "--- Loading evdi with 4 virtual devices ---"
sudo modprobe evdi initial_device_count=4

echo "--- Persisting module option ---"
echo "options evdi initial_device_count=4" | sudo tee /etc/modprobe.d/evdi.conf >/dev/null

echo "--- Verifying ---"
echo -n "initial_device_count: "
cat /sys/module/evdi/parameters/initial_device_count 2>/dev/null || echo "unavailable"
ls -l /dev/dri/card* 2>/dev/null || true

echo
echo "Next steps:"
echo "  1) Restart WBeam host service: ./wbeam host stop && ./wbeam host start"
echo "  2) Reconnect devices or use the desktop UI Autoconnect."
echo "Done."
