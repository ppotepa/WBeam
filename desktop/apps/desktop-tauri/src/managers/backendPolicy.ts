import type { CaptureBackend, HostProbeBrief } from "../types";

export type SupportedCaptureBackend = Exclude<CaptureBackend, "auto">;

type ProbeCapabilities = Pick<HostProbeBrief, "reachable" | "supported" | "captureMode" | "availableBackends">;

// Legacy paths can remain in the daemon; the release UI only uses these
// two concrete, advertised backends.
export function resolveSupportedBackend(
  requested: CaptureBackend | "",
  probe: ProbeCapabilities,
): SupportedCaptureBackend | null {
  if (!probe.reachable || !probe.supported) return null;

  const available = new Set(probe.availableBackends.filter(
    (candidate) => candidate === "evdi" || candidate === "wayland_portal",
  ));
  if (requested === "evdi" || requested === "wayland_portal") {
    return available.has(requested) ? requested : null;
  }
  if (requested !== "auto") return null;

  if (probe.captureMode === "wayland_portal" && available.has("wayland_portal")) {
    return "wayland_portal";
  }
  if (available.has("evdi")) return "evdi";
  if (available.has("wayland_portal")) return "wayland_portal";
  return null;
}
