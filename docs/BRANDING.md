# Branding and upgrades

The application is called **WayBeam**. The repository remains
[`ppotepa/WBeam`](https://github.com/ppotepa/WBeam); clone URLs and existing
technical identifiers remain valid.

## Apply the product name

[`config/branding.json`](../config/branding.json) defines `displayName` and
`desktopName`. From the repository root:

```bash
python3 scripts/apply-branding.py          # preview changes; writes nothing
python3 scripts/apply-branding.py --apply  # synchronize generated display names
python3 scripts/apply-branding.py --check  # fail if generated files differ
```

The script updates the desktop TS/Rust name constants, Tauri window/product
names, HTML titles, shell branding variables and Linux desktop-entry template.
It only touches these explicit targets and is idempotent. Android Gradle reads
the JSON manifest directly for the launcher label, settings/menu titles and
notification channel name. Existing `WBEAM_APP_NAME` Gradle and
`WBEAM_ANDROID_APP_NAME` environment overrides remain available.

Rebuild and deploy the app after applying branding. Generated files are checked
into Git so desktop builds do not need a Python generation step. Local/CI
verification runs `--check`; prose documentation is maintained separately.

## Compatibility contract

| Surface | User-facing name or command | Stable identity retained for upgrades |
| --- | --- | --- |
| Android | WayBeam | `com.wbeam`, activity names, preferences and signing key |
| Desktop | WayBeam Desktop | `com.wbeam.desktop` and existing local-storage keys |
| CLI | `./waybeam` / installed `waybeam` | `wbeam` remains an equivalent command |
| Source installer | `./install-waybeam` | `install-wbeam` remains an equivalent command |
| Packaged desktop launcher | `waybeam-desktop` | `wbeam-desktop`, `wbeam.desktop` and `wbeam` icon ID |
| Linux packages and artifacts | WayBeam descriptions | Package name `wbeam`, existing DEB/RPM/APK filenames |
| Runtime | WayBeam service description | `wbeam-daemon.service`, `wbeamd-*` binaries and Cargo crate names |
| Configuration and state | Existing values reused | `WBEAM_*`, `~/.config/wbeam`, XDG state/data paths and `/usr/share/wbeam` |
| Protocol | Existing stream/control compatibility | WBTP/WBS1 magic and wire layouts |

For an existing installation, update through the same source installer or
package channel. No settings-directory move or uninstall is required for this
name change. Android still requires the same application ID and signing key,
with an increasing `versionCode`; see [release signing](ANDROID_RELEASE_SIGNING.md).
Changing those identities would be a separate data/installation migration.

The rename preserves X11 source and helpers as the legacy path. Current release
selection remains EVDI and Wayland Portal.
