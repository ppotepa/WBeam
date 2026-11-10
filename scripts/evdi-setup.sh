#!/usr/bin/env bash
# Legacy EVDI-only setup entrypoint. For Fedora's complete bootstrap use
# fedora-setup.sh --yes --with-evdi. Keep this helper for existing workflows.
set -Eeuo pipefail

EVDI_SETUP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$EVDI_SETUP_DIR/evdi-compat.sh"

usage() {
  cat <<'HELP'
Usage: scripts/evdi-setup.sh [--dry-run] [--skip-diagnostic]
  --dry-run          print the plan without root, package changes or kernel changes
  --skip-diagnostic  skip the optional pre-install diagnostic report
Run a real installation with sudo from your normal desktop user's account.
HELP
}

setup_error() { printf '[evdi-setup] ERROR: %s\n' "$*" >&2; }

exec_cmd() {
  printf '[evdi-setup]'
  if [[ "$DRY_RUN" -eq 1 ]]; then printf ' DRY-RUN:'; fi
  printf ' %q' "$@"
  printf '\n'
  [[ "$DRY_RUN" -eq 1 ]] || "$@"
}

detect_pm() {
  local manager
  for manager in pacman apt-get dnf yum; do
    if command -v "$manager" >/dev/null 2>&1; then
      printf '%s\n' "$manager"
      return 0
    fi
  done
  return 1
}

require_root() {
  if [[ "$(id -u)" -ne 0 ]]; then
    setup_error 'installation requires sudo; --dry-run does not'
    return 1
  fi
}

evdi_install_packages() {
  local manager="$1" kernel="$2" distro="$3" version="$4" dev_package=libevdi-dev
  case "$manager" in
    apt-get)
      [[ "$distro" != debian || "$version" != 12* ]] || dev_package=libevdi0-dev
      exec_cmd apt-get update || return 1
      exec_cmd apt-get install -y "linux-headers-$kernel" build-essential dkms evdi-dkms "$dev_package" || return 1
      ;;
    pacman)
      # Do not refresh only the sync database: that can cause a partial upgrade.
      exec_cmd pacman -S --needed --noconfirm linux-headers base-devel dkms evdi-dkms || return 1
      ;;
    dnf|yum)
      exec_cmd "$manager" makecache || return 1
      exec_cmd "$manager" install -y "kernel-devel-$kernel" gcc make dkms evdi-dkms || return 1
      ;;
    *) setup_error "unsupported package manager: $manager"; return 1 ;;
  esac
}

evdi_write_file() {
  local path="$1" contents="$2" temporary
  if [[ "$DRY_RUN" -eq 1 ]]; then
    printf '[evdi-setup] DRY-RUN: write %s\n%s\n' "$path" "$contents"
    return 0
  fi
  mkdir -p "$(dirname "$path")" || return 1
  temporary="$(mktemp "${path}.XXXXXX")" || return 1
  if ! printf '%s\n' "$contents" > "$temporary" || ! chmod 0644 "$temporary"; then
    rm -f "$temporary"
    return 1
  fi
  mv -f "$temporary" "$path"
}

evdi_write_configuration() {
  local config_root="${1:-/etc}" old_rule old_line filtered
  old_rule="$config_root/udev/rules.d/50-evdi.rules"
  old_line='KERNEL=="card[0-9]*", SUBSYSTEM=="drm", ATTRS{vendor}=="0x1d0f", MODE="0666"'
  # Remove only the unsafe line emitted by earlier versions. Preserve unrelated
  # administrator rules and keep a backup of the legacy file when migrating it.
  if [[ -f "$old_rule" ]] && grep -Fqx "$old_line" "$old_rule"; then
    if [[ "$DRY_RUN" -eq 1 ]]; then
      printf '[evdi-setup] DRY-RUN: remove legacy world-writable rule from %s\n' "$old_rule"
    else
      if [[ ! -e "$old_rule.wbeam-backup" ]]; then
        cp -p "$old_rule" "$old_rule.wbeam-backup" || return 1
      fi
      filtered="$(awk -v unsafe="$old_line" '$0 != unsafe' "$old_rule")" || return 1
      evdi_write_file "$old_rule" "$filtered" || return 1
    fi
  fi
  evdi_write_file "$config_root/modprobe.d/wbeam-evdi.conf" \
    $'# Managed by WayBeam EVDI setup\noptions evdi initial_device_count=4' || return 1
  evdi_write_file "$config_root/modules-load.d/wbeam-evdi.conf" \
    $'# Managed by WayBeam EVDI setup\nevdi' || return 1
  evdi_write_file "$config_root/udev/rules.d/70-wbeam-evdi.rules" \
    $'# Managed by WayBeam EVDI setup; match EVDI ancestry, never a physical GPU\nSUBSYSTEM=="drm", KERNEL=="card[0-9]*", KERNELS=="evdi*", GROUP="video", MODE="0660", TAG+="uaccess"' || return 1
}

evdi_setup_main() {
  local DRY_RUN=0 skip_diagnostic=0 distro=unknown version=unknown manager kernel
  local target_user group_changed=0 card
  local -a cards=()
  while (($#)); do
    case "$1" in
      --dry-run) DRY_RUN=1; shift ;;
      --skip-diagnostic) skip_diagnostic=1; shift ;;
      --help|-h) usage; return 0 ;;
      *) setup_error "unknown option: $1"; usage >&2; return 2 ;;
    esac
  done
  if [[ -r /etc/os-release ]]; then
    distro="$(. /etc/os-release; printf '%s' "${ID:-unknown}")"
    version="$(. /etc/os-release; printf '%s' "${VERSION_ID:-unknown}")"
  fi
  manager="$(detect_pm)" || { setup_error 'no supported package manager found'; return 1; }
  kernel="$(uname -r)"
  target_user="${SUDO_USER:-$(id -un)}"
  printf '[evdi-setup] distro=%s version=%s kernel=%s user=%s\n' "$distro" "$version" "$kernel" "$target_user"
  if [[ "$DRY_RUN" -eq 0 ]]; then
    require_root || return 1
    if [[ "$skip_diagnostic" -eq 0 ]]; then
      # Failure before installing is expected; installation errors below are fatal.
      bash "$EVDI_SETUP_DIR/evdi-diagnose.sh" --verbose || true
    fi
  fi
  if ! evdi_install_packages "$manager" "$kernel" "$distro" "$version"; then
    setup_error 'package installation failed; no module or permission changes were applied'
    if [[ "$manager" == dnf ]]; then
      printf '[evdi-setup] Fedora: use scripts/fedora-setup.sh --yes --with-evdi for DisplayLink package discovery.\n' >&2
    fi
    return 1
  fi
  evdi_write_configuration || return 1
  exec_cmd udevadm control --reload-rules || return 1
  # Loading an already-loaded module is harmless; never unload an active display.
  exec_cmd modprobe evdi initial_device_count=4 || {
    setup_error 'module load failed; inspect matching kernel headers, DKMS logs and Secure Boot/MOK'
    return 1
  }
  exec_cmd udevadm settle --timeout=10 || return 1
  exec_cmd ldconfig || return 1
  if [[ "$DRY_RUN" -eq 1 ]]; then
    printf '[evdi-setup] DRY-RUN: verify EVDI DRM nodes, libevdi and access for %s; add to video if needed\n' "$target_user"
    return 0
  fi
  mapfile -t cards < <(evdi_find_drm_devices || true)
  if [[ "${#cards[@]}" -eq 0 ]]; then
    setup_error 'no EVDI DRM card exists after module load; an existing card0 is not sufficient'
    setup_error 'if EVDI was already loaded without devices, stop its users before an explicit module reload or reboot'
    return 1
  fi
  for card in "${cards[@]}"; do
    exec_cmd udevadm trigger --action=change "/sys/class/drm/${card##*/}" || return 1
  done
  exec_cmd udevadm settle --timeout=10 || return 1
  if ! id -nG "$target_user" | tr ' ' '\n' | grep -Fxq video; then
    exec_cmd usermod -a -G video "$target_user" || return 1
    group_changed=1
  fi
  if ! evdi_find_runtime_library >/dev/null; then
    setup_error 'libevdi runtime library is missing; install the package matching your streamer ABI'
    return 1
  fi
  printf '[evdi-setup] Module, EVDI DRM devices and runtime library installed.\n'
  if [[ "$group_changed" -eq 1 ]]; then
    printf '[evdi-setup] Log out and back in as %s to apply the video group membership.\n' "$target_user"
  fi
  if ! evdi_find_linkable_library >/dev/null; then
    printf '[evdi-setup] Source builds additionally need the development package providing libevdi.so.\n'
  fi
  printf '[evdi-setup] From the desktop user session, run scripts/virtual-deps-check.sh --json --backend evdi, then verify real Android frames.\n'
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  evdi_setup_main "$@"
fi
