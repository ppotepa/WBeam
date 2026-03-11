#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENFORCE="${WBEAM_REPO_STRUCTURE_ENFORCE:-0}"
WARN_COUNT=0

info() {
  echo "[repo-structure][info] $*"
}

warn() {
  echo "[repo-structure][warn] $*"
  WARN_COUNT=$((WARN_COUNT + 1))
}

check_app_layout() {
  local app_dir="$1"
  local app_name
  app_name="$(basename "$app_dir")"

  if [[ ! -d "$app_dir" ]]; then
    warn "missing app directory: $app_dir"
    return
  fi

  local has_frontend=0
  local has_backend=0
  local has_legacy_frontend=0
  local has_legacy_backend=0

  [[ -d "$app_dir/frontend" ]] && has_frontend=1
  [[ -d "$app_dir/backend" ]] && has_backend=1
  [[ -d "$app_dir/src" ]] && has_legacy_frontend=1
  [[ -d "$app_dir/src-tauri" ]] && has_legacy_backend=1

  if [[ "$has_legacy_frontend" -eq 1 || "$has_legacy_backend" -eq 1 ]]; then
    warn "$app_name uses legacy layout (src/src-tauri). target is frontend/backend."
  fi

  if [[ "$has_frontend" -eq 1 || "$has_backend" -eq 1 ]]; then
    info "$app_name has new layout markers (frontend/backend)."
  fi
}

check_nested_src_patterns() {
  local nested_tauri
  nested_tauri="$(find "$ROOT_DIR/src/apps" -type d -path "*/src-tauri/src" 2>/dev/null || true)"
  if [[ -n "$nested_tauri" ]]; then
    while IFS= read -r line; do
      [[ -z "$line" ]] && continue
      warn "tauri nested backend src detected: ${line#$ROOT_DIR/}"
    done <<< "$nested_tauri"
  fi

  local legacy_front
  legacy_front="$(find "$ROOT_DIR/src/apps" -mindepth 2 -maxdepth 2 -type d -name src 2>/dev/null || true)"
  if [[ -n "$legacy_front" ]]; then
    while IFS= read -r line; do
      [[ -z "$line" ]] && continue
      warn "legacy frontend path detected: ${line#$ROOT_DIR/} (target: frontend/)"
    done <<< "$legacy_front"
  fi
}

check_experimental_lanes() {
  for lane in proto proto_x11; do
    if [[ -d "$ROOT_DIR/$lane" ]]; then
      warn "experimental lane present at top-level: $lane (consider archive/experimental)"
    fi
  done
}

main() {
  info "checking repository structure contract..."
  check_app_layout "$ROOT_DIR/src/apps/desktop-tauri"
  check_app_layout "$ROOT_DIR/src/apps/trainer-tauri"
  check_nested_src_patterns
  check_experimental_lanes

  if [[ "$WARN_COUNT" -eq 0 ]]; then
    info "structure check passed without warnings."
    exit 0
  fi

  info "structure check finished with $WARN_COUNT warning(s)."
  if [[ "$ENFORCE" == "1" ]]; then
    echo "[repo-structure][error] enforce mode is enabled (WBEAM_REPO_STRUCTURE_ENFORCE=1)."
    exit 1
  fi

  info "warn mode active; build is not blocked."
  exit 0
}

main "$@"
