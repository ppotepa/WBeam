#!/usr/bin/env bash

wbeam_has_graphical_env() {
  [[ -n "${WAYLAND_DISPLAY:-}" || -n "${DISPLAY:-}" ]]
}

wbeam_detect_runas_remote_filter() {
  local target_user="$1"
  local sid name type state active remote

  if ! command -v loginctl >/dev/null 2>&1; then
    echo "no"
    return 0
  fi

  while read -r sid _; do
    [[ -n "${sid:-}" ]] || continue
    name="$(loginctl show-session "$sid" -p Name --value 2>/dev/null || true)"
    type="$(loginctl show-session "$sid" -p Type --value 2>/dev/null || true)"
    state="$(loginctl show-session "$sid" -p State --value 2>/dev/null || true)"
    active="$(loginctl show-session "$sid" -p Active --value 2>/dev/null || true)"
    remote="$(loginctl show-session "$sid" -p Remote --value 2>/dev/null || true)"
    if [[ "$name" == "$target_user" && ( "$type" == "x11" || "$type" == "wayland" ) && "$state" == "active" && "$active" == "yes" && "$remote" == "yes" ]]; then
      echo "yes"
      return 0
    fi
  done < <(loginctl list-sessions --no-legend 2>/dev/null | awk '{print $1" "$2}')

  echo "no"
}

wbeam_ensure_graphical_context() {
  local root_dir="$1"
  local script_name="$2"
  local label="$3"
  local auto_reexec_var="$4"
  local reexec_var="$5"
  shift 5
  local -a launch_args=("$@")
  local auto_reexec="${!auto_reexec_var:-1}"
  local reexec_flag="${!reexec_var:-0}"
  local target_user remote_filter runas

  if wbeam_has_graphical_env && [[ "${XDG_SESSION_TYPE:-}" != "tty" ]]; then
    return 0
  fi

  if [[ "$reexec_flag" == "1" ]]; then
    echo "[${label}] failed to enter graphical session context (DISPLAY/WAYLAND missing)." >&2
    echo "[${label}] run './runas-remote <user> ./${script_name}' and verify active GUI session." >&2
    return 1
  fi

  if [[ "$auto_reexec" != "1" ]]; then
    echo "[${label}] no graphical session in current shell (DISPLAY/WAYLAND missing)." >&2
    echo "[${label}] run './runas-remote <user> ./${script_name}' or set ${auto_reexec_var}=1." >&2
    return 1
  fi

  target_user="${WBEAM_DEV_REMOTE_USER:-$(id -un)}"
  remote_filter="${RUNAS_REMOTE_SESSION_REMOTE:-}"
  if [[ -z "${remote_filter}" ]]; then
    remote_filter="$(wbeam_detect_runas_remote_filter "$target_user")"
  fi
  runas="${root_dir}/runas-remote"
  if [[ ! -x "${runas}" ]]; then
    echo "[${label}] missing executable: ${runas}" >&2
    return 1
  fi

  echo "[${label}] no graphical session in current shell; re-launching via runas-remote user=${target_user} remote_filter=${remote_filter}"
  exec env \
    RUNAS_REMOTE_QUIET=1 \
    RUNAS_REMOTE_SESSION_REMOTE="${remote_filter}" \
    "${reexec_var}=1" \
    "${runas}" "${target_user}" "${root_dir}/${script_name}" -- "${launch_args[@]}"
}
