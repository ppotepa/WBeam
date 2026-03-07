import { For, Show, createMemo, createSignal, onCleanup, onMount } from "solid-js";
import { invoke } from "@tauri-apps/api/core";
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
} from "lucide-solid";

type DeviceBasic = {
  serial: string;
  model: string;
  platform: string;
  osVersion: string;
  deviceClass: string;
  resolution: string;
  maxResolution: string;
  apiLevel: string;
  batteryPercent: string;
  batteryLevel: number | null;
  batteryCharging: boolean;
  apkInstalled: boolean;
  apkVersion: string;
  apkMatchesHost: boolean;
  apkMatchesDaemon: boolean;
  streamPort: number;
  streamState: string;
};

type DevicesBasicResponse = {
  hostApkVersion: string;
  daemonApkVersion: string;
  devices: DeviceBasic[];
  error: string | null;
};

type ServiceStatus = {
  available: boolean;
  installed: boolean;
  active: boolean;
  enabled: boolean;
  summary: string;
};

type HostProbeBrief = {
  reachable: boolean;
  os: string;
  session: string;
  desktop: string;
  captureMode: string;
  supported: boolean;
};

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

export default function App() {
  const [mode, setMode] = createSignal<"basic" | "advanced">("basic");
  const [hostName, setHostName] = createSignal("unknown-host");
  const [devices, setDevices] = createSignal<DeviceBasic[]>([]);
  const [hostVersion, setHostVersion] = createSignal<string>("");
  const [daemonVersion, setDaemonVersion] = createSignal<string>("");
  const [service, setService] = createSignal<ServiceStatus>({
    available: false,
    installed: false,
    active: false,
    enabled: false,
    summary: "not checked",
  });
  const [loading, setLoading] = createSignal(true);
  const [serviceBusy, setServiceBusy] = createSignal(false);
  const [error, setError] = createSignal<string>("");
  const [updatedAt, setUpdatedAt] = createSignal<string>("-");
  const [deviceActionBusy, setDeviceActionBusy] = createSignal<string>("");
  const [hostProbe, setHostProbe] = createSignal<HostProbeBrief>({
    reachable: false,
    os: "unknown",
    session: "unknown",
    desktop: "unknown",
    captureMode: "unknown",
    supported: false,
  });

  const tabletCount = createMemo(() => devices().filter((d) => d.deviceClass === "Tablet").length);
  const phoneCount = createMemo(() => devices().filter((d) => d.deviceClass === "Phone").length);
  const serviceState = createMemo<"running" | "stopped" | "missing">(() => {
    if (!service().installed) return "missing";
    if (!service().active) return "stopped";
    return "running";
  });

  async function loadServiceStatus() {
    try {
      const status = await invoke<ServiceStatus>("service_status");
      setService(status);
    } catch (err) {
      setError(String(err));
    }
  }

  async function loadDevices() {
    setLoading(true);
    setError("");
    try {
      const response = await invoke<DevicesBasicResponse>("list_devices_basic");
      setDevices(response.devices || []);
      setHostVersion(response.hostApkVersion || "");
      setDaemonVersion(response.daemonApkVersion || "");
      setUpdatedAt(new Date().toLocaleTimeString());
      if (response.error) setError(response.error);
    } catch (err) {
      setError(String(err));
      setDevices([]);
    } finally {
      setLoading(false);
    }
  }

  async function deviceConnect(device: DeviceBasic) {
    setDeviceActionBusy(`${device.serial}:connect`);
    setError("");
    try {
      await invoke<string>("device_connect", { serial: device.serial, streamPort: device.streamPort });
      await refreshSnapshot();
    } catch (err) {
      setError(String(err));
    } finally {
      setDeviceActionBusy("");
    }
  }

  async function deviceDisconnect(device: DeviceBasic) {
    setDeviceActionBusy(`${device.serial}:disconnect`);
    setError("");
    try {
      await invoke<string>("device_disconnect", { serial: device.serial, streamPort: device.streamPort });
      await refreshSnapshot();
    } catch (err) {
      setError(String(err));
    } finally {
      setDeviceActionBusy("");
    }
  }

  async function refreshSnapshot() {
    await Promise.all([loadServiceStatus(), loadHostProbe()]);
    await loadDevices();
  }

  async function loadHostProbe() {
    try {
      const probe = await invoke<HostProbeBrief>("host_probe_brief");
      setHostProbe(probe);
    } catch {
      setHostProbe({
        reachable: false,
        os: "unknown",
        session: "unknown",
        desktop: "unknown",
        captureMode: "unknown",
        supported: false,
      });
    }
  }

  async function callServiceAction(action: "service_install" | "service_uninstall" | "service_start" | "service_stop") {
    setServiceBusy(true);
    setError("");
    try {
      const status = await invoke<ServiceStatus>(action);
      setService(status);
    } catch (err) {
      setError(String(err));
      await loadServiceStatus();
    } finally {
      await loadHostProbe();
      setServiceBusy(false);
    }
  }

  function deviceVersionStatus(device: DeviceBasic): { cls: "ok" | "warn" | "bad"; label: string } {
    if (!device.apkInstalled) return { cls: "warn", label: "APK missing" };
    if (!service().installed) return { cls: "warn", label: "Install desktop service first" };
    if (!service().active) return { cls: "warn", label: "Start desktop service to verify" };
    return device.apkMatchesDaemon
      ? { cls: "ok", label: "Version match" }
      : { cls: "bad", label: "Update required" };
  }

  function connectDisabledReason(device: DeviceBasic): string {
    if (deviceActionBusy() !== "") return "Another device action is in progress";
    if (!service().active) return "Desktop service must be running";
    if (!device.apkInstalled) return "APK is not installed on this device";
    if (!device.apkMatchesDaemon) return "APK version must match daemon version";
    if (device.streamState === "STREAMING") return "Device is already streaming";
    if (device.streamState === "CONNECTING") return "Device is already connecting";
    return "";
  }

  function disconnectDisabledReason(device: DeviceBasic): string {
    if (deviceActionBusy() !== "") return "Another device action is in progress";
    if (!service().active) return "Desktop service must be running";
    if (device.streamState !== "STREAMING" && device.streamState !== "CONNECTING") {
      return "Device is not streaming";
    }
    return "";
  }

  onMount(async () => {
    try {
      const name = await invoke<string>("host_name");
      const safe = name || "unknown-host";
      setHostName(safe);
      await getCurrentWindow().setTitle(`WBeam - ${safe}`);
    } catch {
      // ignore title update errors
    }

    await refreshSnapshot();

    const timer = window.setInterval(() => {
      void refreshSnapshot();
    }, 1000);
    onCleanup(() => window.clearInterval(timer));
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
            <button class="refresh-btn" onClick={() => refreshSnapshot()} disabled={loading()}>
              {loading() ? "..." : "Refresh"}
            </button>
          </div>
        </header>

        <p class="meta-line">Daemon APK: <strong>{daemonVersion() || hostVersion() || "not set"}</strong></p>

        <Show when={error()}>
          <p class="error-line">{error()}</p>
        </Show>
        <Show when={!loading() && devices().length === 0 && !error()}>
          <p class="empty-line">
            {service().active
              ? "No connected ADB devices."
              : "Probing paused until desktop service is running."}
          </p>
        </Show>

        <ul class="device-list" aria-label="Connected devices">
          <For each={devices()}>
            {(device) => (
              <li class="device-row">
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
                      service().active
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
                    return (
                      <>
                  <button
                    class="device-btn"
                    title="Refresh this device state"
                    disabled={loading() || deviceActionBusy() !== ""}
                    onClick={() => refreshSnapshot()}
                  >
                    <RefreshCw size={13} /> Refresh
                  </button>
                  <button
                    class="device-btn"
                    title={connectDisabled ? connectReason : "Start stream for this device"}
                    disabled={connectDisabled}
                    onClick={() => deviceConnect(device)}
                  >
                    <Link2 size={13} /> Connect
                  </button>
                  <button
                    class="device-btn"
                    title={disconnectDisabled ? disconnectReason : "Stop stream for this device"}
                    disabled={disconnectDisabled}
                    onClick={() => deviceDisconnect(device)}
                  >
                    <Unlink2 size={13} /> Disconnect
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
            class="svc-btn"
            disabled={serviceBusy() || !service().available || service().installed}
            onClick={() => callServiceAction("service_install")}
            title="Install user service"
          >
            <Download size={14} /> Install service
          </button>
          <button
            class="svc-btn"
            disabled={serviceBusy() || !service().available || !service().installed}
            onClick={() => callServiceAction("service_uninstall")}
            title="Uninstall user service"
          >
            <Trash2 size={14} /> Uninstall
          </button>
          <button
            class="svc-btn"
            disabled={serviceBusy() || !service().available || !service().installed || service().active}
            onClick={() => callServiceAction("service_start")}
            title="Start service"
          >
            <Play size={14} /> Start
          </button>
          <button
            class="svc-btn"
            disabled={serviceBusy() || !service().available || !service().installed || !service().active}
            onClick={() => callServiceAction("service_stop")}
            title="Stop service"
          >
            <Square size={14} /> Stop
          </button>
        </section>

        <footer class="status-bar" title={service().summary}>
          <div class={`service-status-strip ${serviceState()}`}>
            <span class="service-status-main">
              service: {service().active ? "running" : service().installed ? "stopped" : "not installed"}
            </span>
            <span class="service-status-hint">
              {service().active
                ? "Service active: device probing enabled."
                : service().installed
                  ? "Service installed but stopped. Click Start."
                  : "Install + Start service to enable probing and streaming."}
            </span>
          </div>
          <span>{devices().length} devices ({tabletCount()} tablet, {phoneCount()} phone)</span>
          <span>
            host: {hostProbe().os}/{hostProbe().session}/{hostProbe().desktop} · capture={hostProbe().captureMode} · {hostProbe().supported ? "supported" : "unsupported"}
          </span>
          <span>updated: {updatedAt()}</span>
        </footer>
      </section>
    </main>
  );
}
