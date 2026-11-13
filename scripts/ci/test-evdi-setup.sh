#!/usr/bin/env bash
# Check legacy setup migration and failure handling using temporary files/mocks.
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT_DIR/scripts/evdi-setup.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/etc/udev/rules.d" "$tmp/bin" "$tmp/lib"
old_rule="$tmp/etc/udev/rules.d/50-evdi.rules"
cat > "$old_rule" <<'RULES'
# EVDI DRM device rule
KERNEL=="card[0-9]*", SUBSYSTEM=="drm", ATTRS{vendor}=="0x1d0f", MODE="0666"
# This administrator's unrelated rule must survive migration.
SUBSYSTEM=="usb", ATTR{idVendor}=="1234", MODE="0660"
RULES
cp "$old_rule" "$tmp/original.rules"
DRY_RUN=0 evdi_write_configuration "$tmp/etc"
cmp "$old_rule.wbeam-backup" "$tmp/original.rules"
! grep -Fq 'MODE="0666"' "$old_rule"
grep -Fq 'SUBSYSTEM=="usb", ATTR{idVendor}=="1234", MODE="0660"' "$old_rule"
grep -Fxq evdi "$tmp/etc/modules-load.d/wbeam-evdi.conf"
grep -Fxq 'options evdi initial_device_count=4' "$tmp/etc/modprobe.d/wbeam-evdi.conf"
grep -Fq 'KERNELS=="evdi*", GROUP="video", MODE="0660", TAG+="uaccess"' "$tmp/etc/udev/rules.d/70-wbeam-evdi.rules"
DRY_RUN=0 evdi_write_configuration "$tmp/etc"
cmp "$old_rule.wbeam-backup" "$tmp/original.rules"

export WBEAM_TEST_COMMAND_LOG="$tmp/commands.log"
for command in apt-get modprobe udevadm ldconfig usermod sudo; do
  cat > "$tmp/bin/$command" <<'MOCK'
#!/bin/sh
command_name="${0##*/}"
printf '%s %s\n' "$command_name" "$*" >> "$WBEAM_TEST_COMMAND_LOG"
if [ "$command_name" = apt-get ] && [ "${WBEAM_TEST_PACKAGE_FAIL:-0}" = 1 ]; then exit 42; fi
exit 0
MOCK
  chmod +x "$tmp/bin/$command"
done
export PATH="$tmp/bin:$PATH"
# The setup uses the package manager abstraction, not the test machine's distro.
detect_pm() { printf 'apt-get\n'; }
# A failing root guard would prove dry-run accidentally requests elevation.
require_root() { echo 'unexpected root requirement' >&2; return 91; }
: > "$WBEAM_TEST_COMMAND_LOG"
evdi_setup_main --dry-run --skip-diagnostic > "$tmp/dry-run.log"
[[ ! -s "$WBEAM_TEST_COMMAND_LOG" ]]
grep -Fq 'DRY-RUN: write /etc/modules-load.d/wbeam-evdi.conf' "$tmp/dry-run.log"
! grep -Fq 'modprobe -r' "$tmp/dry-run.log"

# The real command runner now executes only mock binaries. A configuration
# interception guarantees a regression can never write the host's /etc.
require_root() { return 0; }
evdi_write_configuration() {
  printf 'configuration\n' >> "$WBEAM_TEST_COMMAND_LOG"
}
status=0
WBEAM_TEST_PACKAGE_FAIL=1 evdi_setup_main --skip-diagnostic > "$tmp/package-failure.log" 2>&1 || status=$?
[[ "$status" -ne 0 ]]
grep -Fq 'package installation failed' "$tmp/package-failure.log"
! grep -Eq 'configuration|modprobe|usermod|udevadm' "$WBEAM_TEST_COMMAND_LOG"

# Successful mock setup must load without unloading an in-use module, and must
# not label a missing EVDI DRM device as a successful installation.
evdi_find_drm_devices() {
  [[ "${WBEAM_TEST_NO_DRM:-0}" == 1 ]] || printf '/dev/null\n'
}
evdi_find_runtime_library() { printf '%s/libevdi.so.1\n' "$tmp/lib"; }
evdi_find_linkable_library() { return 1; }
: > "$WBEAM_TEST_COMMAND_LOG"
evdi_setup_main --skip-diagnostic > "$tmp/setup.log"
grep -Fxq 'modprobe evdi initial_device_count=4' "$WBEAM_TEST_COMMAND_LOG"
! grep -Fq 'modprobe -r' "$WBEAM_TEST_COMMAND_LOG"
grep -Fq 'development package providing libevdi.so' "$tmp/setup.log"
status=0
WBEAM_TEST_NO_DRM=1 evdi_setup_main --skip-diagnostic > "$tmp/no-drm.log" 2>&1 || status=$?
[[ "$status" -ne 0 ]]
grep -Fq 'no EVDI DRM card exists' "$tmp/no-drm.log"

# The existing interactive wizard keeps consent and delegates to safe setup.
: > "$WBEAM_TEST_COMMAND_LOG"
status=0
printf 'n\n' | bash "$ROOT_DIR/scripts/evdi-wizard.sh" > "$tmp/wizard-no.log" || status=$?
[[ "$status" -eq 1 && ! -s "$WBEAM_TEST_COMMAND_LOG" ]]
printf 'y\n' | bash "$ROOT_DIR/scripts/evdi-wizard.sh" > "$tmp/wizard-yes.log"
grep -Fxq "sudo bash $ROOT_DIR/scripts/evdi-setup.sh" "$WBEAM_TEST_COMMAND_LOG"
grep -Fq 'systemctl --user restart wbeam-daemon.service' "$tmp/wizard-yes.log"
! grep -Fq 'modprobe -r' "$WBEAM_TEST_COMMAND_LOG"
echo '[evdi-setup][PASS] safe udev migration, boot persistence, non-root preview, fatal errors and no active-module unload'
