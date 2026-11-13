#!/usr/bin/env bash
# WayBeam EVDI diagnostics. Read-only: no compiler invocation, device open,
# package installation, permission change, module load or temporary C program.
set -u
SCRIPT_PATH="$(readlink -f "${BASH_SOURCE[0]}")"
ROOT_DIR="$(cd "$(dirname "$SCRIPT_PATH")/.." && pwd)"
source "$ROOT_DIR/scripts/evdi-compat.sh"

evdi_mok_pending_file() { printf '%s/.cache/evdi-mok-import-pending\n' "$ROOT_DIR"; }
evdi_mok_import_pending() {
  local key="$1" marker current_sha
  marker="$(evdi_mok_pending_file)"
  [[ -f "$key" && -f "$marker" ]] || return 1
  current_sha="$(sha256sum "$key" | awk '{print $1}')"
  grep -Fxq "sha256=$current_sha" "$marker"
}

diagnostic_pass() { printf '[PASS] %s\n' "$*"; }
diagnostic_warn() { WARNINGS=$((WARNINGS + 1)); printf '[WARN] %s\n' "$*"; }
diagnostic_fail() { ERRORS=$((ERRORS + 1)); printf '[FAIL] %s\n' "$*"; }

evdi_diagnostic_hint() {
  [[ "$FIX_RECOMMENDATIONS" -eq 1 ]] || return 0
  case "$1" in
    libevdi-elf-inspector-missing)
      echo '  Install binutils (readelf or objdump) to verify the actual library ABI.' ;;
    libevdi-abi-mismatch:*|libevdi-runtime-library:*)
      echo '  Install the libevdi package matching this streamer. Do not redirect .so.0 to .so.1 or vice versa.' ;;
    streamer-binary-missing|streamer-without-evdi)
      echo '  Build the EVDI streamer with ./waybeam host build, or install the matching distribution package.' ;;
    streamer-linker-check-unavailable)
      echo '  Install the libc runtime diagnostic tools providing ldd.' ;;
    *) echo '  Inspect the selected streamer and its dynamic dependencies; run ldd on that binary.' ;;
  esac
}

evdi_diagnose_main() {
  local VERBOSE=0 FIX_RECOMMENDATIONS=0 ERRORS=0 WARNINGS=0
  local distro=unknown kernel user module_loaded=0 module_present=0
  local log_path reason linkable card accessible=0 key_status config_path
  local -a cards=() runtime_issues=()
  while (($#)); do
    case "$1" in
      --verbose|-v) VERBOSE=1; shift ;;
      --fix|--fix-recommendations|-f) FIX_RECOMMENDATIONS=1; shift ;;
      --help|-h)
        echo 'Usage: scripts/evdi-diagnose.sh [--verbose] [--fix]'
        echo 'Read-only runtime checks and separate source-build diagnostics; --fix prints suggestions.'
        return 0 ;;
      *) echo "Unknown option: $1" >&2; return 2 ;;
    esac
  done
  if [[ -r /etc/os-release ]]; then
    distro="$(. /etc/os-release; printf '%s' "${ID:-unknown}")"
  fi
  kernel="$(uname -r)"
  user="$(id -un)"
  printf 'WayBeam EVDI diagnostics\nHost: %s; kernel: %s; current user: %s\n' "$distro" "$kernel" "$user"

  echo
  echo '1. Module and DRM runtime'
  if command -v modinfo >/dev/null 2>&1 && modinfo evdi >/dev/null 2>&1; then
    module_present=1
    diagnostic_pass 'EVDI module is installed for the running kernel'
  fi
  if command -v lsmod >/dev/null 2>&1; then
    if lsmod | grep '^evdi ' >/dev/null; then module_loaded=1; fi
  elif grep -q '^evdi ' /proc/modules 2>/dev/null; then
    module_loaded=1
  fi
  if [[ "$module_loaded" -eq 1 ]]; then
    diagnostic_pass 'EVDI module is loaded'
  else
    diagnostic_fail 'EVDI module is not loaded'
    if [[ "$FIX_RECOMMENDATIONS" -eq 1 ]]; then
      echo '  Inspect dkms status and module signing. Load with: sudo modprobe evdi initial_device_count=4'
      echo '  The setup helper writes both module options and modules-load.d for future boots.'
    fi
  fi
  mapfile -t cards < <(evdi_find_drm_devices || true)
  if [[ "${#cards[@]}" -eq 0 ]]; then
    diagnostic_fail 'No EVDI DRM device exists in sysfs and /dev/dri; a physical card0 is not sufficient'
  else
    for card in "${cards[@]}"; do
      if evdi_device_accessible "$card"; then
        diagnostic_pass "Current user can read/write EVDI device $card"
        accessible=1
      elif [[ "$VERBOSE" -eq 1 ]]; then
        printf '[INFO] Current user cannot read/write %s\n' "$card"
      fi
    done
    if [[ "$accessible" -eq 0 ]]; then
      diagnostic_fail 'Current user cannot read/write any EVDI DRM device'
      if [[ "$FIX_RECOMMENDATIONS" -eq 1 ]]; then
        echo '  Check the device ACL/group, add the desktop user to video if needed, then log out and back in.'
        echo '  Run this check from that desktop session, not through sudo; do not use world-writable permissions.'
      fi
    fi
  fi
  if [[ "$VERBOSE" -eq 1 ]]; then
    printf '[INFO] Current-session groups: %s\n' "$(id -nG)"
  fi

  echo
  echo '2. Selected streamer and userspace ABI'
  printf '[INFO] Streamer: %s\n' "$(evdi_selected_streamer "$ROOT_DIR")"
  mapfile -t runtime_issues < <(evdi_streamer_runtime_issues "$ROOT_DIR")
  if [[ "${#runtime_issues[@]}" -eq 0 ]]; then
    diagnostic_pass 'Selected streamer resolves its required libevdi SONAME and matching ELF metadata'
  else
    for reason in "${runtime_issues[@]}"; do
      diagnostic_fail "$reason"
      evdi_diagnostic_hint "$reason"
    done
  fi

  echo
  echo '3. Source-build diagnostics (not installed-runtime requirements)'
  if [[ -e "/lib/modules/$kernel/build/Makefile" ]]; then
    diagnostic_pass "Matching kernel headers: /lib/modules/$kernel/build"
  else
    diagnostic_warn "Kernel headers for $kernel are absent; they are needed only when building a module"
    if [[ "$FIX_RECOMMENDATIONS" -eq 1 ]]; then
      echo '  Install headers/devel for the running kernel, or boot the kernel matching your installed headers.'
    fi
  fi
  linkable="$(evdi_find_linkable_library || true)"
  if [[ -n "$linkable" ]]; then
    diagnostic_pass "Source-linker library: $linkable"
  else
    diagnostic_warn 'Unversioned libevdi.so is absent; source builds need its development package or WBEAM_EVDI_LIB_DIR'
  fi
  # A historical compiler failure must not invalidate an already-loaded module.
  if [[ "$module_loaded" -eq 0 && "$module_present" -eq 0 ]]; then
    log_path="$(evdi_find_build_log "$kernel" || true)"
    if [[ -n "$log_path" ]]; then
      reason="$(evdi_build_failure_reason "$log_path")"
      diagnostic_warn "$reason: $log_path"
      if [[ "$FIX_RECOMMENDATIONS" -eq 1 ]]; then
        case "$reason" in
          dkms-kernel-api-sign-compare)
            echo '  Test a compatible EVDI/kernel pair; do not disable global compiler warnings or modify installed kernel headers.' ;;
          dkms-kernel-headers-mismatch)
            echo '  Check the exact running-kernel headers and the matching DKMS build log.' ;;
          *) echo '  Inspect the matching DKMS log before retrying the module build.' ;;
        esac
      fi
    else
      diagnostic_warn "No readable EVDI DKMS make.log found for running kernel $kernel"
    fi
  fi
  if [[ "$VERBOSE" -eq 1 ]] && command -v dkms >/dev/null 2>&1; then
    echo '[INFO] DKMS status:'
    dkms status || diagnostic_warn 'dkms status failed'
  fi

  echo
  echo '4. Secure Boot and configuration'
  if command -v mokutil >/dev/null 2>&1; then
    key_status="$(mokutil --sb-state 2>/dev/null || true)"
    if [[ "$key_status" == *enabled* ]]; then
      printf '[INFO] Secure Boot enabled\n'
      if [[ -f /var/lib/dkms/mok.pub ]]; then
        key_status="$(mokutil --test-key /var/lib/dkms/mok.pub 2>&1 || true)"
        if [[ "$key_status" == *'not enrolled'* ]]; then
          if [[ "$module_loaded" -eq 1 ]]; then
            diagnostic_warn 'DKMS MOK key is not enrolled; loaded EVDI may use a different signing key'
          elif evdi_mok_import_pending /var/lib/dkms/mok.pub; then
            diagnostic_fail 'DKMS MOK enrollment is queued; reboot and complete firmware enrollment'
          else
            diagnostic_fail 'DKMS MOK key is not enrolled; inspect the module signing key before loading EVDI'
          fi
          if [[ "$FIX_RECOMMENDATIONS" -eq 1 && "$module_loaded" -eq 0 ]]; then
            echo '  For the DKMS key: sudo mokutil --import /var/lib/dkms/mok.pub'
            echo '  Then reboot and complete Enroll MOK in firmware with the temporary password.'
          fi
        elif [[ "$key_status" == *'already enrolled'* || "$key_status" == *'is enrolled'* ]]; then
          diagnostic_pass 'DKMS MOK key is enrolled'
        else
          diagnostic_warn 'Cannot determine DKMS MOK enrollment from mokutil output'
        fi
      else
        diagnostic_warn 'No /var/lib/dkms/mok.pub; inspect the signing key used by your EVDI package'
      fi
    elif [[ -n "$key_status" ]]; then
      printf '[INFO] %s\n' "$key_status"
    else
      diagnostic_warn 'Secure Boot state is unavailable'
    fi
  fi
  config_path="${XDG_CONFIG_HOME:-${HOME:-}/.config}/wbeam/wbeam.conf"
  if [[ -f "$config_path" ]]; then
    printf '[INFO] User configuration: %s\n' "$config_path"
    if [[ "$VERBOSE" -eq 1 ]]; then
      awk '/^[[:space:]]*WBEAM_CAPTURE_BACKEND[[:space:]]*=/ {print "[INFO] " $0}' "$config_path"
    fi
  else
    printf '[INFO] No user config; daemon defaults apply unless environment/API overrides them\n'
  fi

  printf '\nSummary: %s runtime error(s), %s warning(s).\n' "$ERRORS" "$WARNINGS"
  echo 'These read-only checks do not test compositor rendering or Android frames.'
  if [[ "$ERRORS" -gt 0 && "$FIX_RECOMMENDATIONS" -eq 0 ]]; then
    echo 'Run with --fix for repair suggestions; no changes will be applied.'
  fi
  return "$ERRORS"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  evdi_diagnose_main "$@"
fi
