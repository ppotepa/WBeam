import type { ConnectEncoderMode, QualityMode, TrainedProfile } from "../types";
import type { SupportedCaptureBackend } from "./backendPolicy";

export type ConnectFormInput = {
  captureBackend: SupportedCaptureBackend | null;
  encoder: ConnectEncoderMode | "";
  qualityMode: QualityMode;
  profileKey: string;
  profiles: readonly TrainedProfile[];
  manualFps: number;
  manualMbps: number;
};

// A pure validator used by both the disabled state and the submit path.
// Backend availability is resolved by backendPolicy and rechecked in native Tauri.
export function validateConnectForm(input: ConnectFormInput): string[] {
  const errors: string[] = [];
  if (!input.captureBackend) errors.push("Select a supported capture backend (EVDI or Wayland Portal).");
  if (!input.encoder) errors.push("Choose a codec before connecting.");
  if (input.qualityMode === "profile") {
    if (!input.profileKey) {
      errors.push("Select a trained profile before connecting.");
    } else {
      const profile = input.profiles.find((item) => item.key === input.profileKey);
      if (!profile) errors.push("The selected profile is no longer available.");
      else {
        if (profile.backend !== input.captureBackend) errors.push("The selected profile belongs to a different backend.");
        if (profile.encoder !== input.encoder) errors.push("The selected profile does not match the codec.");
      }
    }
  }
  if (input.qualityMode === "manual") {
    if (!Number.isInteger(input.manualFps) || input.manualFps < 24 || input.manualFps > 120) {
      errors.push("Target FPS must be an integer between 24 and 120.");
    }
    if (!Number.isInteger(input.manualMbps) || input.manualMbps < 1 || input.manualMbps > 200) {
      errors.push("Target bitrate must be an integer between 1 and 200 Mbps.");
    }
  }
  return errors;
}
