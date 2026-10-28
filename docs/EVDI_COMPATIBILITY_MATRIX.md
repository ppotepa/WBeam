# EVDI compatibility matrix — WBeam release 0.1.3

**Status: unverified until physical/VM Fedora validation and Android frame
acceptance.** The presence of an upstream EVDI release does not prove it
compiles against every kernel API. Never declare a kernel supported based only
on `modinfo` or a successful binary link.

| Host/kernel | EVDI build/load | libevdi link | Real stream | Release status |
| --- | --- | --- | --- | --- |
| Fedora 43 / 6.19.11 / GCC 15 (issue #96) | Reported failing with sign-compare/Werror; reproduction required | Reported missing `-levdi`; investigate separately | Not tested | **UNKNOWN / release gate blocked** |
| Fedora 43 / current tested kernel | Requires driver-specific verification | Requires unversioned `libevdi.so` | Pending real Android test | **PENDING** |
| Ubuntu 24.04 / actual running kernel | Pending EVDI/DKMS/MOK check | Pending | Pending | **PENDING** |

## Run the non-mutating preflight

```bash
uname -r
rpm -q kernel-core kernel-devel dkms akmod-evdi evdi-dkms 2>&1 || true
dkms status || true
modinfo evdi || true
lsmod | grep '^evdi ' || true
mokutil --sb-state || true
bash scripts/evdi-diagnose.sh --verbose
bash scripts/virtual-deps-check.sh --json --backend evdi
```

For the reported Fedora 43 kernel 6.19.11, collect the **matching**
`/var/lib/dkms/evdi/<version>/<uname-r>/<arch>/log/make.log` and
`journalctl -k` output. The helper `scripts/evdi-compat.sh` labels
sign-compare/compiler API failures as `dkms-kernel-api-sign-compare`;
missing header files as `dkms-kernel-headers-mismatch`; other DKMS build
errors separately. Do not mistakenly attribute an old DKMS log from a
different kernel to the current module.

## Runtime vs build requirements

The desktop's **EVDI Host setup** is a *runtime readiness* probe, not a
development-toolchain audit. If EVDI is already loaded, it must not require
kernel headers for the running kernel. If the distributed streamer can load
the ABI library `libevdi.so.1`, it must not require the unversioned
`libevdi.so` symlink that only a development package normally supplies.

Use `scripts/virtual-deps-check.sh --json --backend evdi` to verify runtime
readiness: loaded module, runtime SONAME, and actionable failure details.
The script now also returns a nonzero exit code when `ok=false`, while
keeping the same JSON schema for the desktop doctor.

**Building EVDI from source/RPM** still requires matching kernel headers
if DKMS must compile a missing module, and the unversioned linker
`libevdi.so` for `-levdi`. Keep this separate in
`scripts/ci/build_rpm.sh` and `scripts/evdi-diagnose.sh`. The
`scripts/ci/test-virtual-deps-runtime.sh` fixture verifies that a loaded
module and runtime library can pass even when headers/development symlinks
are absent.

## Distinguish failures

1. **DKMS compile error**: e.g. `-Werror=sign-compare` from changed kernel
   headers. Reproduce with the exact EVDI source revision and kernel. Find a
   compatible upstream package/patch; **never suppress compiler warnings
   globally or modify installed kernel headers as a release workaround**.
2. **Module load/Secure Boot error**: DKMS may compile but `modprobe evdi`
   fails. Inspect MOK enrollment and module signing. Key enrollment requires
   manual confirmation and a reboot.
3. **Rust linker `cannot find -levdi`**: EVDI streamer needs an unversioned
   `libevdi.so` in a linker search directory. Fedora DisplayLink packages
   may place it under `/usr/libexec/displaylink`. Provide a compatible
   package or set `WBEAM_EVDI_LIB_DIR` for the build; this is independent of
   the kernel module build.
4. **Device/permission issue**: `modinfo evdi` succeeds but EVDI is not
   loaded or lacks usable DRM devices/permission. Diagnose the running user,
   `/dev/dri`, active session and group membership; do not mark capture
   ready solely from the package existing.
5. **Actual stream failure**: the module may load and the library may link
   but EVDI can still fail to render frames. Run the Android test matrix in
   `docs/release-checklist.md`.

For kernels that cannot use EVDI, **Wayland Portal is a separate supported
path when available on the host.** Do not silently fall back to legacy X11.

## Candidate upstream versions

The `displaylink-rpm/displaylink-rpm` project shows an EVDI 1.15.1
packaging revision in September 2026:
https://github.com/displaylink-rpm/displaylink-rpm/blob/master/displaylink.spec

This is a **candidate to reproduce/test**, not proof of compatibility with
Fedora 43 kernel 6.19.x. Only after passing signed RPM and hardware tests
should the matrix change from PENDING to PASS.

## Evidence required before closing #120

- Exact distro/kernel/compiler, EVDI source/release, module signing status.
- DKMS build log, `dkms status`, `modinfo`, `lsmod`, sysfs DRM nodes.
- Linkable library resolution and result of the RPM build with EVDI feature.
- One true 60-second Android image stream and 30-minute soak on declared
  supported Fedora configuration.
- Test of negative cases: missing kernel headers, missing linkable library,
  Secure Boot/MOK pending and kernel API signedness error.
- Known incompatible combinations recorded as such, with Portal documented
  as an alternative rather than an unsupported X11 path.
