# Android release signing

Release APKs require a release keystore. Debug builds need no signing secrets.
Signing is one part of the [release acceptance process](release-checklist.md).

## Build inputs

Configure these protected GitLab CI variables; use a **file variable** for the
keystore and keep passwords masked:

| Variable | Meaning |
| --- | --- |
| `WBEAM_ANDROID_KEYSTORE_PATH` | Absolute path to the readable keystore file |
| `WBEAM_ANDROID_STORE_PASSWORD` | Keystore password |
| `WBEAM_ANDROID_KEY_ALIAS` | Signing key alias |
| `WBEAM_ANDROID_KEY_PASSWORD` | Signing key password |
| `WBEAM_VERSION_CODE` | Increasing integer from 2 through 2,100,000,000 |

Run the release wrapper from the repository root after supplying those values
securely in the environment. Set `WBEAM_VERSION` to the intended artifact
revision for a standalone build; the CI pipeline supplies it automatically:

```bash
bash scripts/ci/check-version-consistency.sh
bash scripts/ci/build_apk_release.sh
```

The wrapper derives `WBEAM_VERSION_CODE=10000 + CI_PIPELINE_IID` only when the
code is absent and a numeric pipeline IID is available. Manual builds must
supply it. IIDs increase within one GitLab project; moving publication to a
different project requires preserving a globally increasing version code.

The wrapper passes `WBEAM_VERSION` as Gradle's `WBEAM_BUILD_REV`, which becomes
the APK `versionName` and runtime build revision. The host and APK revisions
must match. For direct Gradle builds, set `WBEAM_BUILD_REV` explicitly;
`WBEAM_VERSION` alone is not a Gradle input. See
[development versioning](agents.workflow.md#versioning).

The build fails for missing signing inputs or an invalid release version code.
The wrapper runs `apksigner verify --print-certs`, rejects an Android Debug
certificate, and writes `dist/wbeam-android-<version>.apk` plus `VERSION.txt`.
The later artifact checker generates checksums; it does not repeat signature
verification. Keep keystores, private keys and secret values out of git, logs,
issues and PR descriptions.

## Updating an existing installation

Android rejects an update signed by a different certificate, regardless of
version code. The installed-mode CLI refuses that update without uninstalling
the app. The source/developer CLI permits destructive recovery only when
`WBEAM_ALLOW_DATA_DELETING_REINSTALL=1` is explicitly set.

For a debug-to-release migration, preserve needed settings/data before the
user explicitly uninstalls the old app. There is no verified lossless
export/import migration documented here. Ordinary release upgrades must keep
the same signing key and package ID (`com.wbeam` unless deliberately overridden).

## Verification

1. Run debug unit tests without release secrets.
2. Build the release APK with the intended version and an increasing code.
3. Inspect `apksigner verify --print-certs <release.apk>` and compare the
   certificate fingerprint with the approved signing key.
4. Install release A, then update to release B signed by the same key; verify
   package identity and retained settings on a physical Android device.
5. Check the host/APK revision with `./wbeam version doctor` in a source checkout
   or `wbeam version doctor` after package installation.

Implementation: [Gradle configuration](../android/app/build.gradle),
[release build wrapper](../scripts/ci/build_apk_release.sh),
[CLI signature handling](../wbeam).
