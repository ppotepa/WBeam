#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

RPM_VERSION="${WBEAM_VERSION}"
RPM_RELEASE="1"
TOPDIR="$(mktemp -d)"
trap 'rm -rf "${TOPDIR}"' EXIT

echo "[build_rpm] Building Rust binaries..."
cargo build --release -p wbeamd-server -p wbeamd-streamer --manifest-path "${RUST_MANIFEST}"
echo "[build_rpm] Building desktop Tauri app..."
(
  cd "${ROOT_DIR}/desktop/apps/desktop-tauri"
  npm ci
  npm run build
  cargo build --release --manifest-path src-tauri/Cargo.toml
)
write_version_manifest

mkdir -p "${TOPDIR}/"{BUILD,BUILDROOT,RPMS,SOURCES,SPECS,SRPMS}

SRCROOT="${TOPDIR}/wbeam-${RPM_VERSION}"
mkdir -p "${SRCROOT}"
mkdir -p "${SRCROOT}/share/wbeam/host" "${SRCROOT}/share/wbeam/config" "${SRCROOT}/share/wbeam/host/rust/systemd" "${SRCROOT}/share/wbeam/host/rust/scripts"
install -m 0755 "${ROOT_DIR}/wbeam" "${SRCROOT}/share/wbeam/wbeam"
cp -a "${ROOT_DIR}/host/scripts" "${SRCROOT}/share/wbeam/host/"
install -m 0644 "${ROOT_DIR}/host/rust/systemd/wbeamd-rust.service.template" "${SRCROOT}/share/wbeam/host/rust/systemd/wbeamd-rust.service.template"
install -m 0755 "${ROOT_DIR}/host/rust/scripts/install_systemd_user.sh" "${SRCROOT}/share/wbeam/host/rust/scripts/install_systemd_user.sh"
install -m 0644 "${ROOT_DIR}/config/wbeam.conf" "${SRCROOT}/share/wbeam/config/wbeam.conf"
cat > "${SRCROOT}/wbeam" <<'EOF'
#!/usr/bin/env bash
export WBEAM_ROOT="${WBEAM_ROOT:-/usr/share/wbeam}"
exec "${WBEAM_ROOT}/wbeam" "$@"
EOF
chmod 0755 "${SRCROOT}/wbeam"
install -m 0755 "${ROOT_DIR}/host/rust/target/release/wbeamd-server" "${SRCROOT}/wbeamd-server"
install -m 0755 "${ROOT_DIR}/host/rust/target/release/wbeamd-streamer" "${SRCROOT}/wbeamd-streamer"
install -m 0755 "${ROOT_DIR}/desktop/apps/desktop-tauri/src-tauri/target/release/wbeam-desktop-tauri" "${SRCROOT}/wbeam-desktop-tauri"
cat > "${SRCROOT}/wbeam-desktop" <<'EOF'
#!/usr/bin/env bash
export WBEAM_ROOT="${WBEAM_ROOT:-/usr/share/wbeam}"
exec /usr/local/bin/wbeam-desktop-tauri "$@"
EOF
chmod 0755 "${SRCROOT}/wbeam-desktop"
install -m 0644 "${ROOT_DIR}/README.md" "${SRCROOT}/README.md"
tar -C "${TOPDIR}" -czf "${TOPDIR}/SOURCES/wbeam-${RPM_VERSION}.tar.gz" "wbeam-${RPM_VERSION}"

cat > "${TOPDIR}/SPECS/wbeam.spec" <<EOF
%global debug_package %{nil}
%global __debug_install_post %{nil}
Name:           wbeam
Version:        ${RPM_VERSION}
Release:        ${RPM_RELEASE}%{?dist}
Summary:        WBeam host tools and daemon binaries
License:        MIT
URL:            https://github.com/ppotepa/WBeam
Source0:        %{name}-%{version}.tar.gz
BuildArch:      %{_arch}

%description
WBeam host runtime package built by GitLab CI.

%prep
%setup -q

%build
:

%install
mkdir -p %{buildroot}/usr/local/bin
mkdir -p %{buildroot}/usr/share/doc/wbeam
install -m 0755 wbeam %{buildroot}/usr/local/bin/wbeam
install -m 0755 wbeamd-server %{buildroot}/usr/local/bin/wbeamd-server
install -m 0755 wbeamd-streamer %{buildroot}/usr/local/bin/wbeamd-streamer
install -m 0755 wbeam-desktop-tauri %{buildroot}/usr/local/bin/wbeam-desktop-tauri
install -m 0755 wbeam-desktop %{buildroot}/usr/local/bin/wbeam-desktop
mkdir -p %{buildroot}/usr/share/wbeam
cp -a share/wbeam/. %{buildroot}/usr/share/wbeam/
install -m 0644 README.md %{buildroot}/usr/share/doc/wbeam/README.md

%files
/usr/local/bin/wbeam
/usr/local/bin/wbeamd-server
/usr/local/bin/wbeamd-streamer
/usr/local/bin/wbeam-desktop-tauri
/usr/local/bin/wbeam-desktop
/usr/share/wbeam/wbeam
/usr/share/wbeam/config/wbeam.conf
/usr/share/wbeam/host/scripts
/usr/share/wbeam/host/rust/scripts/install_systemd_user.sh
/usr/share/wbeam/host/rust/systemd/wbeamd-rust.service.template
/usr/share/doc/wbeam/README.md

%changelog
* Tue Mar 10 2026 WBeam CI <ci@example.local> - ${RPM_VERSION}-${RPM_RELEASE}
- Automated build from GitLab CI
EOF

rpmbuild -bb --define "_topdir ${TOPDIR}" "${TOPDIR}/SPECS/wbeam.spec"
find "${TOPDIR}/RPMS" -type f -name '*.rpm' -print -exec cp -v {} "${DIST_DIR}/" \;
