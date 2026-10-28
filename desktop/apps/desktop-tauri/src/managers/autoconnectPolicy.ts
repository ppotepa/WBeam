import type { HostProbeBrief } from "../types";
import { resolveSupportedBackend, type SupportedCaptureBackend } from "./backendPolicy";

export type AutoConnectDecision = {
  backend: SupportedCaptureBackend | null;
  reason: string;
};

// Do not advertise multi-device Wayland Portal until real concurrent session
// support is verified. EVDI is selected for multi-device when it is available.
export function decideAutoConnectBackend(
  probe: Pick<HostProbeBrief, "reachable" | "supported" | "captureMode" | "availableBackends">,
  deviceCount: number,
): AutoConnectDecision {
  if (deviceCount < 1) return { backend: null, reason: "No connected devices." };
  const evdi = resolveSupportedBackend("evdi", probe);
  if (deviceCount > 1) {
    if (evdi) return { backend: evdi, reason: "" };
    return { backend: null, reason: "Multiple devices require EVDI. Wayland Portal multi-device streaming is not validated; connect one device at a time." };
  }
  const backend = resolveSupportedBackend("auto", probe);
  if (!backend) {
    return { backend: null, reason: "No supported capture backend is currently available. Configure EVDI or Wayland Portal." };
  }
  return { backend, reason: "" };
}
