#!/usr/bin/env bash

# shellcheck shell=bash

# Load WBeam config keys from:
#  1) ~/.config/wbeam/wbeam.conf (or $XDG_CONFIG_HOME/wbeam/wbeam.conf)
#
# If missing, user config is bootstrapped from <repo>/config/wbeam.conf.
# ENV values already present in the process still win for ad-hoc overrides.
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

wbeam_user_config_path() {
  local user_config_home="${XDG_CONFIG_HOME:-${HOME:-}/.config}"
  if [[ -z "$user_config_home" ]]; then
    return 1
  fi
  printf '%s\n' "${user_config_home}/wbeam/wbeam.conf"
}

wbeam_ensure_user_config() {
  local root_dir="$1"
  local template="${root_dir}/config/wbeam.conf"
  local user_file
  user_file="$(wbeam_user_config_path 2>/dev/null || true)"
  [[ -n "$user_file" ]] || return 0
  if [[ -f "$user_file" ]]; then
    return 0
  fi
  local user_dir
  user_dir="$(dirname "$user_file")"
  mkdir -p "$user_dir" 2>/dev/null || {
    echo "[wbeam-config] WARN: cannot create ${user_dir}" >&2
    return 0
  }
  if [[ -f "$template" ]]; then
    cp "$template" "$user_file" 2>/dev/null || {
      echo "[wbeam-config] WARN: cannot bootstrap ${user_file} from ${template}" >&2
      return 0
    }
  else
    : > "$user_file" 2>/dev/null || {
      echo "[wbeam-config] WARN: cannot create empty ${user_file}" >&2
      return 0
    }
  fi
}

wbeam_load_config() {
  local root_dir="$1"
  local user_file
  wbeam_ensure_user_config "$root_dir"
  user_file="$(wbeam_user_config_path 2>/dev/null || true)"
  if [[ -n "$user_file" ]]; then
    wbeam_conf_apply_file "$user_file"
  fi
}
