# First-Run Wizard (Cross-Platform) - Design Spec

## 1. Purpose

The wizard configures WBeam automatically for average users on first launch.

It should:
- detect host OS/runtime,
- discover Android devices and their capabilities,
- detect conflicts (including SuperDisplay),
- pick a safe default profile,
- run a short validation test,
- persist decisions for future launches.

## 2. Scope (v1)

In scope:
- Host detection: Linux/Windows (+ version/session info)
- Android device inventory from ADB
- Capability probing per device (API level, transport readiness)
- SuperDisplay installed/running detection on device
- Auto-select backend/profile
- 10s stream validation + one fallback retry
- Persist wizard report/config

Out of scope (v1):
- Full Wi-Fi streaming implementation
- macOS support
- deep OEM-specific codec tuning beyond profile rules

## 3. High-Level Flow

1. `Welcome`
2. `Host Detection`
3. `Device Inventory`
4. `Conflict Check (SuperDisplay / others)`
5. `Recommended Setup`
6. `Apply + Validate`
7. `Finish`

If any blocking step fails, show one clear recovery action and `Retry`.

## 4. Host Detection

## 4.1 Linux

Collect:
- distro + kernel version
- desktop/compositor (`KDE`, `GNOME`, `Sway`, `Other`)
- session (`Wayland` / `X11`)

Decision hints:
- Linux + Wayland + KDE => prefer portal path
- Linux + X11 => prefer native X11 capture path

## 4.2 Windows

Collect:
- product name and version (`Windows 10/11`)
- build number

Decision hints:
- prefer Windows-native capture backend (future resolver key: `windows_capture`)

## 5. Android Device Inventory

Use `adb devices -l`, then probe each online device.

Per device collect:
- serial
- model/manufacturer
- state (`device`, `unauthorized`, `offline`)
- API level (`ro.build.version.sdk`)
- hardware/platform strings
- display size/density (`wm size`, `wm density`)
- reverse support test result

Store as `DeviceCapability` list.

## 5.1 Capability Fields (v1)

Each device should expose:
- `serial: string`
- `model: string`
- `api_level: number`
- `adb_state: enum`
- `supports_reverse: bool`
- `transport_candidates: ["usb_reverse", "usb_host_ip", "wifi_future"]`
- `safe_max_fps: number`
- `safe_resolution: string`
- `recommended_profile: string`
- `warnings: string[]`

## 6. SuperDisplay Detection

Wizard must check whether SuperDisplay is installed and/or currently active.

Detection strategy (non-blocking warning unless conflict is active):
- check known package names list via `pm list packages`
- check foreground/top activity process hints (`dumpsys activity`, `pidof`) where possible
- optional socket/port/process conflict checks on host side if applicable

Configurable package list (keep externalized):
- `config/conflicts/android_display_apps.json`

Wizard behavior:
- If installed but not active: show warning + continue
- If active/running: show conflict warning and offer:
  - `Stop SuperDisplay and Continue`
  - `Continue Anyway`
  - `Cancel`

## 7. Profile/Backend Resolver

Input:
- `HostContext`
- selected `DeviceCapability`

Output:
- `ResolvedSetup`
  - backend
  - transport path
  - profile name
  - capture size
  - fps
  - bitrate

Rules v1:
- API <= 17 => conservative profile (`ANDROID17_SAFE_30` default)
- API 18-20 => legacy balanced profile
- API >= 21 => balanced 60 profile
- if reverse fails => fallback transport mode (`usb_host_ip`)

## 8. Validation Step

Run short test (10s warmup + 10s sample):
- sender/pipeline fps
- timeout misses
- stalls/freeze indicator

Pass criteria:
- fps above profile minimum
- no hard freeze
- timeout misses below threshold

On fail:
- one automatic fallback (lower fps / safer profile)
- re-test once
- if still fail, mark setup as `degraded` and finish with explicit warning

## 9. Persistence

Write:
- `config/wizard_last_run.json` (full report)
- selected runtime config/profile in canonical config
- device capability cache: `config/device_capabilities.json`

`wizard_last_run.json` should include:
- timestamp
- host context
- detected devices
- selected device
- conflict findings
- resolved setup
- validation metrics
- final status (`ok`, `degraded`, `failed`)

## 10. Future-Proofing for Wi-Fi

Even before Wi-Fi exists, model transport as provider-based.

Transport providers (target):
- `UsbReverseProvider`
- `UsbHostIpProvider`
- `WifiProvider` (placeholder in v1)

Resolver should return provider ID, not hardcoded USB logic.

UI should show transport as generic label:
- `USB Reverse`
- `USB Host IP Fallback`
- `Wi-Fi (future)`

## 11. Wizard UI (Basic)

Keep noob-friendly:
- one primary action per screen
- short health labels (`Good`, `Needs Fix`, `Blocked`)
- advanced details hidden behind `Show Details`

Device table columns:
- `Device`
- `API`
- `ADB`
- `Reverse`
- `Recommended`
- `Warnings`

## 12. Failure Modes

Handle explicitly:
- no ADB installed
- no device connected
- unauthorized device
- host backend unavailable
- capture backend unavailable
- validation failed after fallback

Each failure should provide exact next action and `Retry` button.

## 13. Implementation Notes

Suggested module split:
- `wizard/environment_detector`
- `wizard/device_probe`
- `wizard/conflict_detector`
- `wizard/setup_resolver`
- `wizard/validator`
- `wizard/persistence`
- `wizard/state_machine`

Keep `state_machine` deterministic and serializable.

## 14. Acceptance Criteria (v1)

- On clean machine, user can complete wizard and start stream without opening advanced settings.
- Wizard displays at least one detected device with API level and recommendation.
- SuperDisplay active conflict is detected and surfaced before apply.
- Final mode string is shown: `BACKEND [RES@FPS] PROFILENAME`.
- Wizard result is persisted and loaded on next app launch.
