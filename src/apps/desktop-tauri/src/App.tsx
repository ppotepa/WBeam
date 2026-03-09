import { For, Show, createSignal, onCleanup, onMount } from "solid-js";
import { getCurrentWindow } from "@tauri-apps/api/window";
import {
  AlertTriangle,
  BatteryCharging,
  BatteryFull,
  BatteryLow,
  BatteryMedium,
  Cpu,
  MonitorSmartphone,
  Package,
  Settings,
  ShieldCheck,
  Smartphone,
  Tablet,
  Play,
  Square,
  Download,
  Trash2,
  RefreshCw,
  Link2,
  Unlink2,
  Loader2,
} from "lucide-solid";
import type { DeviceBasic } from "./types";
import type { VirtualDepsInstallStatus, VirtualDoctor } from "./types";
import { HostApiManager } from "./managers/hostApiManager";
import { createSessionManager } from "./managers/sessionManager";

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

export default function App() {
  const api = new HostApiManager();
  const session = createSessionManager(api);
  const [mode, setMode] = createSignal<"basic" | "advanced">("basic");
  const [hostName, setHostName] = createSignal("unknown-host");
  const [connectDialogDevice, setConnectDialogDevice] = createSignal<DeviceBasic | null>(null);
  const [connectDialogMode, setConnectDialogMode] = createSignal<DisplayMode>("virtual_monitor");
  const [connectDialogDoctor, setConnectDialogDoctor] = createSignal<VirtualDoctor | null>(null);
  const [connectDialogDoctorLoading, setConnectDialogDoctorLoading] = createSignal(false);
  const [connectDialogBlockReason, setConnectDialogBlockReason] = createSignal("");
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

  function deviceVersionStatus(device: DeviceBasic): { cls: "ok" | "warn" | "bad"; label: string } {
    if (!device.apkInstalled) return { cls: "warn", label: "APK missing" };
    if (!session.service().installed) return { cls: "warn", label: "Install desktop service first" };
    if (!session.service().active) return { cls: "warn", label: "Start desktop service to verify" };
    return device.apkMatchesDaemon
      ? { cls: "ok", label: "Version match" }
      : { cls: "bad", label: "Update required" };
  }

  function connectDisabledReason(device: DeviceBasic): string {
    if (isDeviceBusy(device)) return "This device action is already in progress";
    if (!session.service().active) return "Desktop service must be running";
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

  async function connectWaylandPortal(device: DeviceBasic): Promise<void> {
    const blockedReason = connectDisabledReason(device);
    if (blockedReason.length > 0) {
      session.setError(blockedReason);
      return;
    }
    saveDisplayMode(device.serial, "duplicate");
    await session.connectDevice(device, "duplicate");
  }

  function openConnectDialog(device: DeviceBasic): void {
    if (isWaylandPortalHost()) {
      void connectWaylandPortal(device);
      return;
    }
    const saved = loadSavedDisplayMode(device.serial);
    setConnectDialogMode(saved ?? "virtual_monitor");
    setConnectDialogDoctor(null);
    setConnectDialogDoctorLoading(true);
    setConnectDialogBlockReason(connectDisabledReason(device));
    setConnectDialogDevice(device);
    void api.getVirtualDoctor(device)
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

  async function confirmConnect(): Promise<void> {
    const device = connectDialogDevice();
    if (!device) return;
    const blockedReason = connectDisabledReason(device);
    if (blockedReason.length > 0) {
      setConnectDialogBlockReason(blockedReason);
      session.setError(blockedReason);
      return;
    }
    try {
      let chosenMode = connectDialogMode();
      let backendMode: "virtual_monitor" | "duplicate" = "duplicate";
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
      closeConnectDialog();
      await session.connectDevice(device, backendMode);
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
      void session.refreshSnapshot({ silent: true });
    }, 4000);
    onCleanup(() => {
      window.clearInterval(timer);
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
          const virtualMonitorSelected = () => connectDialogMode() === "virtual_monitor";
          const doctor = () => connectDialogDoctor();
          const virtualMonitorAvailable = () => isVirtualMonitorAvailable(doctor() ?? null);
          return (
            <div class="modal-backdrop" role="dialog" aria-modal="true" aria-label="Select display mode">
              <section class="connect-modal">
                <h3>Connect mode</h3>
                <p class="connect-modal-subtitle">{device.model} ({device.serial})</p>
                <label class={`mode-option ${virtualMonitorSelected() ? "selected" : ""} ${!virtualMonitorAvailable() ? "disabled" : ""}`}>
                  <input
                    type="radio"
                    name="display-mode"
                    checked={virtualMonitorSelected()}
                    disabled={!virtualMonitorAvailable()}
                    onChange={() => setConnectDialogMode("virtual_monitor")}
                  />
                  <span>
                    Virtual monitor (extend host desktop)
                    <small>
                      {virtualMonitorAvailable()
                        ? "Creates real additional monitor space on host desktop."
                        : "Not implemented for current host session yet."}
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
                    <small>Works with current host backend.</small>
                  </span>
                </label>
                <Show when={connectDialogDoctorLoading()}>
                  <p class="setup-message">Checking host capabilities...</p>
                </Show>
                <Show when={connectDialogBlockReason()}>
                  {(msg) => <p class="setup-missing">Connect blocked: {msg()}</p>}
                </Show>
                <Show when={doctor()}>
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
  return resolver === "linux_x11_real_output" || resolver === "linux_x11_monitor_object_experimental";
}

function isVirtualMonitorAvailable(doctor: VirtualDoctor | null): boolean {
  if (!doctor || !doctor.ok) return false;
  if (isVirtualMonitorResolver(doctor.resolver)) return true;
  if (doctor.hostBackend === "wayland_portal") return true;
  return false;
}
