import { createMemo, createSignal } from "solid-js";
import { HostApiManager } from "./hostApiManager";
import type { ConnectSessionConfig, DeviceBasic, HostProbeBrief, ServiceStatus } from "../types";

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
  const [refreshing, setRefreshing] = createSignal(false);
  const [serviceBusy, setServiceBusy] = createSignal(false);
  const [error, setError] = createSignal<string>("");
  const [updatedAt, setUpdatedAt] = createSignal<string>("-");
  const [deviceActionBusy, setDeviceActionBusy] = createSignal<string[]>([]);
  const [hostProbe, setHostProbe] = createSignal<HostProbeBrief>(EMPTY_PROBE);
  const [refreshInFlight, setRefreshInFlight] = createSignal(false);
  let lastDevicesKey = "";
  let lastServiceKey = "";
  let lastProbeKey = "";
  let lastVersionKey = "";
  let nextDevicesPollAt = 0;
  let devicesPollIntervalMs = 5000;
  const DEVICES_POLL_MIN_MS = 1200;
  const DEVICES_POLL_MAX_MS = 7000;

  const tabletCount = createMemo(() => devices().filter((d) => d.deviceClass === "Tablet").length);
  const phoneCount = createMemo(() => devices().filter((d) => d.deviceClass === "Phone").length);
  const serviceState = createMemo<"running" | "stopped" | "missing">(() => {
    if (!service().installed) return "missing";
    if (!service().active) return "stopped";
    return "running";
  });

  async function loadServiceStatus(): Promise<boolean> {
    try {
      const next = await api.getServiceStatus();
      const nextKey = JSON.stringify(next);
      if (nextKey !== lastServiceKey) {
        setService(next);
        lastServiceKey = nextKey;
        return true;
      }
      return false;
    } catch (err) {
      setError(String(err));
      return false;
    }
  }

  async function loadHostProbe(): Promise<boolean> {
    try {
      const next = await api.getHostProbe();
      const nextKey = JSON.stringify(next);
      if (nextKey !== lastProbeKey) {
        setHostProbe(next);
        lastProbeKey = nextKey;
        return true;
      }
      return false;
    } catch {
      const nextKey = JSON.stringify(EMPTY_PROBE);
      if (nextKey !== lastProbeKey) {
        setHostProbe(EMPTY_PROBE);
        lastProbeKey = nextKey;
        return true;
      }
      return false;
    }
  }

  async function loadDevices(initial = false, force = false): Promise<boolean> {
    const now = Date.now();
    if (!force && now < nextDevicesPollAt) {
      return false;
    }
    let changed = false;
    if (initial) setLoading(true);
    setError("");
    try {
      const response = await api.getDevices();
      const nextDevices = response.devices || [];
      const nextDevicesKey = JSON.stringify(nextDevices);
      if (nextDevicesKey !== lastDevicesKey) {
        setDevices(nextDevices);
        lastDevicesKey = nextDevicesKey;
        changed = true;
      }

      const nextHostVersion = response.hostApkVersion || "";
      const nextDaemonVersion = response.daemonApkVersion || "";
      const nextVersionKey = `${nextHostVersion}|${nextDaemonVersion}`;
      if (nextVersionKey !== lastVersionKey) {
        setHostVersion(nextHostVersion);
        setDaemonVersion(nextDaemonVersion);
        lastVersionKey = nextVersionKey;
        changed = true;
      }

      if (response.error) setError(response.error);
      if (force || changed) {
        devicesPollIntervalMs = DEVICES_POLL_MIN_MS;
      } else {
        devicesPollIntervalMs = Math.min(DEVICES_POLL_MAX_MS, devicesPollIntervalMs + 400);
      }
      nextDevicesPollAt = Date.now() + devicesPollIntervalMs;
    } catch (err) {
      setError(String(err));
      setDevices([]);
      lastDevicesKey = "";
      changed = true;
      devicesPollIntervalMs = Math.min(DEVICES_POLL_MAX_MS, devicesPollIntervalMs + 5000);
      nextDevicesPollAt = Date.now() + devicesPollIntervalMs;
    } finally {
      if (initial) setLoading(false);
    }
    return changed;
  }

  async function refreshSnapshot(options?: { silent?: boolean; forceDevices?: boolean }) {
    const silent = options?.silent ?? false;
    const forceDevices = options?.forceDevices ?? !silent;
    if (refreshInFlight()) return;
    setRefreshInFlight(true);
    if (!silent) setRefreshing(true);
    try {
      const [serviceChanged, probeChanged] = await Promise.all([loadServiceStatus(), loadHostProbe()]);
      const canProbeDevices = service().active;
      let devicesChanged = false;
      if (canProbeDevices) {
        devicesChanged = await loadDevices(loading(), forceDevices);
      } else if (devices().length > 0 || hostVersion() || daemonVersion() || deviceActionBusy().length > 0) {
        setDevices([]);
        setHostVersion("");
        setDaemonVersion("");
        setDeviceActionBusy([]);
        lastDevicesKey = "";
        lastVersionKey = "";
        devicesChanged = true;
      }
      if (serviceChanged || probeChanged || devicesChanged) {
        setUpdatedAt(new Date().toLocaleTimeString());
      }
    } finally {
      if (!silent) setRefreshing(false);
      setRefreshInFlight(false);
    }
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

  async function upgradeService() {
    setServiceBusy(true);
    setError("");
    try {
      if (service().installed && service().active) {
        try {
          await api.serviceAction("service_stop");
        } catch {
          // Continue upgrade path even if stop races/fails.
        }
      }
      await api.serviceAction("service_install");
      await api.serviceAction("service_start");
      await refreshSnapshot();
    } catch (err) {
      setError(String(err));
      await loadServiceStatus();
    } finally {
      await loadHostProbe();
      setServiceBusy(false);
    }
  }

  async function connectDevice(
    device: DeviceBasic,
    displayMode: "virtual_monitor" | "virtual_mirror" | "duplicate",
    connectConfig?: ConnectSessionConfig,
  ) {
    const key = `${device.serial}:connect`;
    setDeviceActionBusy((prev) => (prev.includes(key) ? prev : [...prev, key]));
    setError("");
    try {
      await api.connectDevice(device, displayMode, connectConfig);
      await refreshSnapshot();
    } catch (err) {
      setError(String(err));
    } finally {
      setDeviceActionBusy((prev) => prev.filter((entry) => entry !== key));
    }
  }

  async function disconnectDevice(device: DeviceBasic) {
    const key = `${device.serial}:disconnect`;
    setDeviceActionBusy((prev) => (prev.includes(key) ? prev : [...prev, key]));
    setError("");
    try {
      await api.disconnectDevice(device);
      await refreshSnapshot();
    } catch (err) {
      setError(String(err));
    } finally {
      setDeviceActionBusy((prev) => prev.filter((entry) => entry !== key));
    }
  }

  return {
    devices,
    hostVersion,
    daemonVersion,
    service,
    loading,
    refreshing,
    serviceBusy,
    error,
    updatedAt,
    deviceActionBusy,
    hostProbe,
    refreshInFlight,
    tabletCount,
    phoneCount,
    serviceState,
    refreshSnapshot,
    callServiceAction,
    upgradeService,
    connectDevice,
    disconnectDevice,
    setError,
  };
}
