export type DeviceBasic = {
  serial: string;
  model: string;
  platform: string;
  osVersion: string;
  deviceClass: string;
  resolution: string;
  maxResolution: string;
  apiLevel: string;
  batteryPercent: string;
  batteryLevel: number | null;
  batteryCharging: boolean;
  apkInstalled: boolean;
  apkVersion: string;
  apkMatchesHost: boolean;
  apkMatchesDaemon: boolean;
  streamPort: number;
  streamState: string;
};

export type DevicesBasicResponse = {
  hostApkVersion: string;
  daemonApkVersion: string;
  devices: DeviceBasic[];
  error: string | null;
};

export type ServiceStatus = {
  available: boolean;
  installed: boolean;
  active: boolean;
  enabled: boolean;
  summary: string;
};

export type HostProbeBrief = {
  reachable: boolean;
  os: string;
  session: string;
  desktop: string;
  captureMode: string;
  supported: boolean;
};

export type VirtualDoctor = {
  ok: boolean;
  message: string;
  actionable: boolean;
  hostBackend: string;
  resolver: string;
  missingDeps: string[];
  installHint: string;
};

export type VirtualDepsInstallStatus = {
  running: boolean;
  done: boolean;
  success: boolean;
  message: string;
  logs: string[];
};

export type ConnectEncoderMode = "h264" | "h265" | "rawpng";

export type CaptureBackend = "auto" | "wayland_portal" | "evdi";

export type ConnectSessionConfig = {
  encoder?: "h264" | "h265" | "rawpng";
  size?: string;
  profileName?: string;
  captureBackend?: CaptureBackend;
};

export type TrainedProfile = {
  key: string;
  name: string;
  backend: string;
  codec: string;
  objective: string;
  workload: string;
  encoder: "h264" | "h265" | "rawpng";
  bitrateKbps: number;
  fps: number;
  intraOnly: boolean;
  createdUnixMs: number;
};
