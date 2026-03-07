import { invoke } from "@tauri-apps/api/core";
import type { DeviceBasic, DevicesBasicResponse, HostProbeBrief, ServiceStatus } from "../types";

function normalizeApiError(err: unknown): string {
  const text = String(err ?? "unknown error");
  if (text.includes("curl")) {
    return "Host API unreachable. Verify desktop service is running.";
  }
  return text;
}

export class HostApiManager {
  async getHostName(): Promise<string> {
    return invoke<string>("host_name");
  }

  async getServiceStatus(): Promise<ServiceStatus> {
    try {
      return await invoke<ServiceStatus>("service_status");
    } catch (err) {
      throw new Error(normalizeApiError(err));
    }
  }

  async getHostProbe(): Promise<HostProbeBrief> {
    try {
      return await invoke<HostProbeBrief>("host_probe_brief");
    } catch (err) {
      throw new Error(normalizeApiError(err));
    }
  }

  async getDevices(): Promise<DevicesBasicResponse> {
    try {
      return await invoke<DevicesBasicResponse>("list_devices_basic");
    } catch (err) {
      throw new Error(normalizeApiError(err));
    }
  }

  async serviceAction(action: "service_install" | "service_uninstall" | "service_start" | "service_stop"): Promise<ServiceStatus> {
    try {
      return await invoke<ServiceStatus>(action);
    } catch (err) {
      throw new Error(normalizeApiError(err));
    }
  }

  async connectDevice(device: DeviceBasic): Promise<void> {
    try {
      await invoke<string>("device_connect", { serial: device.serial, streamPort: device.streamPort });
    } catch (err) {
      throw new Error(normalizeApiError(err));
    }
  }

  async disconnectDevice(device: DeviceBasic): Promise<void> {
    try {
      await invoke<string>("device_disconnect", { serial: device.serial, streamPort: device.streamPort });
    } catch (err) {
      throw new Error(normalizeApiError(err));
    }
  }
}
