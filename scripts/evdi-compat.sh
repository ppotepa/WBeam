#!/usr/bin/env bash
# Read-only EVDI diagnostic helpers shared by CLI and desktop Host setup.
# No DKMS builds, kernel patching, reboot or privilege escalation here.

evdi_find_linkable_library() {
  local directory candidate
  if [[ -n "${WBEAM_EVDI_LIB_DIR:-}" ]]; then
    candidate="${WBEAM_EVDI_LIB_DIR}/libevdi.so"
    [[ -f "$candidate" ]] && { printf '%s\n' "$candidate"; return 0; }
    return 1
  fi
  for directory in /usr/libexec/displaylink /usr/lib64 /usr/lib \
    /usr/local/lib64 /usr/local/lib; do
    candidate="$directory/libevdi.so"
    if [[ -f "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

evdi_find_build_log() {
  local kernel="$1" file
  # Prefer a DKMS log built for exactly the running kernel.
  for file in /var/lib/dkms/evdi/*/"$kernel"/*/log/make.log \
    /var/lib/dkms/evdi/*/build/make.log; do
    [[ -f "$file" && -r "$file" ]] || continue
    printf '%s\n' "$file"
    return 0
  done
  return 1
}

evdi_build_failure_reason() {
  local log_file="$1"
  [[ -f "$log_file" && -r "$log_file" ]] || {
    printf 'dkms-log-not-readable\n'
    return 0
  }
  if grep -Eqi '(Werror=sign-compare|comparison of integer expressions of different signedness)' "$log_file"; then
    printf 'dkms-kernel-api-sign-compare\n'
  elif grep -Eqi '(kernel-devel|linux-headers|No such file or directory|kernel headers)' "$log_file"; then
    printf 'dkms-kernel-headers-mismatch\n'
  elif grep -Eqi '(error:|fatal error:|make.*Error)' "$log_file"; then
    printf 'dkms-build-failed-other\n'
  else
    printf 'dkms-failure-unclassified\n'
  fi
}
