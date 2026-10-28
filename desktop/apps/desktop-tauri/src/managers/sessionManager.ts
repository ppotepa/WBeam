import { createMemo, createSignal } from "solid-js";
import { HostApiManager } from "./hostApiManager";
import { createRefreshCoordinator } from "./refreshCoordinator";
import type { ConnectSessionConfig, DeviceBasic, HostProbeBrief, ServiceStatus } from "../types";

const EMPTY_PROBE: HostProbeBrief = {
  reachable: false,
  os: "unknown",
  session: "unknown",
  desktop: "unknown",
  captureMode: "unknown",
  supported: false,
  availableBackends: [],
  evdiAvailable: false,
};

export type DeviceActionResult =
  | { ok: true; serial: string; action: "connect" | "disconnect"; state: "accepted" }
  | { ok: false; serial: string; action: "connect" | "disconnect"; state: "failed"; error: string };

function actionErrorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

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
  // User-initiated errors must not be erased by automatic polling.
  const [error, setError] = createSignal<string>("");
  const [serviceRefreshError, setServiceRefreshError] = createSignal("");
  const [devicesRefreshError, setDevicesRefreshError] = createSignal("");
  const [deviceSnapshotStale, setDeviceSnapshotStale] = createSignal(false);
  const [deviceActionErrors, setDeviceActionErrors] = createSignal<Record<string, string>>({});
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

  const refreshError = createMemo(() =>
    [serviceRefreshError(), devicesRefreshError()].filter(Boolean).join(" · "),
  );

  function setDeviceActionError(serial: string, message: string): void {
    setDeviceActionErrors((prev) => {
      const next = { ...prev };
      if (message) next[serial] = message;
      else delete next[serial];
      return next;
    });
  }

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
      setServiceRefreshError("");
      const nextKey = JSON.stringify(next);
      if (nextKey !== lastServiceKey) {
        setService(next);
        lastServiceKey = nextKey;
        return true;
      }
      return false;
    } catch (err) {
      setServiceRefreshError(`Service refresh failed: ${actionErrorMessage(err)}`);
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
    try {
      const response = await api.getDevices();
      if (response.error) {
        // The daemon can report an ADB failure as an ordinary HTTP response
        // with devices: []. Do not treat that as a successful empty snapshot.
        setDevicesRefreshError(response.error);
        setDeviceSnapshotStale(true);
        devicesPollIntervalMs = Math.min(DEVICES_POLL_MAX_MS, devicesPollIntervalMs + 5000);
        nextDevicesPollAt = Date.now() + devicesPollIntervalMs;
        return false;
      }
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

      setDevicesRefreshError("");
      setDeviceSnapshotStale(false);
      if (force || changed) {
        devicesPollIntervalMs = DEVICES_POLL_MIN_MS;
      } else {
        devicesPollIntervalMs = Math.min(DEVICES_POLL_MAX_MS, devicesPollIntervalMs + 400);
      }
      nextDevicesPollAt = Date.now() + devicesPollIntervalMs;
    } catch (err) {
      // A transient ADB/API failure must not wipe a previously observed device
      // list or a user-action error. Keep the last successful snapshot marked stale.
      setDevicesRefreshError(`Device refresh failed: ${actionErrorMessage(err)}`);
      setDeviceSnapshotStale(true);
      devicesPollIntervalMs = Math.min(DEVICES_POLL_MAX_MS, devicesPollIntervalMs + 5000);
      nextDevicesPollAt = Date.now() + devicesPollIntervalMs;
    } finally {
      if (initial) setLoading(false);
    }
    return changed;
  }

  const refreshCoordinator = createRefreshCoordinator(
    async ({ forceDevices }) => {
      const [serviceChanged, probeChanged] = await Promise.all([loadServiceStatus(), loadHostProbe()]);
      // Keep ADB devices visible when the host service is stopped. Periodic
      // reads retain adaptive backoff; queued user/visibility reads can force.
      const devicesChanged = await loadDevices(loading(), forceDevices);
      if (serviceChanged || probeChanged || devicesChanged) {
        setUpdatedAt(new Date().toLocaleTimeString());
      }
    },
    (busy, interactive) => {
      setRefreshInFlight(busy);
      setRefreshing(interactive);
    },
  );

  function refreshSnapshot(options?: { silent?: boolean; forceDevices?: boolean }): Promise<void> {
    const silent = options?.silent ?? false;
    return refreshCoordinator.refresh({
      silent,
      forceDevices: options?.forceDevices ?? !silent,
    });
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
  ): Promise<DeviceActionResult> {
    const key = `${device.serial}:connect`;
    if (deviceActionBusy().some((entry) => entry.startsWith(`${device.serial}:`))) {
      return { ok: false, serial: device.serial, action: "connect", state: "failed", error: "Device action already in progress" };
    }
    setDeviceActionBusy((prev) => [...prev, key]);
    setDeviceActionError(device.serial, "");
    setError("");
    try {
      // A resolved API request means accepted, NOT confirmed STREAMING.
      await api.connectDevice(device, displayMode, connectConfig);
      await Promise.all([loadServiceStatus(), loadHostProbe()]);
      setUpdatedAt(new Date().toLocaleTimeString());
      return { ok: true, serial: device.serial, action: "connect", state: "accepted" };
    } catch (err) {
      const message = actionErrorMessage(err);
      setDeviceActionError(device.serial, message);
      return { ok: false, serial: device.serial, action: "connect", state: "failed", error: message };
    } finally {
      setDeviceActionBusy((prev) => prev.filter((entry) => entry !== key));
    }
  }

  async function disconnectDevice(device: DeviceBasic): Promise<DeviceActionResult> {
    const key = `${device.serial}:disconnect`;
    if (deviceActionBusy().some((entry) => entry.startsWith(`${device.serial}:`))) {
      return { ok: false, serial: device.serial, action: "disconnect", state: "failed", error: "Device action already in progress" };
    }
    setDeviceActionBusy((prev) => [...prev, key]);
    setDeviceActionError(device.serial, "");
    setError("");
    try {
      await api.disconnectDevice(device);
      await Promise.all([loadServiceStatus(), loadHostProbe()]);
      setUpdatedAt(new Date().toLocaleTimeString());
      return { ok: true, serial: device.serial, action: "disconnect", state: "accepted" };
    } catch (err) {
      const message = actionErrorMessage(err);
      setDeviceActionError(device.serial, message);
      return { ok: false, serial: device.serial, action: "disconnect", state: "failed", error: message };
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
    refreshError,
    deviceSnapshotStale,
    deviceActionErrors,
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
