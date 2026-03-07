import { createMemo, createSignal } from "solid-js";
import { HostApiManager } from "./hostApiManager";
import type { DeviceBasic, HostProbeBrief, ServiceStatus } from "../types";

const EMPTY_PROBE: HostProbeBrief = {
  reachable: false,
  os: "unknown",
  session: "unknown",
  desktop: "unknown",
  captureMode: "unknown",
  supported: false,
};

export function createSessionManager(api: HostApiManager) {
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
  const [hostProbe, setHostProbe] = createSignal<HostProbeBrief>(EMPTY_PROBE);

  const tabletCount = createMemo(() => devices().filter((d) => d.deviceClass === "Tablet").length);
  const phoneCount = createMemo(() => devices().filter((d) => d.deviceClass === "Phone").length);
  const serviceState = createMemo<"running" | "stopped" | "missing">(() => {
    if (!service().installed) return "missing";
    if (!service().active) return "stopped";
    return "running";
  });

  async function loadServiceStatus() {
    try {
      setService(await api.getServiceStatus());
    } catch (err) {
      setError(String(err));
    }
  }

  async function loadHostProbe() {
    try {
      setHostProbe(await api.getHostProbe());
    } catch {
      setHostProbe(EMPTY_PROBE);
    }
  }

  async function loadDevices() {
    setLoading(true);
    setError("");
    try {
      const response = await api.getDevices();
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

  async function refreshSnapshot() {
    await Promise.all([loadServiceStatus(), loadHostProbe()]);
    await loadDevices();
  }

  async function callServiceAction(action: "service_install" | "service_uninstall" | "service_start" | "service_stop") {
    setServiceBusy(true);
    setError("");
    try {
      setService(await api.serviceAction(action));
    } catch (err) {
      setError(String(err));
      await loadServiceStatus();
    } finally {
      await loadHostProbe();
      setServiceBusy(false);
    }
  }

  async function connectDevice(device: DeviceBasic) {
    setDeviceActionBusy(`${device.serial}:connect`);
    setError("");
    try {
      await api.connectDevice(device);
      await refreshSnapshot();
    } catch (err) {
      setError(String(err));
    } finally {
      setDeviceActionBusy("");
    }
  }

  async function disconnectDevice(device: DeviceBasic) {
    setDeviceActionBusy(`${device.serial}:disconnect`);
    setError("");
    try {
      await api.disconnectDevice(device);
      await refreshSnapshot();
    } catch (err) {
      setError(String(err));
    } finally {
      setDeviceActionBusy("");
    }
  }

  return {
    devices,
    hostVersion,
    daemonVersion,
    service,
    loading,
    serviceBusy,
    error,
    updatedAt,
    deviceActionBusy,
    hostProbe,
    tabletCount,
    phoneCount,
    serviceState,
    refreshSnapshot,
    callServiceAction,
    connectDevice,
    disconnectDevice,
    setError,
  };
}
