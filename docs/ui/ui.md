# WBeam Desktop UI/UX Specification (v1)

## Goal
Build a clear, fast, low-friction desktop workflow for operating Android devices with a strict 3-panel layout:
1. **Discovery** (discover/select from multiple probe sources)
2. **Device Properties & Options** (inspect/configure)
3. **Session Control & Telemetry** (run/observe)

The UI must prioritize speed of understanding and operational safety.

## Core UX Principles
- **Single primary path:** connect device -> inspect -> start session.
- **Selection-driven UI:** panel 2 and panel 3 depend on selected device.
- **Stable layout:** fixed 3-panel split; no dynamic panel reflow.
- **Low cognitive load:** each panel has one role.
- **Operational clarity:** every action shows state, result, and next step.

## App Information Architecture

### Global Header (thin top bar)
Shows:
- App name/version
- Discovery summary: `ADB`, `LAN`, `Wi-Fi` source health
- Global errors/warnings badge
- Optional: current clock + lightweight activity spinner when probing

### Panel 1: Discovery
Purpose: discover and select reachable devices across probe sources.

#### Content
- Section title: `1. Discovery`
- Source chips/toggles:
  - `ADB` (enabled in v1)
  - `LAN` (planned)
  - `Wi-Fi` (planned; same probe backend as LAN, different preset/filter)
- Probe status line:
  - `Last probe: <time>`
  - `ADB: OK / ERROR`
  - `LAN: idle / probing / error`
  - `Wi-Fi: idle / probing / error`
  - `N devices discovered`
- Scrollable device list (fixed row height)

#### Device row (clickable)
- Source badge: `ADB` / `LAN` / `Wi-Fi`
- Large type icon: phone/tablet
- Primary line (left): `MAKE MODEL`
- Primary line (right): `Android <release> (API <level>)`
- Secondary line: source-native identity (`ADB serial` or `host:port` / endpoint id)
- Tertiary line: `discovery state` + `abi` + optional latency hint

#### Row interactions
- Single click: select device
- Double click: open device details dialog
- Selected row has clear highlight + border

#### Empty/error states
- No devices: show neutral hint + quick actions (`Retry discovery`, `Check source health`)
- Per-source failure: show source-specific error + actionable hint (e.g. USB auth, subnet blocked)

### Panel 2: Device Properties & Options
Purpose: show per-device data from probing and available configuration/actions.
- **Panel behavior:** vertically scrollable; content can exceed viewport safely.

#### Locked state (no device selected)
- Greyed body
- Message: `Select a device in panel 1`

#### Active state (device selected)
Split into 3 blocks:

1) **DEVICE / HOST INFO**
- Manufacturer, model, codename
- Android release/API
- ABI
- Device type (phone/tablet)
- Battery level/status
- Discovery source + endpoint (`ADB serial` / `IP:port`)
- Optional: source-specific state (`device`, `offline`, `unauthorized`, `unreachable`)
- Host context (OS-aware):
- Host OS mode selector is read-only and source of truth from host probe:
  - On Linux: show `Linux` + compositor/session details (e.g. `KDE/Wayland`, `GNOME/X11`)
  - On Windows: show only `Windows` (no compositor selector)
- Display host capture backend recommendation per OS/session.

2) **PROFILE**
- `Mode` switch:
  - Rendered as two edge-to-edge switch buttons inside panel width (`AUTOMATIC` / `MANUAL`)
  - Each button uses 50% panel width (together 100% width)
  - No caption (`Mode`) and no extra outer margin/padding
  - No border; active side highlighted
  - `Automatic` (default; recommended)
  - `Manual` (expert)
- In `Automatic`:
  - Show selected profile + rationale (e.g. `modern` from API/ABI)
  - Manual fields are locked
- In `Manual`:
  - Profile dropdown (e.g. `api17-safe`, `api21`, `modern`, `custom`)
  - Bitrate input (Mbps / kbps)
  - Optional advanced knobs: target fps, keyframe interval, encoder preset
  - Validation + safe bounds with inline warnings

3) **ACTIONS**
Safe actions:
- Refresh properties
- Open full details
- Capture screenshot
- Restart app on device

Advanced actions:
- Setup transport route (source-aware):
  - ADB route (`reverse :5005`, `forward :5006`)
  - LAN/Wi-Fi route (socket endpoint check)
- Install/update APK
- Reboot device

Action rules:
- Disabled when not applicable
- Source-aware availability matrix (ADB-only actions hidden/disabled for non-ADB sources)
- Automatic/manual gating: profile/bitrate edits available only in `Manual` mode
- Tooltip explains why disabled
- Confirm dialog for disruptive actions (reboot/install)

### Panel 3: Session Control & Telemetry
Purpose: run/stop session and monitor live metrics.

#### Session controls
- Main buttons:
  - `Start Session`
  - `Stop Session`
- Optional secondary:
  - `Restart Session`
  - `Open Logs`

#### Session status card
- Current status: `Idle / Preparing / Running / Error`
- Selected device serial
- Uptime
- Active transport mode

#### Live nerdy telemetry (compact)
- FPS
- Latency (ms)
- Frame drops
- Bitrate
- Decoder restarts
- Packet loss estimate (if available)

#### Event log strip (last N)
- Timestamped events:
  - session transitions
  - probe changes
  - errors/warnings

## Detailed Flow

### A) Happy path
1. App starts, runs discovery for enabled sources (ADB in v1, LAN/Wi-Fi later).
2. User sees unified discovery list in panel 1.
3. User selects a device.
4. Panel 2 unlocks with probed properties/actions and default `Mode: Automatic`.
5. User clicks `Start Session` in panel 3.
6. Panel 3 shows running status + telemetry.

### E) Manual tuning flow
1. User selects device.
2. In panel 2, switch `Mode` from `Automatic` to `Manual`.
3. User picks profile and enters bitrate manually.
4. Validation runs immediately; invalid values block apply/start with clear errors.
5. Session starts with manual overrides and logs that manual mode is active.

### B) Device disconnected mid-session
1. Selected device disappears in panel 1.
2. Selection is cleared.
3. Panel 2 and panel 3 lock.
4. Session status goes to `Error` then `Idle` with reason.

### C) Source-specific unavailable device
1. Row shows warning state badge.
2. Panel 2 actions mostly disabled.
3. Helper message is source-specific (e.g. `Authorize USB debugging`, `Host unreachable`, `Subnet blocked`).

### D) Multi-source duplicate target (future)
1. Same physical device appears via multiple sources (e.g., ADB + LAN).
2. UI keeps one logical device entry with preferred source and alternate source badges.
3. User can switch preferred source in panel 2.

## Interaction and Feedback Model
- Every button produces immediate visual feedback.
- Long operations show progress inline in panel 3.
- Final result shown as success/warn/error toast + event log entry.
- Never silently fail.

## Visual System (practical)
- Use fixed panel gutters and consistent vertical spacing.
- Keep row heights and typography stable.
- Use one accent color for selection/action readiness.
- Use semantic colors only for status (green/yellow/red).
- Layout spacing tokens (flex-like rhythm for all panels):
  - Outer app padding: `16`
  - Panel-to-panel gap: `8`
  - Panel inner horizontal padding: `16`
  - Section divider spacing: `14-18`
  - Item stack spacing inside sections: `8`
  - Action button grid gap: `8-12`

## Keyboard/Power-user UX (phase 2)
- Up/down in device list
- Enter to select
- Ctrl+Enter to start session
- `R` refresh probe

## Suggested Data Contract Per Device
- `device_id` (stable logical id)
- `discovery_source` (`adb` / `lan` / `wifi`)
- `source_identity` (adb serial or endpoint id)
- `source_state`
- `manufacturer`
- `model`
- `device_name`
- `android_release`
- `api_level`
- `abi`
- `characteristics`
- `battery_level`
- `battery_status`

## Suggested Host Context Contract
- `host_os` (`linux` / `windows` / `macos`)
- `host_session_type` (`wayland` / `x11` / `unknown` on Linux)
- `host_desktop_env` (e.g. `kde`, `gnome`, empty on Windows)
- `capture_backend_recommendation`

## Suggested Session Config Contract
- `mode` (`automatic` / `manual`)
- `selected_profile`
- `bitrate_kbps`
- `target_fps` (optional)
- `gop` / `keyframe_interval` (optional)
- `manual_overrides_enabled`

## Out of Scope for v1
- Multi-device simultaneous sessions
- Deep settings editor
- Historical telemetry charts beyond short in-memory window

## Deliverables for implementation
- Panel components:
  - `discovery_panel`
  - `device_properties_panel`
  - `session_panel`
- State:
  - selected device id
  - discovery snapshot (per source + unified devices)
  - session state machine
- UX checks:
  - selection gating
  - source-aware action gating
  - action disable rules
  - clear error messages
