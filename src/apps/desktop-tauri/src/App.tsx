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

export default function App() {
  const api = new HostApiManager();
  const session = createSessionManager(api);
  const [mode, setMode] = createSignal<"basic" | "advanced">("basic");
  const [hostName, setHostName] = createSignal("unknown-host");
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

    const timer = window.setInterval(() => {
      if (session.deviceActionBusy().length > 0 || session.refreshInFlight()) return;
      void session.refreshSnapshot({ silent: true });
    }, 2500);
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
                    title={connectBusy ? "Connecting..." : (connectDisabled ? connectReason : "Start stream for this device")}
                    disabled={connectDisabled}
                    onClick={() => session.connectDevice(device)}
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
    </main>
  );
}
