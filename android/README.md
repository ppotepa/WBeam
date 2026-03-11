# Android Domain

Canonical Android domain boundary.

## Purpose

`android/` owns:

- Android app (`com.wbeam`),
- network receive/depacketize/decode/render pipeline,
- Android-side HUD/metrics/telemetry,
- startup/status diagnostics shown on-device.

## Contract

- Keep Android decode/runtime concerns inside Android domain.
- Preserve compatibility for legacy API lanes (`legacy`/`modern` pipeline selection in `./wbeam`).
