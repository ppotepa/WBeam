# EVDI compatibility and evidence

This table records validation status, not a list of all installable packages.
An upstream release, successful link, or `modinfo` result does not prove a working
WBeam stream. A PASS needs evidence for the exact kernel, driver, userspace ABI
and Android device.

| Configuration | Evidence recorded here | Status |
| --- | --- | --- |
| Fedora 43 / kernel 6.19.11 / GCC 15 | Historical report [#96](https://github.com/ppotepa/WBeam/issues/96), tracked by [#120](https://github.com/ppotepa/WBeam/issues/120): DKMS sign-compare failure and separate missing `-levdi` link failure. | Reported failure; reproduction and a working driver/kernel pair still need linked evidence. |
| Fedora 43 / another kernel | No passing kernel/driver/device record linked in this matrix. | Pending acceptance. |
| Ubuntu 24.04 / actual running kernel | No passing kernel/driver/device record linked in this matrix. | Pending acceptance. |

Use the [release checklist](release-checklist.md) for the authoritative acceptance
criteria. Do not infer compatibility for other kernels, distributions or
architectures from these targets.

Issue #120 was [closed administratively on 2026-11-05](https://github.com/ppotepa/WBeam/issues/120#issuecomment-6654581413)
with CI deferred and Fedora/Android hardware checks unperformed. Its closed
state is not evidence that the reported compatibility problem was fixed.

## Collect a preflight

From the repository root:

```bash
uname -r
dkms status
modinfo evdi
lsmod | grep '^evdi '
mokutil --sb-state
bash scripts/virtual-deps-check.sh --json --backend evdi
bash scripts/evdi-diagnose.sh --verbose
```

On Fedora, also collect `rpm -q kernel-core kernel-devel dkms akmod-evdi evdi-dkms`;
missing-package results are part of the evidence. Collect the matching
`/var/lib/dkms/evdi/<version>/<kernel>/<arch>/log/make.log` and relevant
`journalctl -k` output. Do not attribute a log from another kernel to the running
module.

The shared helper [`evdi-compat.sh`](../scripts/evdi-compat.sh) classifies:

| Diagnostic | Meaning |
| --- | --- |
| `dkms-kernel-api-sign-compare` | Compiler reports signedness comparison errors; reproduce with exact driver source and kernel headers. |
| `dkms-kernel-headers-mismatch` | Build log indicates missing/mismatched headers or files; inspect the actual log. |
| `dkms-build-failed-other` | Another build error needs investigation. |
| `dkms-failure-unclassified` | No recognized signature; retain the full log. |

These are diagnostic hints, not a compatibility verdict. Do not suppress global
compiler warnings or modify installed kernel headers to make a release gate pass.

## Keep build and runtime checks separate

Building a missing module requires matching kernel headers. Building an EVDI
streamer requires unversioned `libevdi.so` for `-levdi`. Running a prebuilt
streamer requires its matching SONAME library, not headers or a development
symlink. Debian 12 packages link `libevdi.so.0`; Ubuntu 24.04 packages link
`libevdi.so.1`. These ABIs require separate binaries/package metadata (see
[#144](https://github.com/ppotepa/WBeam/issues/144)).

The runtime dependency checker accepts either library's presence and does not
prove that it matches a given binary. Check `ldd` on the actual streamer, active
user DRM permissions and real capture separately. The fixture
[`test-virtual-deps-runtime.sh`](../scripts/ci/test-virtual-deps-runtime.sh)
verifies that missing build-only inputs do not block an already-loaded runtime;
it is not a hardware test.

## Evidence needed for PASS

- Exact distribution, kernel, compiler, EVDI source revision/package and module
  signing status; record a pinned version, not a floating upstream `master`.
- DKMS build/load results, library resolution and EVDI device access for the
  user running WBeam.
- Package/source build result with EVDI enabled.
- A real Android image stream for at least 60 seconds, followed by the
  30-minute soak required by the release checklist.
- Results for missing headers/library, pending MOK enrollment and known kernel
  compiler failures, with useful diagnostics.

Keep failing combinations and their logs in the record. If a compatible EVDI
pair is unavailable, Wayland Portal is a separate path to validate. X11 remains
legacy code and is not the supported automatic fallback.
