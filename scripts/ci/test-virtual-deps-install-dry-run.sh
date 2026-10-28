#!/usr/bin/env bash
# Prove "dry-run" only prints intended commands. It must never prompt or
# execute a package manager, sudo or modprobe.
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
export WBEAM_INSTALL_TEST_MARKER="$tmp/commands-executed"
for command in apt-get sudo modprobe lsmod; do
  cat > "$tmp/bin/$command" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "${0##*/}" >> "$WBEAM_INSTALL_TEST_MARKER"
exit 79
EOF
  chmod +x "$tmp/bin/$command"
done
export PATH="$tmp/bin:$PATH"

# --dry-run implies no interactive confirmation. With stdin closed, a
# prompt would terminate before the expected success message.
output="$(bash "$ROOT_DIR/scripts/virtual-deps-install.sh" --dry-run </dev/null)"
grep -Fq "DRY-RUN:" <<<"$output"
grep -Fq "DRY-RUN completed. No packages were installed" <<<"$output"
if [[ -e "$WBEAM_INSTALL_TEST_MARKER" ]]; then
  echo "[virtual-deps][FAIL] dry-run executed privileged commands" >&2
  cat "$WBEAM_INSTALL_TEST_MARKER" >&2
  exit 1
fi
echo "[virtual-deps][PASS] noninteractive dry-run performs no privileged actions"
