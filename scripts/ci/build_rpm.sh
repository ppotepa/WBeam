#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

RPM_VERSION="${WBEAM_VERSION}"
RPM_RELEASE="1"
TOPDIR="$(mktemp -d)"
trap 'rm -rf "${TOPDIR}"' EXIT

echo "[build_rpm] Building Rust binaries..."
cargo build --release -p wbeamd-server -p wbeamd-streamer --manifest-path "${RUST_MANIFEST}"
write_version_manifest

mkdir -p "${TOPDIR}/"{BUILD,BUILDROOT,RPMS,SOURCES,SPECS,SRPMS}

SRCROOT="${TOPDIR}/wbeam-${RPM_VERSION}"
mkdir -p "${SRCROOT}"
install -m 0755 "${ROOT_DIR}/wbeam" "${SRCROOT}/wbeam"
install -m 0755 "${ROOT_DIR}/src/host/rust/target/release/wbeamd-server" "${SRCROOT}/wbeamd-server"
install -m 0755 "${ROOT_DIR}/src/host/rust/target/release/wbeamd-streamer" "${SRCROOT}/wbeamd-streamer"
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
install -m 0644 README.md %{buildroot}/usr/share/doc/wbeam/README.md

%files
/usr/local/bin/wbeam
/usr/local/bin/wbeamd-server
/usr/local/bin/wbeamd-streamer
/usr/share/doc/wbeam/README.md

%changelog
* Tue Mar 10 2026 WBeam CI <ci@example.local> - ${RPM_VERSION}-${RPM_RELEASE}
- Automated build from GitLab CI
EOF

rpmbuild -bb --define "_topdir ${TOPDIR}" "${TOPDIR}/SPECS/wbeam.spec"
find "${TOPDIR}/RPMS" -type f -name '*.rpm' -print -exec cp -v {} "${DIST_DIR}/" \;
