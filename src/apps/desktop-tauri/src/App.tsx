import { For, Show, createMemo, createSignal, onMount } from "solid-js";
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
};

type DevicesBasicResponse = {
  hostApkVersion: string;
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

  const tabletCount = createMemo(() => devices().filter((d) => d.deviceClass === "Tablet").length);
  const phoneCount = createMemo(() => devices().filter((d) => d.deviceClass === "Phone").length);

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
      setUpdatedAt(new Date().toLocaleTimeString());
      if (response.error) setError(response.error);
    } catch (err) {
      setError(String(err));
      setDevices([]);
    } finally {
      setLoading(false);
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
      setServiceBusy(false);
    }
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

    await Promise.all([loadDevices(), loadServiceStatus()]);
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
            <button class="refresh-btn" onClick={() => Promise.all([loadDevices(), loadServiceStatus()])} disabled={loading()}>
              {loading() ? "..." : "Refresh"}
            </button>
          </div>
        </header>

        <p class="meta-line">Host APK: <strong>{hostVersion() || "not set"}</strong></p>

        <Show when={error()}>
          <p class="error-line">{error()}</p>
        </Show>

        <Show when={!loading() && devices().length === 0 && !error()}>
          <p class="empty-line">No connected ADB devices.</p>
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

                <div class="line end-line">
                  <span
                    class={device.apkMatchesHost ? "status ok" : "status bad"}
                    title="APK version must match host version"
                  >
                    <Show when={device.apkMatchesHost} fallback={<AlertTriangle size={14} />}>
                      <ShieldCheck size={14} />
                    </Show>
                    {device.apkMatchesHost ? "Version match" : "Version mismatch"}
                  </span>
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
          <span>{devices().length} devices ({tabletCount()} tablet, {phoneCount()} phone)</span>
          <span>service: {service().active ? "running" : service().installed ? "stopped" : "not installed"}</span>
          <span>updated: {updatedAt()}</span>
        </footer>
      </section>
    </main>
  );
}
