#!/usr/bin/env bash
# Source-level regression for package runtime log locations. Rust unit tests
# cover the resolver's real behavior; the script requires no Cargo.
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
main="$ROOT_DIR/host/rust/crates/wbeamd-server/src/main.rs"
core="$ROOT_DIR/host/rust/crates/wbeamd-core/src/lib.rs"
template="$ROOT_DIR/host/rust/systemd/wbeamd-rust.service.template"
package="$ROOT_DIR/scripts/ci/runtime-assets.sh"

grep -Fq 'root.join("VERSION.txt").is_file()' "$main"
# The Rust core also writes effective-runtime JSONL snapshots. Those must
# obey the same user-state policy as the server's tracing logs.
grep -Fq 'fn resolve_effective_runtime_log_dir(' "$core"
grep -Fq 'self.root.join("VERSION.txt").is_file()' "$core"
grep -Fq 'xdg.join("wbeam/logs/effective-runtime")' "$core"
grep -Fq 'path.join(".local/state/wbeam/logs/effective-runtime")' "$core"
grep -Fq 'fn installed_runtime_snapshots_do_not_fall_back_to_read_only_root()' "$core"
grep -Fq 'let Some(path) = self.effective_runtime_log_path() else {' "$core"
grep -Fq 'fn resolve_daemon_log_dir(' "$main"
grep -Fq 'return Ok(root.join("host/rust/logs"))' "$main"
grep -Fq 'xdg.join("wbeam/logs")' "$main"
grep -Fq 'user_home.join(".local/state/wbeam/logs")' "$main"
grep -Fq 'if let Some(path) = requested {' "$main"
grep -Fq 'Set XDG_STATE_HOME or HOME to an absolute path, or pass --log-dir' "$main"
grep -Fq 'fn installed_runtime_uses_xdg_state_not_read_only_package_root()' "$main"
grep -Fq 'fn installed_runtime_without_writable_state_home_fails_clearly()' "$main"
grep -Fq -- '--root @ROOT@' "$template"
grep -Fq 'install -m 0644 "${DIST_DIR}/VERSION.txt" "$target/VERSION.txt"' "$package"
if grep -Fq 'unwrap_or_else(|| root.join("host/rust/logs"))' "$main"; then
  echo "[daemon-log][FAIL] unconditional repo-relative log path returned" >&2
  exit 1
fi
echo "[daemon-log][PASS] daemon tracing and effective-runtime snapshots resolve to user state"
