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
    /usr/lib/x86_64-linux-gnu /usr/lib/aarch64-linux-gnu \
    /usr/local/lib64 /usr/local/lib; do
    candidate="$directory/libevdi.so"
    if [[ -f "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  if command -v ldconfig >/dev/null 2>&1; then
    candidate="$(ldconfig -p 2>/dev/null | awk '$1 == "libevdi.so" {print $NF; exit}' || true)"
    [[ -n "$candidate" && -f "$candidate" ]] && { printf '%s\n' "$candidate"; return 0; }
  fi
  return 1
}

# Runtime packaging needs the SONAME library, not the unversioned linker
# symlink that is normally provided by libevdi development packages.
# Keep evdi_find_linkable_library() for RPM/build preflight only.
evdi_find_runtime_library() {
  local required="${1:-}" directory library resolved
  local names=(libevdi.so.0 libevdi.so.1 libevdi.so)
  if [[ -n "$required" ]]; then
    [[ "$required" =~ ^libevdi\.so(\.[0-9]+)*$ ]] || return 1
    names=("$required")
  fi
  if [[ -n "${WBEAM_EVDI_LIB_DIR:-}" ]]; then
    # Debian 12 provides libevdi.so.0; Ubuntu 24.04 provides
    # libevdi.so.1. Neither requires an unversioned dev symlink at runtime.
    for library in "${names[@]}"; do
      if [[ -f "${WBEAM_EVDI_LIB_DIR}/$library" ]]; then
        printf '%s\n' "${WBEAM_EVDI_LIB_DIR}/$library"
        return 0
      fi
    done
    return 1
  fi

  for directory in /usr/libexec/displaylink /usr/lib64 /usr/lib \
    /usr/lib/x86_64-linux-gnu /usr/lib/aarch64-linux-gnu \
    /usr/local/lib64 /usr/local/lib; do
    for library in "${names[@]}"; do
      if [[ -f "$directory/$library" ]]; then
        printf '%s\n' "$directory/$library"
        return 0
      fi
    done
  done

  if command -v ldconfig >/dev/null 2>&1; then
    resolved="$(ldconfig -p 2>/dev/null | awk -v required="$required" '(required != "" && $1 == required) || (required == "" && ($1 == "libevdi.so.0" || $1 == "libevdi.so.1" || $1 == "libevdi.so")) {print $NF; exit}' || true)"
    if [[ -n "$resolved" && -f "$resolved" ]]; then
      printf '%s\n' "$resolved"
      return 0
    fi
  fi
  return 1
}

# Use sysfs ancestry, not the DRM card number, to identify EVDI devices.
# Explicit path arguments also allow fixtures without touching the host kernel.
evdi_find_drm_devices() {
  local platform_root="${1:-/sys/bus/platform/devices}" dri_root="${2:-/dev/dri}"
  local device card name found=0
  for device in "$platform_root"/evdi*; do
    [[ -d "$device/drm" ]] || continue
    for card in "$device"/drm/card[0-9]*; do
      name="${card##*/}"
      [[ "$name" =~ ^card[0-9]+$ && -c "$dri_root/$name" ]] || continue
      printf '%s\n' "$dri_root/$name"
      found=1
    done
  done
  [[ "$found" -eq 1 ]]
}

evdi_device_accessible() {
  [[ -r "$1" && -w "$1" ]]
}

evdi_drm_runtime_issues() {
  local card found=0 accessible=0
  while IFS= read -r card; do
    [[ -n "$card" ]] || continue
    found=1
    if evdi_device_accessible "$card"; then accessible=1; fi
  done < <(evdi_find_drm_devices || true)
  if [[ "$found" -eq 0 ]]; then
    printf 'evdi-drm-device\n'
  elif [[ "$accessible" -eq 0 ]]; then
    printf 'evdi-drm-access\n'
  fi
}

evdi_selected_streamer() {
  local root="$1" configured="${WBEAM_RUST_STREAMER_BIN:-}"
  # Read the same user override as the daemon without bootstrapping/writing it.
  if [[ -z "$configured" && -f "$root/host/scripts/wbeam_config.sh" ]]; then
    configured="$(
      source "$root/host/scripts/wbeam_config.sh"
      wbeam_conf_read_key "${XDG_CONFIG_HOME:-${HOME:-}/.config}/wbeam/wbeam.conf" WBEAM_RUST_STREAMER_BIN
    )"
  fi
  if [[ -n "$configured" ]]; then
    printf '%s\n' "$configured"
  elif [[ -e "$root/host/rust/target/release/wbeamd-streamer" ]]; then
    printf '%s\n' "$root/host/rust/target/release/wbeamd-streamer"
  elif [[ -e /usr/local/bin/wbeamd-streamer ]]; then
    printf '/usr/local/bin/wbeamd-streamer\n'
  else
    printf '%s\n' "$root/host/rust/target/release/wbeamd-streamer"
  fi
}

evdi_streamer_runtime_issues() {
  local root="$1" streamer links link_status=0 line required resolved actual
  local dep_pattern='^[[:space:]]*(libevdi\.so(\.[0-9]+)*)[[:space:]]+=>[[:space:]]+(.+)[[:space:]]+\(0x[[:xdigit:]]+\)'
  streamer="$(evdi_selected_streamer "$root")"
  if [[ ! -f "$streamer" || ! -x "$streamer" ]]; then
    printf 'streamer-binary-missing\n'
    # Still report missing runtime libraries while a binary is being prepared.
    evdi_find_runtime_library >/dev/null || printf 'libevdi-runtime-library\n'
    return 0
  fi
  if ! command -v ldd >/dev/null 2>&1; then
    printf 'streamer-linker-check-unavailable\n'
    return 0
  fi
  links="$(LC_ALL=C ldd "$streamer" 2>&1)" || link_status=$?
  line="$(awk '$1 ~ /^libevdi\.so(\.[0-9]+)*$/ {print; exit}' <<<"$links")"
  if [[ -z "$line" ]]; then
    if [[ "$link_status" -ne 0 ]]; then
      printf 'streamer-linker-check-failed\n'
    else
      printf 'streamer-without-evdi\n'
    fi
    return 0
  fi
  required="$(awk '{print $1}' <<<"$line")"
  if [[ "$line" == *'=> not found'* ]]; then
    printf 'libevdi-runtime-library:%s\n' "$required"
    return 0
  fi
  if [[ ! "$line" =~ $dep_pattern ]]; then
    printf 'streamer-linker-check-failed\n'
    return 0
  fi
  resolved="${BASH_REMATCH[3]}"
  # Trim trailing whitespace without changing spaces within the library path.
  resolved="${resolved%"${resolved##*[![:space:]]}"}"
  if [[ ! -r "$resolved" ]]; then
    printf 'libevdi-runtime-library:%s\n' "$required"
    return 0
  fi
  if command -v readelf >/dev/null 2>&1; then
    if ! actual="$(LC_ALL=C readelf -d "$resolved" 2>/dev/null)"; then
      printf 'libevdi-elf-inspection-failed\n'
      return 0
    fi
    actual="$(awk '/\(SONAME\)/ {gsub(/^\[|\]$/, "", $NF); print $NF; exit}' <<<"$actual")"
  elif command -v objdump >/dev/null 2>&1; then
    if ! actual="$(LC_ALL=C objdump -p "$resolved" 2>/dev/null)"; then
      printf 'libevdi-elf-inspection-failed\n'
      return 0
    fi
    actual="$(awk '$1 == "SONAME" {print $2; exit}' <<<"$actual")"
  else
    printf 'libevdi-elf-inspector-missing\n'
    return 0
  fi
  if [[ "$actual" != "$required" ]]; then
    printf 'libevdi-abi-mismatch:%s\n' "$required"
  fi
  if [[ "$link_status" -ne 0 || "$links" == *'=> not found'* ]]; then
    printf 'streamer-runtime-libraries\n'
  fi
}

evdi_find_build_log() {
  local kernel="$1" file
  # Prefer a DKMS log built for exactly the running kernel.
  for file in /var/lib/dkms/evdi/*/"$kernel"/*/log/make.log; do
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
