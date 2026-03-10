#!/usr/bin/env bash

# shellcheck shell=bash

# Load WBeam config keys from:
#  1) WBEAM_CONFIG_FILE (optional, highest config priority)
#  2) ~/.config/wbeam/wbeam.conf (or $XDG_CONFIG_HOME/wbeam/wbeam.conf)
#  3) <repo>/.wbeam.conf
#  4) <repo>/config/wbeam.conf
#
# ENV values already present in the process always win over config files.
# INI-like section headers ([service], [android], ...) are accepted and ignored.

wbeam_conf_trim() {
  local s="${1-}"
  s="${s#"${s%%[![:space:]]*}"}"
  s="${s%"${s##*[![:space:]]}"}"
  printf '%s' "$s"
}

wbeam_conf_unquote() {
  local s="${1-}"
  local len=${#s}
  if (( len >= 2 )); then
    local first="${s:0:1}"
    local last="${s:len-1:1}"
    if [[ ( "$first" == '"' && "$last" == '"' ) || ( "$first" == "'" && "$last" == "'" ) ]]; then
      s="${s:1:len-2}"
    fi
  fi
  printf '%s' "$s"
}

wbeam_conf_apply_file() {
  local file="$1"
  local line key value
  [[ -f "$file" ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="$(wbeam_conf_trim "$line")"
    [[ -n "$line" ]] || continue
    [[ "$line" == \#* ]] && continue
    [[ "$line" == *=* ]] || continue
    key="$(wbeam_conf_trim "${line%%=*}")"
    value="$(wbeam_conf_trim "${line#*=}")"
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    [[ "$key" == WBEAM_* || "$key" == RUNAS_REMOTE_* ]] || continue
    if [[ -v "$key" ]]; then
      continue
    fi
    value="$(wbeam_conf_unquote "$value")"
    printf -v "$key" '%s' "$value"
    export "$key"
  done < "$file"
}

wbeam_load_config() {
  local root_dir="$1"
  local user_config_home="${XDG_CONFIG_HOME:-${HOME:-}/.config}"
  local user_file="${user_config_home}/wbeam/wbeam.conf"
  local repo_local="${root_dir}/.wbeam.conf"
  local repo_default="${root_dir}/config/wbeam.conf"

  if [[ -n "${WBEAM_CONFIG_FILE:-}" ]]; then
    wbeam_conf_apply_file "$WBEAM_CONFIG_FILE"
  fi
  wbeam_conf_apply_file "$user_file"
  wbeam_conf_apply_file "$repo_local"
  wbeam_conf_apply_file "$repo_default"
}
