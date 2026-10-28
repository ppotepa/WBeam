# Android release signing / WBeam 0.1.3

Release APKs **must not use Android's debug keystore**. Do not commit keystores,
passwords, export certificates with private keys, or CI secret values.

## Required protected CI environment

Provide a signing keystore as a protected **file variable**, and these variables
to the GitLab release pipeline:

| Environment variable | Meaning |
| --- | --- |
| `WBEAM_ANDROID_KEYSTORE_PATH` | Absolute path to protected keystore file |
| `WBEAM_ANDROID_STORE_PASSWORD` | Keystore password (secret) |
| `WBEAM_ANDROID_KEY_ALIAS` | Signing key alias |
| `WBEAM_ANDROID_KEY_PASSWORD` | Signing key password (secret) |
| `WBEAM_VERSION_CODE` | Positive, increasing integer above 1 (optional in CI) |

`scripts/ci/build_apk_release.sh` derives `WBEAM_VERSION_CODE` as
`10000 + CI_PIPELINE_IID` when it is absent in CI. CI_PIPELINE_IID increases
within a GitLab project; if artifacts are published by a different project,
maintain a globally monotonic code yourself. Manual release builds must supply
`WBEAM_VERSION_CODE`. The `versionName` is the exact `WBEAM_VERSION`/build
revision supplied by the pipeline. Host and Android must match.

Debug builds remain available without any signing secrets. A release build
fails if any release signing field or versionCode is missing/invalid.
The pipeline runs `apksigner verify --print-certs` and refuses a debug
certificate. Publish checksums together with every release.

## Existing debug-signed installation

Android refuses an in-place update if certificates differ, regardless of
`versionCode`. **Do not silently uninstall the previous app.** WBeam's
installed-mode CLI now refuses an incompatible signature without removing
application data. Source/developer CLI also requires explicit
`WBEAM_ALLOW_DATA_DELETING_REINSTALL=1` before destructive reinstall.

Users should export whatever app settings/data they need **before** explicitly
uninstalling the previous debug-signed APK and installing the new
release-signed APK. A lossless migration is not guaranteed; do not claim one
until a verified export/import mechanism exists.

## Verification checklist

1. Run `scripts/ci/check-version-consistency.sh`.
2. Run Gradle debug tests without any release secrets.
3. Run a release build with protected secrets and an increasing versionCode.
4. Check `apksigner verify --print-certs <release.apk>`.
5. Install release A and update to release B signed by the **same key**,
   confirming unchanged package ID and preserved user settings.
6. Verify host daemon and APK build revisions match at runtime.

Do not copy signing values to logs, PR descriptions or issues.
