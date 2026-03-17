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
import type {
  CaptureBackend,
  ConnectEncoderMode,
  ConnectSessionConfig,
  DeviceBasic,
  TrainedProfile,
  VirtualDepsInstallStatus,
  VirtualDoctor,
} from "./types";
import { HostApiManager } from "./managers/hostApiManager";
import { createSessionManager } from "./managers/sessionManager";
import connectResolutionPresets from "./config/connect-resolution-presets.json";
import connectEncoderOptions from "./config/connect-encoder-options.json";

function BatteryIcon(props: { readonly level: number | null; readonly charging: boolean }) {
  if (props.charging) return <BatteryCharging size={14} />;
  if (props.level === null) return <BatteryMedium size={14} />;
  if (props.level >= 80) return <BatteryFull size={14} />;
  if (props.level >= 30) return <BatteryMedium size={14} />;
  return <BatteryLow size={14} />;
}

function DeviceTypeIcon(props: { readonly type: string }) {
  return props.type === "Tablet" ? <Tablet size={16} /> : <Smartphone size={16} />;
}

type DisplayMode = "virtual_monitor" | "duplicate";
const CONNECT_MODE_STORAGE_KEY = "wbeam.connect.mode.by.serial";
const WAYLAND_EXPERIMENTAL_DUPLICATION_STORAGE_KEY = "wbeam.connect.experimental.dup.wayland";

type ResolutionPresetEntry = {
  id: string;
  label: string;
  kind: "device_max" | "device_current" | "fixed";
  size?: string;
};

const RESOLUTION_PRESET_DATA = connectResolutionPresets as {
  defaultPresetId?: string;
  presets?: ResolutionPresetEntry[];
};

const ENCODER_OPTION_DATA = connectEncoderOptions as {
  defaultEncoderMode?: ConnectEncoderMode;
  options?: { id: ConnectEncoderMode; label: string }[];
};

const RESOLUTION_PRESET_OPTIONS: ResolutionPresetEntry[] = RESOLUTION_PRESET_DATA.presets ?? [];
const RESOLUTION_PRESET_IDS = new Set(RESOLUTION_PRESET_OPTIONS.map((item) => item.id));
function resolveDefaultResolutionPresetId(): string {
  const configuredId = RESOLUTION_PRESET_DATA.defaultPresetId;
  if (configuredId && RESOLUTION_PRESET_IDS.has(configuredId)) {
    return configuredId;
  }
  return RESOLUTION_PRESET_OPTIONS[0]?.id ?? "device_max";
}
const RESOLUTION_PRESET_DEFAULT_ID = resolveDefaultResolutionPresetId();

const ENCODER_OPTIONS = ENCODER_OPTION_DATA.options ?? [
  { id: "h264" as const, label: "H.264" },
  { id: "h265" as const, label: "H.265 / HEVC" },
  { id: "rawpng" as const, label: "RAW PNG (experimental)" },
];
const ENCODER_OPTION_IDS = new Set(ENCODER_OPTIONS.map((item) => item.id));
const ENCODER_DEFAULT_MODE = ENCODER_OPTION_IDS.has(ENCODER_OPTION_DATA.defaultEncoderMode ?? "h264")
  ? (ENCODER_OPTION_DATA.defaultEncoderMode as ConnectEncoderMode)
  : "h264";

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
  const regex = /^(\d{3,5})x(\d{3,5})$/;
  const match = regex.exec(value.trim());
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

type ServiceStatus = { active: boolean; installed: boolean };

function resolveDeviceVersionStatus(
  device: DeviceBasic,
  service: ServiceStatus,
  daemonVersionKnown: boolean,
): { cls: "ok" | "warn" | "bad"; label: string } {
  if (!device.apkInstalled) return { cls: "warn", label: "APK missing" };
  if (!service.installed) return { cls: "warn", label: "Install desktop service first" };
  if (!service.active) return { cls: "warn", label: "Start desktop service to verify" };
  if (!daemonVersionKnown) return { cls: "warn", label: "Daemon unreachable - cannot verify" };
  if (device.apkMatchesDaemon) return { cls: "ok", label: "Version match" };
  return { cls: "bad", label: "Update required" };
}

function resolveConnectDisabledReason(params: {
  device: DeviceBasic;
  deviceBusy: boolean;
  waylandPortalHost: boolean;
  deviceActionBusy: string[];
  serviceActive: boolean;
  daemonVersionKnown: boolean;
}): string {
  const { device, deviceBusy, waylandPortalHost, deviceActionBusy, serviceActive, daemonVersionKnown } = params;
  if (deviceBusy) return "This device action is already in progress";
  if (waylandPortalHost) {
    const otherConnectInFlight = deviceActionBusy
      .some((entry) => entry.endsWith(":connect") && !entry.startsWith(`${device.serial}:`));
    if (otherConnectInFlight) {
      return "Another Wayland portal connect is in progress";
    }
  }
  if (!serviceActive) return "Desktop service must be running";
  if (!daemonVersionKnown) return "Daemon API unreachable";
  if (!device.apkInstalled) return "APK is not installed on this device";
  if (!device.apkMatchesDaemon) return "APK version must match daemon version";
  if (device.streamState === "STREAMING") return "Device is already streaming";
  if (device.streamState === "CONNECTING") return "Device is already connecting";
  return "";
}

function resolveDisconnectDisabledReason(device: DeviceBasic, deviceBusy: boolean, serviceActive: boolean): string {
  if (deviceBusy) return "This device action is already in progress";
  if (!serviceActive) return "Desktop service must be running";
  if (device.streamState !== "STREAMING" && device.streamState !== "CONNECTING") {
    return "Device is not streaming";
  }
  return "";
}

function serviceStatusText(service: ServiceStatus): string {
  if (service.active) return "running";
  if (service.installed) return "stopped";
  return "not installed";
}

function serviceStatusHint(service: ServiceStatus): string {
  if (service.active) return "Service active: device probing enabled.";
  if (service.installed) return "Service installed but stopped. Click Start.";
  return "Install + Start service to enable probing and streaming.";
}

function connectTitle(connectBusy: boolean, connectDisabled: boolean, reason: string): string {
  if (connectBusy) return "Connecting...";
  if (connectDisabled) return `Connect blocked: ${reason}`;
  return "Start stream for this device";
}

function disconnectTitle(disconnectBusy: boolean, disconnectDisabled: boolean, reason: string): string {
  if (disconnectBusy) return "Disconnecting...";
  if (disconnectDisabled) return reason;
  return "Stop stream for this device";
}

function installProgressBarClass(status: VirtualDepsInstallStatus): string {
  if (status.running) return "install-progress-bar running";
  if (status.success) return "install-progress-bar ok";
  return "install-progress-bar bad";
}

function installStatusClass(status: VirtualDepsInstallStatus): string {
  if (!status.done) return "setup-message";
  return status.success ? "setup-message install-ok" : "setup-message install-bad";
}

function installStatusText(status: VirtualDepsInstallStatus): string {
  if (!status.done) return "Installing...";
  return status.success ? "Installation completed successfully." : "Installation failed.";
}

export default function App() {
  const api = new HostApiManager();
  const session = createSessionManager(api);
  const [mode, setMode] = createSignal<"basic" | "advanced">("basic");
  const [hostName, setHostName] = createSignal("unknown-host");
  const [connectDialogDevice, setConnectDialogDevice] = createSignal<DeviceBasic | null>(null);
  const [connectDialogMode, setConnectDialogMode] = createSignal<DisplayMode>("virtual_monitor");
  const [connectDialogResolutionPresetId, setConnectDialogResolutionPresetId] = createSignal<string>(RESOLUTION_PRESET_DEFAULT_ID);
  const [connectDialogEncoderMode, setConnectDialogEncoderMode] = createSignal<ConnectEncoderMode | "">("");
  const [connectDialogProfileKey, setConnectDialogProfileKey] = createSignal<string>("");
  const [connectDialogCaptureBackend, setConnectDialogCaptureBackend] = createSignal<CaptureBackend | "">("");
  const [trainedProfiles, setTrainedProfiles] = createSignal<TrainedProfile[]>([]);
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

  function connectDisabledReason(device: DeviceBasic): string {
    return resolveConnectDisabledReason({
      device,
      deviceBusy: isDeviceBusy(device),
      waylandPortalHost: isWaylandPortalHost(),
      deviceActionBusy: session.deviceActionBusy(),
      serviceActive: session.service().active,
      daemonVersionKnown: daemonVersionKnown(),
    });
  }

  function disconnectDisabledReason(device: DeviceBasic): string {
    return resolveDisconnectDisabledReason(device, isDeviceBusy(device), session.service().active);
  }

  function modeLabel(): "Basic" | "Advanced" {
    if (mode() === "basic") return "Basic";
    return "Advanced";
  }

  function emptyDevicesMessage(): string {
    if (session.service().active) return "No connected ADB devices.";
    return "Probing paused until desktop service is running.";
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

  function recommendedProfileKey(_profiles: TrainedProfile[]): string {
    return "";
  }

  async function refreshTrainedProfiles(): Promise<void> {
    const backend = connectDialogCaptureBackend();
    // Profiles are backend-scoped; abstract selection cannot determine a namespace.
    if (!backend || backend === "auto") {
      setTrainedProfiles([]);
      setConnectDialogProfileKey("");
      return;
    }
    try {
      const profiles = await api.listTrainedProfiles(backend);
      setTrainedProfiles(profiles);
      const selected = connectDialogProfileKey();
      const selectedExists = profiles.some((profile) => profile.key === selected);
      if (selected.length > 0 && !selectedExists) {
        setConnectDialogProfileKey("");
      }
    } catch {
      setTrainedProfiles([]);
    }
  }

  function openConnectDialog(device: DeviceBasic): void {
    const isWaylandHost = isWaylandPortalHost();
    let saved: DisplayMode = "virtual_monitor";
    if (!isWaylandHost) {
      saved = loadSavedDisplayMode(device.serial) ?? "virtual_monitor";
    }
    setConnectDialogMode(saved);
    setConnectDialogResolutionPresetId(RESOLUTION_PRESET_DEFAULT_ID);
    setConnectDialogEncoderMode("");
    setConnectDialogProfileKey("");
    setConnectDialogCaptureBackend("");
    void refreshTrainedProfiles();
    setConnectDialogDoctor(null);
    setConnectDialogDoctorLoading(!isWaylandHost);
    setConnectDialogBlockReason(connectDisabledReason(device));
    setConnectDialogDevice(device);
    if (isWaylandHost) {
      return;
    }
    void api
      .getVirtualDoctor(device)
      .then((doctor) => {
        setConnectDialogDoctor(doctor);
      })
      .catch(() => {
        setConnectDialogDoctor(null);
      })
      .finally(() => {
        setConnectDialogDoctorLoading(false);
      });
  }

  function closeConnectDialog(): void {
    setConnectDialogDevice(null);
    setConnectDialogDoctor(null);
    setConnectDialogDoctorLoading(false);
    setConnectDialogBlockReason("");
  }

  async function resolveBackendMode(
    device: DeviceBasic,
    waylandHost: boolean,
  ): Promise<"virtual_monitor" | "virtual_mirror" | "duplicate" | null> {
    if (waylandHost) {
      return waylandExperimentalDuplication() ? "virtual_mirror" : "virtual_monitor";
    }
    const chosenMode = connectDialogMode();
    if (chosenMode === "virtual_monitor") {
      const doctor = connectDialogDoctor() ?? await api.getVirtualDoctor(device);
      if (!isVirtualMonitorAvailable(doctor)) {
        session.setError(
          "Virtual monitor mode is unavailable in current host session. Use Duplicate mode."
        );
        return null;
      }
      return "virtual_monitor";
    }
    saveDisplayMode(device.serial, chosenMode);
    return "duplicate";
  }

function resolveEncoderMode(
  selectedProfile: TrainedProfile | null,
  encoderValue: ConnectEncoderMode | "",
): ConnectEncoderMode | undefined {
  if (selectedProfile) return undefined;
  if (encoderValue === "") return undefined;
  return encoderValue;
}

  function resolveCaptureBackend(): CaptureBackend | undefined {
    const captureBackend = connectDialogCaptureBackend();
    if (captureBackend === "" || captureBackend === "auto") return undefined;
    return captureBackend;
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
      const backendMode = await resolveBackendMode(device, waylandHost);
      if (!backendMode) return;

      const resolvedSize = resolveSessionSizeForPreset(device, connectDialogResolutionPresetId());
      const encoderValue = connectDialogEncoderMode();
      const profilesForEncoder = trainedProfiles().filter((p) => p.encoder === encoderValue);
      const selectedProfile = profilesForEncoder.find((p) => p.key === connectDialogProfileKey()) ?? null;
      const connectConfig: ConnectSessionConfig = {
        profileName: selectedProfile?.key,
        encoder: resolveEncoderMode(selectedProfile, encoderValue),
        size: resolvedSize,
        captureBackend: resolveCaptureBackend(),
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
      globalThis.clearInterval(virtualInstallPollTimer);
      virtualInstallPollTimer = null;
    }
  }

  function closeVirtualInstallModal(): void {
    if (virtualInstallStatus().running) return;
    setVirtualInstallVisible(false);
  }

  function installServiceOrUpgrade(): Promise<void> {
    if (upgradeAvailable()) return session.upgradeService();
    return session.callServiceAction("service_install");
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
      virtualInstallPollTimer = globalThis.setInterval(() => {
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
    await refreshTrainedProfiles();
    try {
      const doctor = await api.getVirtualDoctor();
      setVirtualStartupDoctor(doctor);
    } catch {
      // Non-blocking check; connect flow still performs per-device guard.
    }

    const timer = globalThis.setInterval(() => {
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
    globalThis.addEventListener("focus", onFocus);
    document.addEventListener("visibilitychange", onVisibilityChange);

    onCleanup(() => {
      globalThis.clearInterval(timer);
      globalThis.removeEventListener("focus", onFocus);
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
            <p class="mode-line">Mode: {modeLabel()}</p>
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
            {emptyDevicesMessage()}
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
                    const st = resolveDeviceVersionStatus(device, session.service(), daemonVersionKnown());
                    const statusHint = session.service().active
                      ? "APK version should match daemon build revision"
                      : "Version check is paused until desktop service is running";
                    return (
                      <span class={`status ${st.cls}`} title={statusHint}>
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
                    const connectActionTitle = connectTitle(connectBusy, connectDisabled, connectReason);
                    const disconnectActionTitle = disconnectTitle(disconnectBusy, disconnectDisabled, disconnectReason);
                    const connectLabel = connectBusy ? "Connecting..." : "Connect";
                    const disconnectLabel = disconnectBusy ? "Stopping..." : "Disconnect";
                    return (
                      <>
                        <button
                          class="device-btn"
                          title="Refresh this device state"
                          disabled={session.refreshing() || isDeviceBusy(device)}
                          onClick={() => session.refreshSnapshot()}
                        >
                          <RefreshCw size={13} />
                          <span>Refresh</span>
                        </button>
                        <button
                          class="device-btn"
                          title={connectActionTitle}
                          disabled={connectBusy || connectDisabled}
                          onClick={() => openConnectDialog(device)}
                        >
                          <Show when={connectBusy} fallback={<Link2 size={13} />}>
                            <Loader2 size={13} class="spinning" />
                          </Show>
                          <span>{connectLabel}</span>
                        </button>
                        <button
                          class="device-btn"
                          title={disconnectActionTitle}
                          disabled={disconnectDisabled}
                          onClick={() => session.disconnectDevice(device)}
                        >
                          <Show when={disconnectBusy} fallback={<Unlink2 size={13} />}>
                            <Loader2 size={13} class="spinning" />
                          </Show>
                          <span>{disconnectLabel}</span>
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
              Use experimental virtual mirroring (Wayland only)
              {" "}
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
            onClick={() => installServiceOrUpgrade()}
            title={upgradeAvailable() ? "Upgrade service to current host build" : "Install user service"}
          >
            <Download size={14} />
            <span>{upgradeAvailable() ? "Upgrade" : "Install service"}</span>
          </button>
          <button
            class="svc-btn"
            disabled={session.serviceBusy() || !session.service().available || !session.service().installed}
            onClick={() => session.callServiceAction("service_uninstall")}
            title="Uninstall user service"
          >
            <Trash2 size={14} />
            <span>Uninstall</span>
          </button>
          <button
            class="svc-btn"
            disabled={session.serviceBusy() || !session.service().available || !session.service().installed || session.service().active}
            onClick={() => session.callServiceAction("service_start")}
            title="Start service"
          >
            <Play size={14} />
            <span>Start</span>
          </button>
          <button
            class="svc-btn"
            disabled={session.serviceBusy() || !session.service().available || !session.service().installed || !session.service().active}
            onClick={() => session.callServiceAction("service_stop")}
            title="Stop service"
          >
            <Square size={14} />
            <span>Stop</span>
          </button>
        </section>

        <footer class="status-bar" title={session.service().summary}>
          <div class={`service-status-strip ${session.serviceState()}`}>
            <span class="service-status-main">
              service: {serviceStatusText(session.service())}
            </span>
            <span class="service-status-hint">
              {serviceStatusHint(session.service())}
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
          const virtualMonitorUnavailable = () => !virtualMonitorAvailable();
          const virtualMonitorHint = () => {
            if (!virtualMonitorAvailable()) return "Not implemented for current host session yet.";
            if (doctor()?.resolver === "linux_x11_monitor_object_experimental") {
              return "Experimental simulated monitor space on X11 (xrandr --setmonitor); not a true output.";
            }
            return "Creates real additional monitor space on host desktop.";
          };
          const selectedSize = () => resolveSessionSizeForPreset(device, connectDialogResolutionPresetId()) ?? "auto";
          const backendChosen = () => connectDialogCaptureBackend() !== "";
          const concreteBackendChosen = () => {
            const b = connectDialogCaptureBackend();
            return b !== "" && b !== "auto";
          };
          const codecChosen = () => connectDialogEncoderMode() !== "";
          const profilesForCodec = () => trainedProfiles().filter((p) => p.encoder === connectDialogEncoderMode());
          const selectedProfile = () => profilesForCodec().find((profile) => profile.key === connectDialogProfileKey()) ?? null;
          return (
            <dialog open class="modal-backdrop" aria-label="Select display mode">
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
                  <label class={`mode-option ${virtualMonitorSelected() ? "selected" : ""} ${virtualMonitorUnavailable() ? "disabled" : ""}`}>
                    <input
                      type="radio"
                      name="display-mode"
                      checked={virtualMonitorSelected()}
                      disabled={virtualMonitorUnavailable()}
                      onChange={() => setConnectDialogMode("virtual_monitor")}
                    />
                    <span>
                      Virtual monitor (extend host desktop)
                      {" "}
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
                      Duplicate current screen
                      {" "}
                      <small>Works with current host backend.</small>
                    </span>
                  </label>
                </Show>

                <div class="connect-config-grid">
                  <label class="connect-config-field">
                    <span>Capture backend</span>
                    <select
                      value={connectDialogCaptureBackend()}
                      aria-label="Capture backend"
                      onChange={(event) => {
                        setConnectDialogCaptureBackend(event.currentTarget.value as CaptureBackend | "");
                        setConnectDialogEncoderMode("");
                        setConnectDialogProfileKey("");
                        void refreshTrainedProfiles();
                      }}
                    >
                      <option value="">— select backend —</option>
                      <option value="auto">Auto (use host default)</option>
                      <option value="evdi">EVDI — direct kernel capture (fastest, no 60 fps cap)</option>
                      <option value="wayland_portal">Wayland portal (default on Wayland)</option>
                    </select>
                    <small>
                      {connectDialogCaptureBackend() === "evdi"
                        ? "Requires: sudo modprobe evdi initial_device_count=1"
                        : `Host detected: ${session.hostProbe().captureMode}`}
                    </small>
                  </label>

                  <label class={`connect-config-field${backendChosen() ? "" : " field-locked"}`}>
                    <span>Codec</span>
                    <select
                      value={connectDialogEncoderMode()}
                      aria-label="Codec"
                      disabled={!backendChosen()}
                      onChange={(event) => {
                        setConnectDialogEncoderMode(event.currentTarget.value as ConnectEncoderMode | "");
                        setConnectDialogProfileKey("");
                      }}
                    >
                      <option value="">— select codec —</option>
                      <For each={ENCODER_OPTIONS}>
                        {(option) => (
                          <option value={option.id}>{option.label}</option>
                        )}
                      </For>
                    </select>
                    <small>{codecChosen() ? `Selected: ${connectDialogEncoderMode()}` : "Choose backend first"}</small>
                  </label>

                  <label class={`connect-config-field${(codecChosen() && concreteBackendChosen()) ? "" : " field-locked"}`}>
                    <span>Profile</span>
                    <select
                      value={connectDialogProfileKey()}
                      aria-label="Profile"
                      disabled={!codecChosen() || !concreteBackendChosen()}
                      onChange={(event) => setConnectDialogProfileKey(event.currentTarget.value)}
                    >
                      <option value="">None (manual settings)</option>
                      <For each={profilesForCodec()}>
                        {(profile) => (
                          <option value={profile.key}>
                            {`${profile.name} (${profile.fps}fps, ${profile.bitrateKbps}kbps, ${profile.workload})`}
                          </option>
                        )}
                      </For>
                    </select>
                    <small>
                      {(() => {
                        const profile = selectedProfile();
                        if (profile) {
                          const p = profile;
                          return `${p.fps}fps ${p.bitrateKbps}kbps — ${p.objective}, ${p.workload}`;
                        }
                        if (!backendChosen()) return "Choose backend first";
                        if (!concreteBackendChosen()) return "Select a specific backend (not Auto) to use profiles";
                        if (!codecChosen()) return "Choose codec first";
                        return "Optional: use manual settings without a profile";
                      })()}
                    </small>
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
                  <button class="device-btn" onClick={() => void confirmConnect()} disabled={connectDialogBlockReason().length > 0 || !backendChosen() || !codecChosen()}>
                    Connect
                  </button>
                </div>
              </section>
            </dialog>
          );
        }}
      </Show>
      <Show when={virtualSetupVisible() && virtualStartupDoctor()}>
        {(doctorAccessor) => {
          const doctor = doctorAccessor();
          return (
            <dialog open class="modal-backdrop" aria-label="Virtual desktop setup">
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
            </dialog>
          );
        }}
      </Show>
      <Show when={virtualInstallVisible()}>
        <dialog open class="modal-backdrop" aria-label="Installing dependencies">
          <section class="connect-modal setup-modal install-modal">
            <h3>Installing virtual desktop dependencies</h3>
            <p class="connect-modal-subtitle">Elevation is required (root/pkexec prompt).</p>
            <div class="install-progress">
              <div class={installProgressBarClass(virtualInstallStatus())} />
            </div>
            <p class={installStatusClass(virtualInstallStatus())}>
              {installStatusText(virtualInstallStatus())}
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
        </dialog>
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
