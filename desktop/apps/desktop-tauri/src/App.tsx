import { For, Show, createSignal, onCleanup, onMount } from "solid-js";
import { getCurrentWindow } from "@tauri-apps/api/window";
import AlertTriangle from "lucide-solid/icons/alert-triangle";
import BatteryCharging from "lucide-solid/icons/battery-charging";
import BatteryFull from "lucide-solid/icons/battery-full";
import BatteryLow from "lucide-solid/icons/battery-low";
import BatteryMedium from "lucide-solid/icons/battery-medium";
import Cpu from "lucide-solid/icons/cpu";
import MonitorSmartphone from "lucide-solid/icons/monitor-smartphone";
import Package from "lucide-solid/icons/package";
import Settings from "lucide-solid/icons/settings";
import ShieldCheck from "lucide-solid/icons/shield-check";
import Smartphone from "lucide-solid/icons/smartphone";
import Tablet from "lucide-solid/icons/tablet";
import Play from "lucide-solid/icons/play";
import Square from "lucide-solid/icons/square";
import Download from "lucide-solid/icons/download";
import Trash2 from "lucide-solid/icons/trash-2";
import RefreshCw from "lucide-solid/icons/refresh-cw";
import Link2 from "lucide-solid/icons/link-2";
import Unlink2 from "lucide-solid/icons/unlink-2";
import Loader2 from "lucide-solid/icons/loader-2";
import type { ConnectEncoderMode, ConnectSessionConfig, DeviceBasic } from "./types";
import type { VirtualDepsInstallStatus, VirtualDoctor } from "./types";
import { HostApiManager } from "./managers/hostApiManager";
import { createSessionManager } from "./managers/sessionManager";
import trainedProfileLabels from "./config/trained-profile-labels.json";
import trainedProfileRuntime from "./config/trained-profile-runtime.json";
import connectResolutionPresets from "./config/connect-resolution-presets.json";
import connectEncoderOptions from "./config/connect-encoder-options.json";

function BatteryIcon(props: { level: number | null; charging: boolean }) {
  if (props.charging) return <BatteryCharging size={14} />;
  if (props.level === null) return <BatteryMedium size={14} />;
  if (props.level >= 80) return <BatteryFull size={14} />;
  if (props.level >= 30) return <BatteryMedium size={14} />;
  return <BatteryLow size={14} />;
}

function DeviceTypeIcon(props: { type: string }) {
  return props.type === "Tablet" ? <Tablet size={16} /> : <Smartphone size={16} />;
}

type DisplayMode = "virtual_monitor" | "duplicate";
const CONNECT_MODE_STORAGE_KEY = "wbeam.connect.mode.by.serial";
const WAYLAND_EXPERIMENTAL_DUPLICATION_STORAGE_KEY = "wbeam.connect.experimental.dup.wayland";

type TrainedProfileLabelEntry = {
  id: string;
  label: string;
  description: string;
};

type TrainedProfileRuntimeEntry = {
  encoder?: "h264" | "h265" | "rawpng";
  cursorMode?: "embedded" | "hidden" | "metadata";
};

type ResolutionPresetEntry = {
  id: string;
  label: string;
  kind: "device_max" | "device_current" | "fixed";
  size?: string;
};

const TRAINED_PROFILE_DATA = trainedProfileLabels as {
  defaultProfileId?: string;
  profiles?: TrainedProfileLabelEntry[];
};

const TRAINED_PROFILE_RUNTIME_DATA = trainedProfileRuntime as {
  defaultsByProfileId?: Record<string, TrainedProfileRuntimeEntry>;
};

const RESOLUTION_PRESET_DATA = connectResolutionPresets as {
  defaultPresetId?: string;
  presets?: ResolutionPresetEntry[];
};

const ENCODER_OPTION_DATA = connectEncoderOptions as {
  defaultEncoderMode?: ConnectEncoderMode;
  options?: { id: ConnectEncoderMode; label: string }[];
};

const TRAINED_PROFILE_OPTIONS: TrainedProfileLabelEntry[] = TRAINED_PROFILE_DATA.profiles ?? [];
const TRAINED_PROFILE_IDS = new Set(TRAINED_PROFILE_OPTIONS.map((item) => item.id));
const TRAINED_PROFILE_DEFAULT_ID =
  (TRAINED_PROFILE_DATA.defaultProfileId && TRAINED_PROFILE_IDS.has(TRAINED_PROFILE_DATA.defaultProfileId))
    ? TRAINED_PROFILE_DATA.defaultProfileId
    : (TRAINED_PROFILE_OPTIONS[0]?.id ?? "baseline");

const TRAINED_PROFILE_RUNTIME = TRAINED_PROFILE_RUNTIME_DATA.defaultsByProfileId ?? {};
const RESOLUTION_PRESET_OPTIONS: ResolutionPresetEntry[] = RESOLUTION_PRESET_DATA.presets ?? [];
const RESOLUTION_PRESET_IDS = new Set(RESOLUTION_PRESET_OPTIONS.map((item) => item.id));
const RESOLUTION_PRESET_DEFAULT_ID =
  (RESOLUTION_PRESET_DATA.defaultPresetId && RESOLUTION_PRESET_IDS.has(RESOLUTION_PRESET_DATA.defaultPresetId))
    ? RESOLUTION_PRESET_DATA.defaultPresetId
    : (RESOLUTION_PRESET_OPTIONS[0]?.id ?? "device_max");

const ENCODER_OPTIONS = ENCODER_OPTION_DATA.options ?? [
  { id: "profile_default" as const, label: "From trained profile" },
  { id: "h264" as const, label: "H.264" },
  { id: "h265" as const, label: "H.265 / HEVC" },
  { id: "rawpng" as const, label: "RAW PNG (experimental)" },
];
const ENCODER_OPTION_IDS = new Set(ENCODER_OPTIONS.map((item) => item.id));
const ENCODER_DEFAULT_MODE = ENCODER_OPTION_IDS.has(ENCODER_OPTION_DATA.defaultEncoderMode ?? "profile_default")
  ? (ENCODER_OPTION_DATA.defaultEncoderMode as ConnectEncoderMode)
  : "profile_default";

function loadSavedDisplayMode(serial: string): DisplayMode | null {
  try {
    const raw = localStorage.getItem(CONNECT_MODE_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Record<string, string>;
    const value = parsed?.[serial];
    if (value === "virtual_monitor" || value === "duplicate") return value;
    // Backward compatibility with legacy values.
    if (value === "virtual") return "virtual_monitor";
    if (value === "isolated" || value === "virtual_isolated") return "duplicate";
    return null;
  } catch {
    return null;
  }
}

function saveDisplayMode(serial: string, mode: DisplayMode): void {
  try {
    const raw = localStorage.getItem(CONNECT_MODE_STORAGE_KEY);
    const parsed = raw ? (JSON.parse(raw) as Record<string, string>) : {};
    parsed[serial] = mode;
    localStorage.setItem(CONNECT_MODE_STORAGE_KEY, JSON.stringify(parsed));
  } catch {
    // Ignore localStorage failures.
  }
}

function loadWaylandExperimentalDuplication(): boolean {
  try {
    return localStorage.getItem(WAYLAND_EXPERIMENTAL_DUPLICATION_STORAGE_KEY) === "1";
  } catch {
    return false;
  }
}

function saveWaylandExperimentalDuplication(enabled: boolean): void {
  try {
    localStorage.setItem(WAYLAND_EXPERIMENTAL_DUPLICATION_STORAGE_KEY, enabled ? "1" : "0");
  } catch {
    // Ignore localStorage failures.
  }
}

function parseResolutionDims(value: string): [number, number] | null {
  const match = value.trim().match(/^(\d{3,5})x(\d{3,5})$/);
  if (!match) return null;
  const w = Number(match[1]);
  const h = Number(match[2]);
  if (!Number.isFinite(w) || !Number.isFinite(h) || w < 320 || h < 320) return null;
  return [w, h];
}

function normalizeLandscapeSize(value: string): string | undefined {
  const dims = parseResolutionDims(value);
  if (!dims) return undefined;
  const width = Math.max(dims[0], dims[1]);
  const height = Math.min(dims[0], dims[1]);
  const safeW = Math.max(640, Math.min(3840, width));
  const safeH = Math.max(360, Math.min(2160, height));
  const evenW = safeW % 2 === 0 ? safeW : safeW - 1;
  const evenH = safeH % 2 === 0 ? safeH : safeH - 1;
  return `${evenW}x${evenH}`;
}

function resolveSessionSizeForPreset(device: DeviceBasic, presetId: string): string | undefined {
  const preset = RESOLUTION_PRESET_OPTIONS.find((item) => item.id === presetId);
  if (!preset) return normalizeLandscapeSize(device.maxResolution) ?? normalizeLandscapeSize(device.resolution);
  if (preset.kind === "device_max") {
    return normalizeLandscapeSize(device.maxResolution) ?? normalizeLandscapeSize(device.resolution);
  }
  if (preset.kind === "device_current") {
    return normalizeLandscapeSize(device.resolution) ?? normalizeLandscapeSize(device.maxResolution);
  }
  return normalizeLandscapeSize(preset.size ?? "");
}

function resolveProfileId(profileId: string): string {
  if (TRAINED_PROFILE_IDS.has(profileId)) return profileId;
  return TRAINED_PROFILE_DEFAULT_ID;
}

function resolveEncoderForProfile(profileId: string, mode: ConnectEncoderMode): "h264" | "h265" | "rawpng" {
  if (mode !== "profile_default") return mode;
  const byProfile = TRAINED_PROFILE_RUNTIME[profileId];
  if (byProfile?.encoder === "h264" || byProfile?.encoder === "h265" || byProfile?.encoder === "rawpng") {
    return byProfile.encoder;
  }
  return "h264";
}

function getInitialConnectDialogMode(deviceSerial: string, waylandHost: boolean): DisplayMode {
  if (waylandHost) return "virtual_monitor";
  return loadSavedDisplayMode(deviceSerial) ?? "virtual_monitor";
}

function resolveConnectDialogDoctorLoading(waylandHost: boolean): boolean {
  return !waylandHost;
}

export default function App() {
  const api = new HostApiManager();
  const session = createSessionManager(api);
  const [mode, setMode] = createSignal<"basic" | "advanced">("basic");
  const [hostName, setHostName] = createSignal("unknown-host");
  const [connectDialogDevice, setConnectDialogDevice] = createSignal<DeviceBasic | null>(null);
  const [connectDialogMode, setConnectDialogMode] = createSignal<DisplayMode>("virtual_monitor");
  const [connectDialogProfileId, setConnectDialogProfileId] = createSignal<string>(TRAINED_PROFILE_DEFAULT_ID);
  const [connectDialogResolutionPresetId, setConnectDialogResolutionPresetId] = createSignal<string>(RESOLUTION_PRESET_DEFAULT_ID);
  const [connectDialogEncoderMode, setConnectDialogEncoderMode] = createSignal<ConnectEncoderMode>(ENCODER_DEFAULT_MODE);
  const [connectDialogDoctor, setConnectDialogDoctor] = createSignal<VirtualDoctor | null>(null);
  const [connectDialogDoctorLoading, setConnectDialogDoctorLoading] = createSignal(false);
  const [connectDialogBlockReason, setConnectDialogBlockReason] = createSignal("");
  const [waylandExperimentalDuplication, setWaylandExperimentalDuplication] = createSignal(
    loadWaylandExperimentalDuplication(),
  );
  const [virtualStartupDoctor, setVirtualStartupDoctor] = createSignal<VirtualDoctor | null>(null);
  const [virtualSetupVisible, setVirtualSetupVisible] = createSignal(false);
  const [virtualSetupInstalling, setVirtualSetupInstalling] = createSignal(false);
  const [virtualInstallVisible, setVirtualInstallVisible] = createSignal(false);
  const [virtualInstallStatus, setVirtualInstallStatus] = createSignal<VirtualDepsInstallStatus>({
    running: false,
    done: false,
    success: false,
    message: "idle",
    logs: [],
  });
  let virtualInstallPollTimer: number | null = null;
  const upgradeAvailable = () =>
    session.service().active
    && session.service().installed
    && session.daemonVersion().length > 0
    && session.hostVersion().length > 0
    && session.daemonVersion() !== session.hostVersion();

  const daemonVersionKnown = () => session.daemonVersion().length > 0;

  function deviceVersionStatus(device: DeviceBasic): { cls: "ok" | "warn" | "bad"; label: string } {
    if (!device.apkInstalled) return { cls: "warn", label: "APK missing" };
    if (!session.service().installed) return { cls: "warn", label: "Install desktop service first" };
    if (!session.service().active) return { cls: "warn", label: "Start desktop service to verify" };
    if (!daemonVersionKnown()) return { cls: "warn", label: "Daemon unreachable - cannot verify" };
    return device.apkMatchesDaemon
      ? { cls: "ok", label: "Version match" }
      : { cls: "bad", label: "Update required" };
  }

  function connectDisabledReason(device: DeviceBasic): string {
    if (isDeviceBusy(device)) return "This device action is already in progress";
    if (isWaylandPortalHost()) {
      const otherConnectInFlight = session
        .deviceActionBusy()
        .some((entry) => entry.endsWith(":connect") && !entry.startsWith(`${device.serial}:`));
      if (otherConnectInFlight) {
        return "Another Wayland portal connect is in progress";
      }
    }
    if (!session.service().active) return "Desktop service must be running";
    if (!daemonVersionKnown()) return "Daemon API unreachable";
    if (!device.apkInstalled) return "APK is not installed on this device";
    if (!device.apkMatchesDaemon) return "APK version must match daemon version";
    if (device.streamState === "STREAMING") return "Device is already streaming";
    if (device.streamState === "CONNECTING") return "Device is already connecting";
    return "";
  }

  function disconnectDisabledReason(device: DeviceBasic): string {
    if (isDeviceBusy(device)) return "This device action is already in progress";
    if (!session.service().active) return "Desktop service must be running";
    if (device.streamState !== "STREAMING" && device.streamState !== "CONNECTING") {
      return "Device is not streaming";
    }
    return "";
  }

  function isDeviceActionBusy(device: DeviceBasic, action: "connect" | "disconnect"): boolean {
    return session.deviceActionBusy().includes(`${device.serial}:${action}`);
  }

  function isDeviceBusy(device: DeviceBasic): boolean {
    return session.deviceActionBusy().some((entry) => entry.startsWith(`${device.serial}:`));
  }

  function isWaylandPortalHost(): boolean {
    return session.hostProbe().captureMode === "wayland_portal";
  }

  function toggleWaylandExperimentalDuplication(enabled: boolean): void {
    setWaylandExperimentalDuplication(enabled);
    saveWaylandExperimentalDuplication(enabled);
  }

  function resetConnectDialogForDevice(device: DeviceBasic, isWaylandHost: boolean): void {
    setConnectDialogMode(getInitialConnectDialogMode(device.serial, isWaylandHost));
    setConnectDialogProfileId(TRAINED_PROFILE_DEFAULT_ID);
    setConnectDialogResolutionPresetId(RESOLUTION_PRESET_DEFAULT_ID);
    setConnectDialogEncoderMode(ENCODER_DEFAULT_MODE);
    setConnectDialogDoctor(null);
    setConnectDialogDoctorLoading(resolveConnectDialogDoctorLoading(isWaylandHost));
    setConnectDialogBlockReason(connectDisabledReason(device));
    setConnectDialogDevice(device);
  }

  async function loadConnectDialogDoctor(device: DeviceBasic): Promise<void> {
    try {
      const doctor = await api.getVirtualDoctor(device);
      setConnectDialogDoctor(doctor);
    } catch {
      setConnectDialogDoctor(null);
    } finally {
      setConnectDialogDoctorLoading(false);
    }
  }

  function openConnectDialog(device: DeviceBasic): void {
    const isWaylandHost = isWaylandPortalHost();
    resetConnectDialogForDevice(device, isWaylandHost);
    if (!isWaylandHost) {
      void loadConnectDialogDoctor(device);
    }
  }

  function closeConnectDialog(): void {
    setConnectDialogDevice(null);
    setConnectDialogDoctor(null);
    setConnectDialogDoctorLoading(false);
    setConnectDialogBlockReason("");
  }

  async function confirmConnect(): Promise<void> {
    const device = connectDialogDevice();
    if (!device) return;
    const waylandHost = isWaylandPortalHost();
    const blockedReason = connectDisabledReason(device);
    if (blockedReason.length > 0) {
      setConnectDialogBlockReason(blockedReason);
      session.setError(blockedReason);
      return;
    }
    try {
      let backendMode: "virtual_monitor" | "virtual_mirror" | "duplicate" = "duplicate";
      if (waylandHost) {
        backendMode = waylandExperimentalDuplication() ? "virtual_mirror" : "virtual_monitor";
      } else {
        const chosenMode = connectDialogMode();
        if (chosenMode === "virtual_monitor") {
          const doctor = connectDialogDoctor() ?? await api.getVirtualDoctor(device);
          if (!isVirtualMonitorAvailable(doctor)) {
            session.setError(
              "Virtual monitor mode is unavailable in current host session. Use Duplicate mode."
            );
            return;
          }
          backendMode = "virtual_monitor";
        }
        saveDisplayMode(device.serial, chosenMode);
      }

      const resolvedProfileId = resolveProfileId(connectDialogProfileId());
      const resolvedSize = resolveSessionSizeForPreset(device, connectDialogResolutionPresetId());
      const resolvedEncoder = resolveEncoderForProfile(resolvedProfileId, connectDialogEncoderMode());
      const connectConfig: ConnectSessionConfig = {
        profile: resolvedProfileId,
        encoder: resolvedEncoder,
        size: resolvedSize,
      };

      closeConnectDialog();
      await session.connectDevice(device, backendMode, connectConfig);
    } catch (err) {
      session.setError(`Connect mode validation failed: ${String(err)}`);
    }
  }

  function closeVirtualSetup(): void {
    setVirtualSetupVisible(false);
  }

  function stopVirtualInstallPolling(): void {
    if (virtualInstallPollTimer !== null) {
      window.clearInterval(virtualInstallPollTimer);
      virtualInstallPollTimer = null;
    }
  }

  function closeVirtualInstallModal(): void {
    if (virtualInstallStatus().running) return;
    setVirtualInstallVisible(false);
  }

  async function installVirtualDeps(): Promise<void> {
    setVirtualSetupInstalling(true);
    session.setError("");
    setVirtualInstallVisible(true);
    setVirtualInstallStatus({
      running: true,
      done: false,
      success: false,
      message: "Starting dependency installer...",
      logs: ["[ui] requesting installer start..."],
    });
    stopVirtualInstallPolling();
    try {
      await api.startVirtualDepsInstall();
      const poll = async () => {
        try {
          const status = await api.getVirtualDepsInstallStatus();
          setVirtualInstallStatus(status);
          if (status.done) {
            stopVirtualInstallPolling();
            if (status.success) {
              const doctor = await api.getVirtualDoctor();
              setVirtualStartupDoctor(doctor);
              if (doctor.ok) {
                setVirtualSetupVisible(false);
                session.setError("");
              } else {
                setVirtualSetupVisible(true);
                const details = doctor.missingDeps.length > 0 ? ` Missing: ${doctor.missingDeps.join(", ")}.` : "";
                session.setError(`${doctor.message}.${details}`.trim());
              }
              await session.refreshSnapshot({ silent: true });
            } else {
              session.setError(status.message);
            }
          }
        } catch (err) {
          stopVirtualInstallPolling();
          setVirtualInstallStatus({
            running: false,
            done: true,
            success: false,
            message: `Installer status read failed: ${String(err)}`,
            logs: [...virtualInstallStatus().logs, `[ui][error] ${String(err)}`],
          });
          session.setError(`Dependency installation failed: ${String(err)}`);
        }
      };
      await poll();
      virtualInstallPollTimer = window.setInterval(() => {
        void poll();
      }, 600);
    } catch (err) {
      session.setError(`Dependency installation failed: ${String(err)}`);
      setVirtualInstallStatus({
        running: false,
        done: true,
        success: false,
        message: `Dependency installation failed: ${String(err)}`,
        logs: [...virtualInstallStatus().logs, `[ui][error] ${String(err)}`],
      });
    } finally {
      setVirtualSetupInstalling(false);
    }
  }

  onMount(async () => {
    try {
      const name = await api.getHostName();
      const safe = name || "unknown-host";
      setHostName(safe);
      await getCurrentWindow().setTitle(`WBeam - ${safe}`);
    } catch {
      // ignore title update errors
    }

    await session.refreshSnapshot();
    try {
      const doctor = await api.getVirtualDoctor();
      setVirtualStartupDoctor(doctor);
    } catch {
      // Non-blocking check; connect flow still performs per-device guard.
    }

    const timer = window.setInterval(() => {
      if (session.deviceActionBusy().length > 0 || session.refreshInFlight()) return;
      void session.refreshSnapshot({ silent: true, forceDevices: true });
    }, 1200);

    const refreshVisible = () => {
      if (session.deviceActionBusy().length > 0 || session.refreshInFlight()) return;
      void session.refreshSnapshot({ silent: true, forceDevices: true });
    };
    const onFocus = () => refreshVisible();
    const onVisibilityChange = () => {
      if (!document.hidden) refreshVisible();
    };
    window.addEventListener("focus", onFocus);
    document.addEventListener("visibilitychange", onVisibilityChange);

    onCleanup(() => {
      window.clearInterval(timer);
      window.removeEventListener("focus", onFocus);
      document.removeEventListener("visibilitychange", onVisibilityChange);
      stopVirtualInstallPolling();
    });
  });

  return (
    <main class="app-shell">
      <section class="panel basic-400">
        <header class="panel-header">
          <div>
            <h1>WBeam - {hostName()}</h1>
            <p class="mode-line">Mode: {mode() === "basic" ? "Basic" : "Advanced"}</p>
          </div>
          <div class="header-actions">
            <button
              class="icon-btn"
              onClick={() => setMode(mode() === "basic" ? "advanced" : "basic")}
              title="Switch basic/advanced mode"
              aria-label="Switch mode"
            >
              <Settings size={16} />
            </button>
            <button class="refresh-btn" onClick={() => session.refreshSnapshot()} disabled={session.refreshing()}>
              <Show when={session.refreshing()} fallback={<RefreshCw size={14} />}>
                <Loader2 size={14} class="spinning" />
              </Show>
              {session.refreshing() ? "Refreshing" : "Refresh"}
            </button>
          </div>
        </header>

        <p class="meta-line">Daemon APK: <strong>{session.daemonVersion() || session.hostVersion() || "not set"}</strong></p>

        <Show when={session.error()}>
          <p class="error-line">{session.error()}</p>
        </Show>
        <Show when={!session.loading() && session.devices().length === 0 && !session.error()}>
          <p class="empty-line">
            {session.service().active
              ? "No connected ADB devices."
              : "Probing paused until desktop service is running."}
          </p>
        </Show>

        <ul class="device-list" aria-label="Connected devices">
          <For each={session.devices()}>
            {(device) => (
              <li class={`device-row ${isDeviceBusy(device) ? "device-row-busy" : ""}`} aria-busy={isDeviceBusy(device)}>
                <div class="line model-line" title={`serial: ${device.serial}`}>
                  <DeviceTypeIcon type={device.deviceClass} />
                  <span class="model-text">{device.model}</span>
                </div>

                <div class="line split-line">
                  <span title="Platform and OS version">
                    <Smartphone size={14} /> {device.platform} {device.osVersion}
                  </span>
                  <span title="Android API level">
                    <Cpu size={14} /> API {device.apiLevel}
                  </span>
                </div>

                <div class="line split-line">
                  <span title="Current resolution">
                    <MonitorSmartphone size={14} /> {device.resolution}
                  </span>
                  <span title="Maximum detected resolution">
                    Max {device.maxResolution}
                  </span>
                </div>

                <div class="line split-line">
                  <span title="Battery percentage and charge state">
                    <BatteryIcon level={device.batteryLevel} charging={device.batteryCharging} /> {device.batteryPercent}
                  </span>
                  <span title="Detected form factor">
                    {device.deviceClass}
                  </span>
                </div>

                <div class="line split-line">
                  <span title="Whether com.wbeam is installed">
                    <Package size={14} /> {device.apkInstalled ? "Installed" : "Missing"}
                  </span>
                  <span class="version-pill" title="Installed APK version">
                    APK {device.apkVersion || "-"}
                  </span>
                </div>

                <div class="line split-line">
                  <span title="Assigned stream port">
                    Port {device.streamPort}
                  </span>
                  <span title="Current daemon stream state">
                    State {device.streamState}
                  </span>
                </div>

                <div class="line end-line">
                  {(() => {
                    const st = deviceVersionStatus(device);
                    return (
                  <span
                    class={`status ${st.cls}`}
                    title={
                      session.service().active
                        ? "APK version should match daemon build revision"
                        : "Version check is paused until desktop service is running"
                    }
                  >
                    <Show when={st.cls === "ok"} fallback={<AlertTriangle size={14} />}>
                      <ShieldCheck size={14} />
                    </Show>
                    {st.label}
                  </span>
                    );
                  })()}
                </div>

                <div class="device-actions">
                  {(() => {
                    const connectReason = connectDisabledReason(device);
                    const disconnectReason = disconnectDisabledReason(device);
                    const connectDisabled = connectReason.length > 0;
                    const disconnectDisabled = disconnectReason.length > 0;
                    const connectBusy = isDeviceActionBusy(device, "connect");
                    const disconnectBusy = isDeviceActionBusy(device, "disconnect");
                    return (
                      <>
                  <button
                    class="device-btn"
                    title="Refresh this device state"
                    disabled={session.refreshing() || isDeviceBusy(device)}
                    onClick={() => session.refreshSnapshot()}
                  >
                    <RefreshCw size={13} /> Refresh
                  </button>
                  <button
                    class="device-btn"
                    title={connectBusy ? "Connecting..." : (connectDisabled ? `Connect blocked: ${connectReason}` : "Start stream for this device")}
                    disabled={connectBusy || connectDisabled}
                    onClick={() => openConnectDialog(device)}
                  >
                    <Show when={connectBusy} fallback={<Link2 size={13} />}>
                      <Loader2 size={13} class="spinning" />
                    </Show>
                    {connectBusy ? "Connecting..." : "Connect"}
                  </button>
                  <button
                    class="device-btn"
                    title={disconnectBusy ? "Disconnecting..." : (disconnectDisabled ? disconnectReason : "Stop stream for this device")}
                    disabled={disconnectDisabled}
                    onClick={() => session.disconnectDevice(device)}
                  >
                    <Show when={disconnectBusy} fallback={<Unlink2 size={13} />}>
                      <Loader2 size={13} class="spinning" />
                    </Show>
                    {disconnectBusy ? "Stopping..." : "Disconnect"}
                  </button>
                      </>
                    );
                  })()}
                </div>
              </li>
            )}
          </For>
        </ul>

        <section class="wayland-experimental-control">
          <label class={`checkbox-option ${isWaylandPortalHost() ? "" : "disabled"}`}>
            <input
              type="checkbox"
              checked={waylandExperimentalDuplication()}
              disabled={!isWaylandPortalHost()}
              onChange={(event) => toggleWaylandExperimentalDuplication(event.currentTarget.checked)}
            />
            <span>
              <span>Use experimental virtual mirroring (Wayland only)</span>
              <small>
                Applies to Wayland connects only. For X11 this option is unavailable.
              </small>
            </span>
          </label>
        </section>

        <section class="service-controls">
          <button
            class={`svc-btn ${upgradeAvailable() ? "svc-upgrade" : ""}`}
            disabled={
              session.serviceBusy()
              || !session.service().available
              || (!upgradeAvailable() && session.service().installed)
            }
            onClick={() => (upgradeAvailable() ? session.upgradeService() : session.callServiceAction("service_install"))}
            title={upgradeAvailable() ? "Upgrade service to current host build" : "Install user service"}
          >
            <Download size={14} /> {upgradeAvailable() ? "Upgrade" : "Install service"}
          </button>
          <button
            class="svc-btn"
            disabled={session.serviceBusy() || !session.service().available || !session.service().installed}
            onClick={() => session.callServiceAction("service_uninstall")}
            title="Uninstall user service"
          >
            <Trash2 size={14} /> Uninstall
          </button>
          <button
            class="svc-btn"
            disabled={session.serviceBusy() || !session.service().available || !session.service().installed || session.service().active}
            onClick={() => session.callServiceAction("service_start")}
            title="Start service"
          >
            <Play size={14} /> Start
          </button>
          <button
            class="svc-btn"
            disabled={session.serviceBusy() || !session.service().available || !session.service().installed || !session.service().active}
            onClick={() => session.callServiceAction("service_stop")}
            title="Stop service"
          >
            <Square size={14} /> Stop
          </button>
        </section>

        <footer class="status-bar" title={session.service().summary}>
          <div class={`service-status-strip ${session.serviceState()}`}>
            <span class="service-status-main">
              service: {session.service().active ? "running" : session.service().installed ? "stopped" : "not installed"}
            </span>
            <span class="service-status-hint">
              {session.service().active
                ? "Service active: device probing enabled."
                : session.service().installed
                  ? "Service installed but stopped. Click Start."
                  : "Install + Start service to enable probing and streaming."}
            </span>
          </div>
          <span>{session.devices().length} devices ({session.tabletCount()} tablet, {session.phoneCount()} phone)</span>
          <span>
            host: {session.hostProbe().os}/{session.hostProbe().session}/{session.hostProbe().desktop} · capture={session.hostProbe().captureMode} · {session.hostProbe().supported ? "supported" : "unsupported"}
          </span>
          <span>updated: {session.updatedAt()}</span>
        </footer>
      </section>
      <Show when={connectDialogDevice()}>
        {(deviceAccessor) => {
          const device = deviceAccessor();
          const waylandHost = isWaylandPortalHost();
          const virtualMonitorSelected = () => connectDialogMode() === "virtual_monitor";
          const doctor = () => connectDialogDoctor();
          const virtualMonitorAvailable = () => isVirtualMonitorAvailable(doctor() ?? null);
          const virtualMonitorHint = () => {
            if (!virtualMonitorAvailable()) return "Not implemented for current host session yet.";
            if (doctor()?.resolver === "linux_x11_monitor_object_experimental") {
              return "Experimental simulated monitor space on X11 (xrandr --setmonitor); not a true output.";
            }
            return "Creates real additional monitor space on host desktop.";
          };
          const selectedProfile = () =>
            TRAINED_PROFILE_OPTIONS.find((item) => item.id === connectDialogProfileId())
            ?? TRAINED_PROFILE_OPTIONS[0];
          const selectedSize = () => resolveSessionSizeForPreset(device, connectDialogResolutionPresetId()) ?? "auto";
          const selectedEncoder = () => resolveEncoderForProfile(
            resolveProfileId(connectDialogProfileId()),
            connectDialogEncoderMode(),
          );
          return (
            <div class="modal-backdrop" role="dialog" aria-modal="true" aria-label="Select display mode">
              <section class="connect-modal">
                <h3>Connect session</h3>
                <p class="connect-modal-subtitle">{device.model} ({device.serial})</p>
                <Show
                  when={!waylandHost}
                  fallback={(
                    <p class="setup-hint">
                      Wayland mode: {waylandExperimentalDuplication() ? "Virtual mirror (experimental)" : "Virtual monitor (standard)"}
                    </p>
                  )}
                >
                  <label class={`mode-option ${virtualMonitorSelected() ? "selected" : ""} ${!virtualMonitorAvailable() ? "disabled" : ""}`}>
                    <input
                      type="radio"
                      name="display-mode"
                      checked={virtualMonitorSelected()}
                      disabled={!virtualMonitorAvailable()}
                      onChange={() => setConnectDialogMode("virtual_monitor")}
                    />
                    <span>
                      <span>Virtual monitor (extend host desktop)</span>
                      <small>
                        {virtualMonitorHint()}
                      </small>
                    </span>
                  </label>
                  <label class={`mode-option ${connectDialogMode() === "duplicate" ? "selected" : ""}`}>
                    <input
                      type="radio"
                      name="display-mode"
                      checked={connectDialogMode() === "duplicate"}
                      onChange={() => setConnectDialogMode("duplicate")}
                    />
                    <span>
                      <span>Duplicate current screen</span>
                      <small>Works with current host backend.</small>
                    </span>
                  </label>
                </Show>

                <div class="connect-config-grid">
                  <label class="connect-config-field">
                    <span>Trained profile</span>
                    <select
                      value={connectDialogProfileId()}
                      onChange={(event) => setConnectDialogProfileId(event.currentTarget.value)}
                    >
                      <For each={TRAINED_PROFILE_OPTIONS}>
                        {(profile) => (
                          <option value={profile.id}>{profile.label}</option>
                        )}
                      </For>
                    </select>
                    <small>{selectedProfile()?.description ?? "Session profile"}</small>
                  </label>

                  <label class="connect-config-field">
                    <span>Resolution preset</span>
                    <select
                      value={connectDialogResolutionPresetId()}
                      onChange={(event) => setConnectDialogResolutionPresetId(event.currentTarget.value)}
                    >
                      <For each={RESOLUTION_PRESET_OPTIONS}>
                        {(preset) => (
                          <option value={preset.id}>{preset.label}</option>
                        )}
                      </For>
                    </select>
                    <small>Final stream size: {selectedSize()}</small>
                  </label>

                  <label class="connect-config-field">
                    <span>Encoder</span>
                    <select
                      value={connectDialogEncoderMode()}
                      onChange={(event) => setConnectDialogEncoderMode(event.currentTarget.value as ConnectEncoderMode)}
                    >
                      <For each={ENCODER_OPTIONS}>
                        {(option) => (
                          <option value={option.id}>{option.label}</option>
                        )}
                      </For>
                    </select>
                    <small>Active encoder for this connect: {selectedEncoder()}</small>
                  </label>
                </div>

                <Show when={connectDialogDoctorLoading()}>
                  <p class="setup-message">Checking host capabilities...</p>
                </Show>
                <Show when={connectDialogBlockReason()}>
                  {(msg) => <p class="setup-missing">Connect blocked: {msg()}</p>}
                </Show>
                <Show when={!waylandHost && doctor()}>
                  {(d) => <p class="setup-hint">{d().installHint}</p>}
                </Show>
                <div class="connect-modal-actions">
                  <button class="device-btn" onClick={closeConnectDialog}>Cancel</button>
                  <button class="device-btn" onClick={() => void confirmConnect()} disabled={connectDialogBlockReason().length > 0}>
                    Connect
                  </button>
                </div>
              </section>
            </div>
          );
        }}
      </Show>
      <Show when={virtualSetupVisible() && virtualStartupDoctor()}>
        {(doctorAccessor) => {
          const doctor = doctorAccessor();
          return (
            <div class="modal-backdrop" role="dialog" aria-modal="true" aria-label="Virtual desktop setup">
              <section class="connect-modal setup-modal">
                <h3>Virtual desktop setup</h3>
                <p class="connect-modal-subtitle">Host backend: {doctor.hostBackend}</p>
                <p class="setup-message">{doctor.message}</p>
                <p class="setup-hint">{doctor.installHint}</p>
                <Show when={doctor.missingDeps.length > 0}>
                  <p class="setup-missing">Missing: {doctor.missingDeps.join(", ")}</p>
                </Show>
                <div class="connect-modal-actions">
                  <button class="device-btn" onClick={closeVirtualSetup} disabled={virtualSetupInstalling()}>
                    Cancel
                  </button>
                  <button class="device-btn" onClick={() => void installVirtualDeps()} disabled={virtualSetupInstalling()}>
                    <Show when={virtualSetupInstalling()} fallback={"Install deps"}>
                      <><Loader2 size={13} class="spinning" /> Installing...</>
                    </Show>
                  </button>
                </div>
              </section>
            </div>
          );
        }}
      </Show>
      <Show when={virtualInstallVisible()}>
        <div class="modal-backdrop" role="dialog" aria-modal="true" aria-label="Installing dependencies">
          <section class="connect-modal setup-modal install-modal">
            <h3>Installing virtual desktop dependencies</h3>
            <p class="connect-modal-subtitle">Elevation is required (root/pkexec prompt).</p>
            <div class="install-progress">
              <div class={`install-progress-bar ${virtualInstallStatus().running ? "running" : virtualInstallStatus().success ? "ok" : "bad"}`} />
            </div>
            <p class={`setup-message ${virtualInstallStatus().done ? (virtualInstallStatus().success ? "install-ok" : "install-bad") : ""}`}>
              {virtualInstallStatus().done
                ? (virtualInstallStatus().success
                  ? "Installation completed successfully."
                  : "Installation failed.")
                : "Installing..."}
            </p>
            <p class="setup-hint">{virtualInstallStatus().message}</p>
            <textarea
              class="install-log"
              readOnly
              value={virtualInstallStatus().logs.join("\n")}
              aria-label="Installer terminal output"
            />
            <div class="connect-modal-actions">
              <button class="device-btn" disabled={!virtualInstallStatus().done} onClick={closeVirtualInstallModal}>
                {virtualInstallStatus().done ? "Close" : "Installing..."}
              </button>
              <button class="device-btn" disabled={!virtualInstallStatus().done} onClick={() => void session.refreshSnapshot()}>
                Refresh
              </button>
            </div>
          </section>
        </div>
      </Show>
    </main>
  );
}
function isVirtualMonitorResolver(resolver: string | undefined): boolean {
  return resolver === "linux_x11_real_output";
}

function isVirtualMonitorAvailable(doctor: VirtualDoctor | null): boolean {
  if (!doctor?.ok) return false;
  if (isVirtualMonitorResolver(doctor.resolver)) return true;
  return false;
}
