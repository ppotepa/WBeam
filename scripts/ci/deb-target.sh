#!/usr/bin/env bash
# Supported binary DEB profiles. A package linked on Debian Bookworm against
# libevdi0 is not assumed ABI-compatible with Ubuntu Noble's libevdi1.
# Sourced by build_deb.sh; functions may also be tested independently.

wbeam_deb_target_for_os() {
  case "${1:-}:${2:-}" in
    debian:12) printf 'bookworm\n' ;;
    ubuntu:24.04) printf 'noble\n' ;;
    *) return 1 ;;
  esac
}

wbeam_deb_evdi_runtime_package() {
  case "$1" in
    bookworm) printf 'libevdi0\n' ;;
    noble) printf 'libevdi1\n' ;;
    *) return 1 ;;
  esac
}

wbeam_deb_evdi_build_package() {
  case "$1" in
    bookworm) printf 'libevdi0-dev\n' ;;
    noble) printf 'libevdi-dev\n' ;;
    *) return 1 ;;
  esac
}

# Check the exact EVDI ELF NEEDED SONAME, not merely any libevdi.so.*.
wbeam_deb_evdi_soname() {
  case "$1" in
    bookworm) printf 'libevdi.so.0\n' ;;
    noble) printf 'libevdi.so.1\n' ;;
    *) return 1 ;;
  esac
}

wbeam_deb_package_version() {
  local version="${1:?WBEAM_VERSION required}"
  local target="${2:?deb target required}"
  [[ "$target" == bookworm || "$target" == noble ]] || return 1
  # Debian '~rc' sorts before final. Distro suffixes create distinct artifact
  # names; the version embedded into binaries/APK remains WBEAM_VERSION.
  version="${version/-rc./~rc.}"
  version="${version/-beta./~beta.}"
  version="${version/-alpha./~alpha.}"
  printf '%s+%s\n' "$version" "$target"
}

# A stable release supports both build ABIs; failing silently if one is
# missing would publish an artifact that cannot install on its target OS.
wbeam_deb_has_both_artifacts() {
  local directory="${1:?artifact directory required}"
  local -a bookworm_files=() noble_files=()
  local f
  for f in "$directory"/wbeam_*+bookworm_*.deb; do
    [[ -e "$f" ]] && bookworm_files+=("$f")
  done
  for f in "$directory"/wbeam_*+noble_*.deb; do
    [[ -e "$f" ]] && noble_files+=("$f")
  done
  (( ${#bookworm_files[@]} == 1 && ${#noble_files[@]} == 1 ))
}
