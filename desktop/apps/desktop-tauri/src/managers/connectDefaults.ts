import type { SupportedCaptureBackend } from "./backendPolicy";
import type { ConnectSessionConfig } from "../types";

// Basic and Auto-connect share a conservative adaptive profile. Never
// force unvalidated fixed bandwidth/FPS values on an unknown Android device.
export function buildDefaultConnectConfig(backend: SupportedCaptureBackend): ConnectSessionConfig {
  return {
    captureBackend: backend,
    encoder: "h264",
    runtimeProfile: "adaptive",
  };
}
