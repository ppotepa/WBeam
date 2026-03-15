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

wbeam_apply_tauri_stability_env() {
  local label="$1"
  local xa=""

  export WEBKIT_DISABLE_DMABUF_RENDERER="${WEBKIT_DISABLE_DMABUF_RENDERER:-1}"

  if [[ "${XDG_SESSION_TYPE:-}" == "wayland" && "${WBEAM_TAURI_NATIVE_WAYLAND:-0}" != "1" ]]; then
    if [[ -n "${DISPLAY:-}" ]]; then
      export GDK_BACKEND="${GDK_BACKEND:-x11}"
      export WINIT_UNIX_BACKEND="${WINIT_UNIX_BACKEND:-x11}"
      echo "[${label}] wayland detected with DISPLAY available; forcing x11 backend for Tauri stability."
    else
      export GDK_BACKEND="${GDK_BACKEND:-wayland}"
      export WINIT_UNIX_BACKEND="${WINIT_UNIX_BACKEND:-wayland}"
      echo "[${label}] wayland-only session detected (DISPLAY missing); using native wayland backend."
    fi
  fi

  if [[ "${GDK_BACKEND:-}" == "x11" && -z "${XAUTHORITY:-}" ]]; then
    if [[ -n "${XDG_RUNTIME_DIR:-}" ]]; then
      for candidate in "${XDG_RUNTIME_DIR}"/xauth_*; do
        [[ -f "${candidate}" ]] || continue
        xa="${candidate}"
        break
      done
    fi
    if [[ -z "${xa}" && -n "${HOME:-}" && -f "${HOME}/.Xauthority" ]]; then
      xa="${HOME}/.Xauthority"
    fi
    if [[ -n "${xa}" ]]; then
      export XAUTHORITY="${xa}"
    fi
  fi
}

# ─── Canonical loginctl GUI session resolver ────────────────────────────────
#
# Outputs the username of the first active GUI session that matches
# the given remote filter ("any" | "yes" | "no").
# Returns 0 + user on success, 1 + no output when none found.
#
# Usage: user="$(wbeam_resolve_active_gui_user any)"
#
wbeam_resolve_active_gui_user() {
  local remote_filter="${1:-any}"
  local sid name type state active remote

  while read -r sid _; do
    [[ -n "${sid:-}" ]] || continue
    name="$(loginctl show-session "$sid" -p Name --value 2>/dev/null || true)"
    type="$(loginctl show-session "$sid" -p Type --value 2>/dev/null || true)"
    state="$(loginctl show-session "$sid" -p State --value 2>/dev/null || true)"
    active="$(loginctl show-session "$sid" -p Active --value 2>/dev/null || true)"
    remote="$(loginctl show-session "$sid" -p Remote --value 2>/dev/null || true)"
    [[ -n "$name" && "$name" != "root" ]] || continue
    [[ "$active" == "yes" && "$state" == "active" ]] || continue
    [[ "$type" == "x11" || "$type" == "wayland" ]] || continue
    case "$remote_filter" in
      yes) [[ "${remote,,}" == "yes" ]] || continue ;;
      no)  [[ "${remote,,}" == "no"  ]] || continue ;;
      *)   ;;  # any: no remote filter
    esac
    echo "$name"
    return 0
  done < <(loginctl list-sessions --no-legend 2>/dev/null | awk '{print $1" "$2}')
  return 1
}
