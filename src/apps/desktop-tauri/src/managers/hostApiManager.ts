import { invoke } from "@tauri-apps/api/core";
import type {
  DeviceBasic,
  DevicesBasicResponse,
  HostProbeBrief,
  ServiceStatus,
  VirtualDepsInstallStatus,
  VirtualDoctor,
} from "../types";

function normalizeApiError(err: unknown): string {
  const text = String(err ?? "unknown error");
  if (text.toLowerCase().includes("timeout")) {
    return "Host API timeout. Check service state and USB connectivity.";
  }
  if (text.includes("curl")) {
    return "Host API unreachable. Verify desktop service is running.";
  }
  return text;
}

async function withTimeout<T>(promise: Promise<T>, ms: number, label: string): Promise<T> {
  let timeoutId: ReturnType<typeof setTimeout> | null = null;
  const timeout = new Promise<never>((_, reject) => {
    timeoutId = setTimeout(() => reject(new Error(`${label} timeout after ${ms}ms`)), ms);
  });
  try {
    return await Promise.race([promise, timeout]);
  } finally {
    if (timeoutId) clearTimeout(timeoutId);
  }
}

export class HostApiManager {
  async getHostName(): Promise<string> {
    return withTimeout(invoke<string>("host_name"), 1500, "host_name");
  }

  async getServiceStatus(): Promise<ServiceStatus> {
    try {
      return await withTimeout(invoke<ServiceStatus>("service_status"), 2000, "service_status");
    } catch (err) {
      throw new Error(normalizeApiError(err));
    }
  }

  async getHostProbe(): Promise<HostProbeBrief> {
    try {
      return await withTimeout(invoke<HostProbeBrief>("host_probe_brief"), 2000, "host_probe_brief");
    } catch (err) {
      throw new Error(normalizeApiError(err));
    }
  }

  async getDevices(): Promise<DevicesBasicResponse> {
    try {
      return await withTimeout(invoke<DevicesBasicResponse>("list_devices_basic"), 3500, "list_devices_basic");
    } catch (err) {
      throw new Error(normalizeApiError(err));
    }
  }

  async serviceAction(action: "service_install" | "service_uninstall" | "service_start" | "service_stop"): Promise<ServiceStatus> {
    try {
      return await withTimeout(invoke<ServiceStatus>(action), 6000, action);
    } catch (err) {
      throw new Error(normalizeApiError(err));
    }
  }

  async connectDevice(
    device: DeviceBasic,
    displayMode: "virtual_monitor" | "duplicate",
  ): Promise<void> {
    try {
      await withTimeout(
        invoke<string>("device_connect", { serial: device.serial, streamPort: device.streamPort, displayMode }),
        10000,
        "device_connect",
      );
    } catch (err) {
      throw new Error(normalizeApiError(err));
    }
  }

  async getVirtualDoctor(device?: DeviceBasic): Promise<VirtualDoctor> {
    try {
      const params = device ? { serial: device.serial, streamPort: device.streamPort } : {};
      return await withTimeout(
        invoke<VirtualDoctor>("virtual_doctor", params),
        2500,
        "virtual_doctor",
      );
    } catch (err) {
      throw new Error(normalizeApiError(err));
    }
  }

  async startVirtualDepsInstall(): Promise<string> {
    try {
      return await withTimeout(
        invoke<string>("virtual_install_deps_start"),
        2500,
        "virtual_install_deps_start",
      );
    } catch (err) {
      throw new Error(normalizeApiError(err));
    }
  }

  async getVirtualDepsInstallStatus(): Promise<VirtualDepsInstallStatus> {
    try {
      return await withTimeout(
        invoke<VirtualDepsInstallStatus>("virtual_install_deps_status"),
        2500,
        "virtual_install_deps_status",
      );
    } catch (err) {
      throw new Error(normalizeApiError(err));
    }
  }

  async disconnectDevice(device: DeviceBasic): Promise<void> {
    try {
      await withTimeout(
        invoke<string>("device_disconnect", { serial: device.serial, streamPort: device.streamPort }),
        8000,
        "device_disconnect",
      );
    } catch (err) {
      throw new Error(normalizeApiError(err));
    }
  }
}
