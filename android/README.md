# Android Client

The `:app` Gradle module provides the Android receiver (`com.wbeam` by default),
MediaCodec video decoding, PNG rendering, connection diagnostics, HUD and
telemetry. Session lifecycle and adaptation remain owned by the host daemon.

## Build and Deploy

Run from the repository root after [source setup](../docs/INSTALLER_WIZARD.md):

```bash
./wbeam android build
./wbeam android deploy
./wbeam version doctor
```

For multiple devices, set `WBEAM_ANDROID_SERIAL=<serial>` before a command or use
`./wbeam android deploy-all`. Accept USB debugging authorization on the device.
Signed release builds have separate
[signing requirements](../docs/ANDROID_RELEASE_SIGNING.md).

## Compatibility and Transport

[`app/build.gradle`](app/build.gradle) defaults to `minSdk=17` and
`compileSdk=targetSdk=35`. The CLI overrides the build minimum according to
`WBEAM_ANDROID_PIPELINE`:

| Value | Build minimum | Automatic selection |
|---|---|---|
| `legacy` | API 17 | Device API 17–18, or API detection failed |
| `modern` | API 19 | Detected device API 19+ |
| `auto` (default) | Selected as above | Probes the targeted ADB device |

This is a build/deploy compatibility choice, not a guarantee of codec or stream
performance. H.264/H.265 availability depends on the device decoder; retain the
legacy Android path and validate real devices with the
[release checklist](../docs/release-checklist.md).

The preferred transport is TCP through **ADB reverse**, normally available on
Android API 21+. API 17–20 devices, or devices where reverse fails, require a
direct host address over a suitable tethered/LAN connection. The deploy script
attempts to resolve that address; choosing `modern` alone does not make reverse
available. The host control API binds loopback by default, so direct access also
requires the explicit [trusted-interface configuration](../docs/SECURITY_RELEASE.md).
It is not a zero-configuration fallback.

## Code Map

Under [`app/src/main/java/com/wbeam/`](app/src/main/java/com/wbeam/):

| Package | Responsibility |
|---|---|
| `stream/` | WBTP parsing, H.264/H.265 MediaCodec loops, PNG rendering, reconnects |
| `api/` | Host HTTP client and status polling |
| `telemetry/`, `hud/` | Metrics delivery and on-device presentation |
| `startup/`, `ui/` | Preflight, settings, lifecycle and activity coordinators |

See [wire and telemetry contracts](../docs/telemetry_schema.md). Android legacy
compatibility and legacy host X11 capture are separate concerns.
