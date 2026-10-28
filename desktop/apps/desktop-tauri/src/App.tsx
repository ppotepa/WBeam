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
  QualityMode,
  TrainedProfile,
  VirtualDepsInstallStatus,
  VirtualDoctor,
} from "./types";
import { HostApiManager } from "./managers/hostApiManager";
import { createSessionManager } from "./managers/sessionManager";
import { resolveSupportedBackend, type SupportedCaptureBackend } from "./managers/backendPolicy";
import { createPollingLoop } from "./managers/pollingLoop";
import { validateConnectForm } from "./managers/connectValidation";
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

// X11/Xorg are legacy implementation details, not supported product modes.
// Keep technical data intact for diagnostics, but never offer the legacy
// capture path or surface its backend identifiers in the release UI.
function safeUiText(value: string, legacyLabel = "Legacy capture is unsupported. Use EVDI or Wayland Portal."): string {
  return /x11|xorg|xrandr/i.test(value) ? legacyLabel : value;
}

function supportedCaptureLabel(mode: string): string {
  if (mode === "evdi") return "EVDI";
  if (mode === "wayland_portal") return "Wayland Portal";
  if (mode === "unknown" || !mode) return "unknown";
  return "unsupported legacy/unknown backend";
}

function isSupportedCaptureMode(mode: string): boolean {
  return mode === "evdi" || mode === "wayland_portal";
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
  const [connectDialogQualityMode, setConnectDialogQualityMode] = createSignal<QualityMode>("manual");
  const [connectDialogManualFps, setConnectDialogManualFps] = createSignal(60);
  const [connectDialogManualMbps, setConnectDialogManualMbps] = createSignal(10);
  const [connectDialogProfileKey, setConnectDialogProfileKey] = createSignal<string>("");
  const [connectDialogCaptureBackend, setConnectDialogCaptureBackend] = createSignal<CaptureBackend | "">("");
  const [trainedProfiles, setTrainedProfiles] = createSignal<TrainedProfile[]>([]);
  const [connectDialogDoctor, setConnectDialogDoctor] = createSignal<VirtualDoctor | null>(null);
  const [connectDialogDoctorLoading, setConnectDialogDoctorLoading] = createSignal(false);
  const [connectDialogBlockReason, setConnectDialogBlockReason] = createSignal("");
  const [autoConnectBusy, setAutoConnectBusy] = createSignal(false);
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
  const selectedCaptureBackend = () => resolveSupportedBackend(
    connectDialogCaptureBackend(), session.hostProbe(),
  );
  const connectFormErrors = () => validateConnectForm({
    captureBackend: selectedCaptureBackend(),
    encoder: connectDialogEncoderMode(),
    qualityMode: connectDialogQualityMode(),
    profileKey: connectDialogProfileKey(),
    profiles: trainedProfiles(),
    manualFps: connectDialogManualFps(),
    manualMbps: connectDialogManualMbps(),
  });
  let profilesRequestId = 0;
  let connectDialogRequestId = 0;
  let virtualInstallPollTimer: number | null = null;
  let installPollingGeneration = 0;
  let installPollingActive = false;
  const [evdiWizardVisible, setEvdiWizardVisible] = createSignal(false);
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

  function autoConnectDisabledReason(): string {
    if (!session.service().active) return "Desktop service must be running";
    if (session.devices().length === 0) return "No connected devices";
    const hasConnectable = session.devices().some((device) => connectDisabledReason(device).length === 0);
    if (!hasConnectable) return "No devices eligible for connect";
    return "";
  }

  async function autoConnectAll(): Promise<void> {
    if (autoConnectBusy()) return;
    const blocked = autoConnectDisabledReason();
    if (blocked.length > 0) {
      session.setError(blocked);
      return;
    }

    setAutoConnectBusy(true);
    session.setError("");
    const errors: string[] = [];
    for (const device of session.devices()) {
      const reason = connectDisabledReason(device);
      if (reason.length > 0) continue;
      const size = resolveSessionSizeForPreset(device, "device_max");
      const connectConfig: ConnectSessionConfig = {
        captureBackend: "evdi",
        manualFps: 60,
        manualBitrateKbps: 20000,
        size,
      };
      try {
        const result = await session.connectDevice(device, "virtual_monitor", connectConfig);
        if (!result.ok) errors.push(`${device.serial}: ${result.error}`);
      } catch (err) {
        errors.push(`${device.serial}: ${String(err)}`);
      }
    }
    if (errors.length > 0) {
      session.setError(`Auto-connect issues: ${errors.join("; ")}`);
    }
    await session.refreshSnapshot({ silent: true, forceDevices: true });
    setAutoConnectBusy(false);
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
    const requestId = ++profilesRequestId;
    const backend = connectDialogCaptureBackend();
    // Profiles are backend-scoped; a concrete backend is required.
    if (!backend || backend === "auto") {
      setTrainedProfiles([]);
      setConnectDialogProfileKey("");
      return;
    }
    try {
      const profiles = await api.listTrainedProfiles(backend);
      if (requestId !== profilesRequestId || !connectDialogDevice()) return;
      setTrainedProfiles(profiles);
      const selected = connectDialogProfileKey();
      if (selected.length > 0 && !profiles.some((profile) => profile.key === selected)) {
        setConnectDialogProfileKey("");
      }
    } catch (err) {
      if (requestId !== profilesRequestId || !connectDialogDevice()) return;
      setTrainedProfiles([]);
      session.setError(`Unable to load profiles for ${backend}: ${String(err)}`);
    }
  }

  function openConnectDialog(device: DeviceBasic): void {
    const requestId = ++connectDialogRequestId;
    profilesRequestId += 1;
    setTrainedProfiles([]);
    const isWaylandHost = isWaylandPortalHost();
    let saved: DisplayMode = "virtual_monitor";
    if (!isWaylandHost) {
      saved = loadSavedDisplayMode(device.serial) ?? "virtual_monitor";
    }
    setConnectDialogMode(saved);
    setConnectDialogResolutionPresetId(RESOLUTION_PRESET_DEFAULT_ID);
    setConnectDialogEncoderMode("");
    setConnectDialogQualityMode("manual");
    setConnectDialogManualFps(60);
    setConnectDialogManualMbps(10);
    setConnectDialogProfileKey("");
    setConnectDialogCaptureBackend("");
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
        if (requestId === connectDialogRequestId) setConnectDialogDoctor(doctor);
      })
      .catch(() => {
        if (requestId === connectDialogRequestId) setConnectDialogDoctor(null);
      })
      .finally(() => {
        if (requestId === connectDialogRequestId) setConnectDialogDoctorLoading(false);
      });
  }

  function closeConnectDialog(): void {
    connectDialogRequestId += 1;
    profilesRequestId += 1;
    setConnectDialogDevice(null);
    setConnectDialogDoctor(null);
    setConnectDialogDoctorLoading(false);
    setConnectDialogBlockReason("");
  }

  async function resolveBackendMode(
    device: DeviceBasic,
    captureBackend: SupportedCaptureBackend,
  ): Promise<"virtual_monitor" | "virtual_mirror" | "duplicate" | null> {
    if (captureBackend === "wayland_portal") {
      return waylandExperimentalDuplication() ? "virtual_mirror" : "virtual_monitor";
    }
    const chosenMode = connectDialogMode();
    if (chosenMode === "virtual_monitor") {
      // Legacy X11 virtual doctor is not a gate for the independent EVDI backend.
      if (!session.hostProbe().availableBackends.includes("evdi")) {
        session.setError("EVDI is unavailable. Check the kernel module and host permissions.");
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

  function resolveCaptureBackend(): SupportedCaptureBackend | null {
    return selectedCaptureBackend();
  }

  async function confirmConnect(): Promise<void> {
    const selectedDevice = connectDialogDevice();
    if (!selectedDevice) return;
    const device = session.devices().find((candidate) => candidate.serial === selectedDevice.serial);
    if (!device) {
      session.setError("Device disconnected while configuring the session. Refresh devices and retry.");
      return;
    }
    const errors = connectFormErrors();
    if (errors.length > 0) {
      session.setError(errors.join("; "));
      return;
    }
    const captureBackend = resolveCaptureBackend();
    if (!captureBackend) {
      session.setError("Selected capture backend is unavailable. Choose EVDI or Wayland Portal.");
      return;
    }
    const blockedReason = connectDisabledReason(device);
    if (blockedReason.length > 0) {
      setConnectDialogBlockReason(blockedReason);
      session.setError(blockedReason);
      return;
    }
    try {
      const backendMode = await resolveBackendMode(device, captureBackend);
      if (!backendMode) return;

      const qualityMode = connectDialogQualityMode();
      const isAdaptive = qualityMode === "adaptive";
      const isManual = qualityMode === "manual";
      const resolvedSize = isAdaptive
        ? undefined
        : resolveSessionSizeForPreset(device, connectDialogResolutionPresetId());
      const encoderValue = connectDialogEncoderMode();
      const profilesForEncoder = (isAdaptive || isManual)
        ? []
        : trainedProfiles().filter((p) => p.encoder === encoderValue);
      const selectedProfile = profilesForEncoder.find((p) => p.key === connectDialogProfileKey()) ?? null;
      const connectConfig: ConnectSessionConfig = {
        runtimeProfile: isAdaptive ? "adaptive" : undefined,
        profileName: (!isAdaptive && !isManual) ? selectedProfile?.key : undefined,
        encoder: resolveEncoderMode(selectedProfile, encoderValue),
        size: resolvedSize,
        captureBackend,
        manualFps: isManual ? connectDialogManualFps() : undefined,
        manualBitrateKbps: isManual ? connectDialogManualMbps() * 1000 : undefined,
      };

      const result = await session.connectDevice(device, backendMode, connectConfig);
      if (result.ok) closeConnectDialog();
    } catch (err) {
      session.setError(`Connect mode validation failed: ${String(err)}`);
    }
  }

  async function openVirtualSetup(): Promise<void> {
    setVirtualSetupVisible(true);
    setVirtualStartupDoctor({
      ok: false, message: "Checking EVDI prerequisites...", actionable: false,
      hostBackend: "evdi", resolver: "evdi_kernel", missingDeps: [], installHint: "",
    });
    try {
      const doctor = await api.getEvdiDepsDoctor();
      if (virtualSetupVisible()) setVirtualStartupDoctor(doctor);
    } catch (err) {
      if (!virtualSetupVisible()) return;
      setVirtualStartupDoctor({
        ok: false, message: `EVDI diagnostics unavailable: ${String(err)}`, actionable: false,
        hostBackend: "evdi", resolver: "evdi_kernel", missingDeps: [],
        installHint: "Check the installed EVDI diagnostic scripts and logs.",
      });
    }
  }

  function closeVirtualSetup(): void {
    if (virtualSetupInstalling()) return;
    setVirtualSetupVisible(false);
  }

  function hasActiveStreams(): boolean {
    return session.devices().some((device) =>
      device.streamState === "STREAMING" || device.streamState === "CONNECTING",
    );
  }

  function stopVirtualInstallPolling(): void {
    installPollingGeneration += 1;
    installPollingActive = false;
    if (virtualInstallPollTimer !== null) {
      globalThis.clearTimeout(virtualInstallPollTimer);
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
    const generation = installPollingGeneration;
    installPollingActive = true;
    try {
      await api.startVirtualDepsInstall();
      const poll = async () => {
        if (!installPollingActive || generation !== installPollingGeneration) return;
        try {
          const status = await api.getVirtualDepsInstallStatus();
          // Responses from cancelled/previous installation attempts are stale.
          if (!installPollingActive || generation !== installPollingGeneration) return;
          setVirtualInstallStatus(status);
          if (status.done) {
            stopVirtualInstallPolling();
            setVirtualSetupInstalling(false);
            if (status.success) {
              const doctor = await api.getEvdiDepsDoctor();
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
          } else {
            // Self-scheduling means there is at most one status request in flight.
            virtualInstallPollTimer = globalThis.setTimeout(() => {
              virtualInstallPollTimer = null;
              void poll();
            }, 600);
          }
        } catch (err) {
          if (generation !== installPollingGeneration) return;
          stopVirtualInstallPolling();
          setVirtualSetupInstalling(false);
          setVirtualInstallStatus({
            running: false,
            done: true,
            success: false,
            message: `Installer status unavailable (installation outcome unknown): ${String(err)}`,
            logs: [...virtualInstallStatus().logs, `[ui][error] ${String(err)}`],
          });
          session.setError("Unable to read installer progress. Check host state before retrying.");
        }
      };
      await poll();
    } catch (err) {
      stopVirtualInstallPolling();
      session.setError(`Dependency installation failed: ${String(err)}`);
      setVirtualInstallStatus({
        running: false,
        done: true,
        success: false,
        message: `Dependency installation failed: ${String(err)}`,
        logs: [...virtualInstallStatus().logs, `[ui][error] ${String(err)}`],
      });
      setVirtualSetupInstalling(false);
    }
  }

  onMount(() => {
    let disposed = false;
    const poller = createPollingLoop({
      delayMs: 1200,
      shouldPoll: () => !document.hidden
        && session.deviceActionBusy().length === 0
        && !session.refreshInFlight(),
      work: () => session.refreshSnapshot({ silent: true, forceDevices: false }),
      onError: (err) => console.error("Background status refresh failed", err),
    });
    const refreshVisible = () => {
      if (disposed || session.deviceActionBusy().length > 0 || session.refreshInFlight()) return;
      // Visibility/manual refresh bypasses device backoff; periodic polls do not.
      void session.refreshSnapshot({ silent: true, forceDevices: true });
    };
    const onFocus = () => refreshVisible();
    const onVisibilityChange = () => {
      if (!document.hidden) refreshVisible();
    };
    globalThis.addEventListener("focus", onFocus);
    document.addEventListener("visibilitychange", onVisibilityChange);

    // Must be registered synchronously while Solid's owner is active,
    // not after awaits inside an async onMount callback.
    onCleanup(() => {
      disposed = true;
      poller.stop();
      globalThis.removeEventListener("focus", onFocus);
      document.removeEventListener("visibilitychange", onVisibilityChange);
      stopVirtualInstallPolling();
    });

    void (async () => {
      try {
        const name = await api.getHostName();
        if (disposed) return;
        const safe = name || "unknown-host";
        setHostName(safe);
        await getCurrentWindow().setTitle(`WBeam - ${safe}`);
      } catch {
        // Window title is optional. Continue probing even if it fails.
      }
      if (disposed) return;
      await session.refreshSnapshot();
      if (disposed) return;
      await refreshTrainedProfiles();
      if (disposed) return;
      try {
        const doctor = await api.getEvdiDepsDoctor();
        if (!disposed) setVirtualStartupDoctor(doctor);
      } catch {
        // Non-blocking check; connect performs its own capability guard.
      } finally {
        if (!disposed) poller.start();
      }
    })();
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
          <p class="error-line" role="alert">{safeUiText(session.error())}</p>
        </Show>
        <Show when={session.refreshError()}>
          <p class="warn-line" role="status">{safeUiText(session.refreshError())}</p>
        </Show>
        <Show when={session.deviceSnapshotStale()}>
          <p class="warn-line" role="status">Device status may be outdated. Last known devices are shown.</p>
        </Show>
        <Show when={!session.loading() && session.devices().length === 0 && !session.error()}>
          <p class="empty-line">
            {emptyDevicesMessage()}
          </p>
        </Show>

        <section class="toolbar">
          <div class="toolbar-row">
            <button
              class="toolbar-btn toolbar-btn-primary"
              title="Connect all devices using EVDI at native resolution, 60 fps, 20 Mbps"
              disabled={autoConnectBusy() || autoConnectDisabledReason().length > 0}
              onClick={() => void autoConnectAll()}
            >
              <Show when={autoConnectBusy()} fallback={<Link2 size={14} />}>
                <Loader2 size={14} class="spinning" />
              </Show>
              <span>{autoConnectBusy() ? "Connecting…" : "Autoconnect all"}</span>
            </button>
            <button
              class="toolbar-btn"
              onClick={() => void openVirtualSetup()}
              title="Diagnose and prepare EVDI on this Linux host"
            >
              <Settings size={14} />
              <span>Host setup</span>
            </button>
          </div>
          <Show when={!autoConnectBusy() && autoConnectDisabledReason().length > 0}>
            <p class="toolbar-hint toolbar-hint-warn">{autoConnectDisabledReason()}</p>
          </Show>
          <Show when={isWaylandPortalHost()}>
            <p class="toolbar-hint">Wayland host — auto-connect uses EVDI. Ensure EVDI module is loaded.</p>
          </Show>
        </section>

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
                <Show when={session.deviceActionErrors()[device.serial]}>
                  {(message) => <p class="error-line" role="alert">{safeUiText(message())}</p>}
                </Show>
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
            host: {safeUiText(session.hostProbe().os)}/{safeUiText(session.hostProbe().session, "legacy session")}/{safeUiText(session.hostProbe().desktop, "legacy desktop")} · capture={supportedCaptureLabel(session.hostProbe().captureMode)} · {session.hostProbe().supported && isSupportedCaptureMode(session.hostProbe().captureMode) ? "supported" : "unsupported"}
          </span>
          <span>updated: {session.updatedAt()}</span>
        </footer>
      </section>
      <Show when={connectDialogDevice()}>
        {(deviceAccessor) => {
          const device = deviceAccessor();
          const portalBackendSelected = () => selectedCaptureBackend() === "wayland_portal";
          const virtualMonitorSelected = () => connectDialogMode() === "virtual_monitor";
          const doctor = () => connectDialogDoctor();
          const virtualMonitorAvailable = () => selectedCaptureBackend() === "evdi";
          const virtualMonitorUnavailable = () => !virtualMonitorAvailable();
          const virtualMonitorHint = () => {
            if (!virtualMonitorAvailable()) return "A supported virtual monitor backend is not available for this host.";
            return "Creates real additional monitor space on host desktop.";
          };
          const qualityMode = () => connectDialogQualityMode();
          const isAdaptive = () => qualityMode() === "adaptive";
          const isManual = () => qualityMode() === "manual";
          const isProfile = () => qualityMode() === "profile";
          const selectedSize = () => {
            if (isAdaptive()) return "auto (adaptive)";
            return resolveSessionSizeForPreset(device, connectDialogResolutionPresetId()) ?? "auto";
          };
          const backendChosen = () => selectedCaptureBackend() !== null;
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
                  when={!portalBackendSelected()}
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
                        setTrainedProfiles([]);
                        void refreshTrainedProfiles();
                      }}
                    >
                      <option value="">— select backend —</option>
                      <option value="auto">Auto (available supported backend)</option>
                      <option value="evdi">EVDI — direct kernel capture</option>
                      <option value="wayland_portal">Wayland portal (default on Wayland)</option>
                    </select>
                    <small>
                      {selectedCaptureBackend()
                        ? `Ready: ${selectedCaptureBackend()}`
                        : "Backend unavailable. Run host setup or select an available backend."}
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

                  <div class={`quality-mode-radios${codecChosen() ? "" : " disabled"}`}>
                    <span class="quality-mode-label">Quality mode</span>
                    <label class={`radio-option${isManual() ? " selected" : ""}${!codecChosen() ? " disabled" : ""}`}>
                      <input
                        type="radio"
                        name="quality-mode"
                        value="manual"
                        checked={isManual()}
                        disabled={!codecChosen()}
                        onChange={() => setConnectDialogQualityMode("manual")}
                      />
                      <span>Manual</span>
                    </label>
                    <label class={`radio-option${isAdaptive() ? " selected" : ""}${!codecChosen() ? " disabled" : ""}`}>
                      <input
                        type="radio"
                        name="quality-mode"
                        value="adaptive"
                        checked={isAdaptive()}
                        disabled={!codecChosen()}
                        onChange={() => setConnectDialogQualityMode("adaptive")}
                      />
                      <span>Adaptive</span>
                    </label>
                    <label class={`radio-option${isProfile() ? " selected" : ""}${!codecChosen() ? " disabled" : ""}`}>
                      <input
                        type="radio"
                        name="quality-mode"
                        value="profile"
                        checked={isProfile()}
                        disabled={!codecChosen()}
                        onChange={() => setConnectDialogQualityMode("profile")}
                      />
                      <span>Profile</span>
                    </label>
                  </div>

                  <Show when={isManual()}>
                    <label class="connect-config-field">
                      <span>Target FPS: {connectDialogManualFps()}</span>
                      <input
                        type="range"
                        min="24"
                        max="120"
                        step="1"
                        value={connectDialogManualFps()}
                        onInput={(event) => setConnectDialogManualFps(Number(event.currentTarget.value))}
                      />
                      <small>Range: 24–120 fps</small>
                    </label>
                    <label class="connect-config-field">
                      <span>Target bitrate: {connectDialogManualMbps()} Mbps</span>
                      <input
                        type="range"
                        min="1"
                        max="200"
                        step="1"
                        value={connectDialogManualMbps()}
                        onInput={(event) => setConnectDialogManualMbps(Number(event.currentTarget.value))}
                      />
                      <small>Range: 1–200 Mbps (VBR — encoder uses up to this target)</small>
                    </label>
                  </Show>

                  <Show when={isAdaptive()}>
                    <p class="quality-mode-hint">Host daemon tunes FPS and bitrate dynamically based on device feedback.</p>
                  </Show>

                  <Show when={isProfile()}>
                    <label class={`connect-config-field${(codecChosen() && concreteBackendChosen()) ? "" : " field-locked"}`}>
                      <span>Profile</span>
                      <select
                        value={connectDialogProfileKey()}
                        aria-label="Profile"
                        disabled={!codecChosen() || !concreteBackendChosen()}
                        onChange={(event) => setConnectDialogProfileKey(event.currentTarget.value)}
                      >
                        <option value="">Select a profile...</option>
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
                          return "Pick a trained profile";
                        })()}
                      </small>
                    </label>
                  </Show>

                  <label class={`connect-config-field${isAdaptive() ? " field-locked" : ""}`}>
                    <span>Resolution preset</span>
                    <select
                      value={connectDialogResolutionPresetId()}
                      disabled={isAdaptive()}
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
                  {(msg) => <p class="setup-missing">Connect blocked: {safeUiText(msg())}</p>}
                </Show>
                <Show when={connectFormErrors().length > 0}>
                  <p class="setup-missing" role="status">{connectFormErrors()[0]}</p>
                </Show>
                <Show when={session.deviceActionErrors()[device.serial]}>
                  {(msg) => <p class="setup-missing" role="alert">{safeUiText(msg())}</p>}
                </Show>
                <Show when={selectedCaptureBackend() === "evdi" && doctor()}>
                  {(d) => <p class="setup-hint">{safeUiText(d().installHint)}</p>}
                </Show>
                <div class="connect-modal-actions">
                  <button class="device-btn" onClick={closeConnectDialog}>Cancel</button>
                  <button class="device-btn" onClick={() => void confirmConnect()} disabled={connectDialogBlockReason().length > 0 || connectFormErrors().length > 0}>
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
                <h3>EVDI host setup</h3>
                <p class="connect-modal-subtitle">Host backend: {safeUiText(doctor.hostBackend, "legacy backend (unsupported)")}</p>
                <p class="setup-message">{safeUiText(doctor.message)}</p>
                <p class="setup-hint">{safeUiText(doctor.installHint)}</p>
                <Show when={hasActiveStreams()}>
                  <p class="setup-missing" role="alert">Stop active streams before installing or reloading EVDI.</p>
                </Show>
                <Show when={doctor.missingDeps.length > 0}>
                  <p class="setup-missing">Missing: {safeUiText(doctor.missingDeps.join(", "))}</p>
                </Show>
                <div class="connect-modal-actions">
                  <button class="device-btn" onClick={closeVirtualSetup} disabled={virtualSetupInstalling()}>
                    Cancel
                  </button>
                  <button class="device-btn" onClick={() => void installVirtualDeps()} disabled={virtualSetupInstalling() || !doctor.actionable || doctor.ok || hasActiveStreams()}>
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
      <Show when={evdiWizardVisible()}>
        <dialog open class="modal-backdrop" aria-label="EVDI setup wizard">
          <section class="connect-modal setup-modal">
            <h3>EVDI setup wizard</h3>
            <p class="connect-modal-subtitle">Configure EVDI kernel virtual displays for multiple connected devices.</p>
            <ol class="setup-list">
              <li>
                <strong>Load EVDI with multiple devices (run in terminal):</strong>
                <pre class="setup-hint code-block">sudo modprobe -r evdi
sudo modprobe evdi initial_device_count=4</pre>
              </li>
              <li>
                <strong>Persist across reboots:</strong>
                <pre class="setup-hint code-block">echo "options evdi initial_device_count=4" | sudo tee /etc/modprobe.d/evdi.conf</pre>
              </li>
              <li>
                <strong>Restart WBeam service and reconnect devices:</strong>
                <pre class="setup-hint code-block">./wbeam host stop
./wbeam host start</pre>
              </li>
              <li>
                <strong>Verify:</strong>
                <pre class="setup-hint code-block">cat /sys/module/evdi/parameters/initial_device_count
ls /dev/dri/card*</pre>
              </li>
            </ol>
            <p class="setup-message">
              After reloading EVDI, use “Autoconnect all (EVDI 20 Mbps)” to start all devices in virtual monitor mode.
            </p>
            <div class="connect-modal-actions">
              <button class="device-btn" onClick={() => setEvdiWizardVisible(false)}>Close</button>
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
