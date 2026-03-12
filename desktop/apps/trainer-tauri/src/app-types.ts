export type Tab =
  | "train"
  | "live_run"
  | "live_stats"
  | "runs"
  | "profiles"
  | "datasets"
  | "compare"
  | "devices"
  | "validation"
  | "diagnostics"
  | "settings";

export type Health = {
  ok?: boolean;
  state?: string;
  build_revision?: string;
};

export type RunItem = {
  run_id: string;
  profile_name: string;
  serial: string;
  mode: string;
  engine: string;
  status: string;
  started_at_unix_ms: number;
  finished_at_unix_ms?: number | null;
  trials: number;
  warmup_sec: number;
  sample_sec: number;
  log_path: string;
  profile_dir: string;
  run_artifacts_dir: string;
  hud_chart_mode?: string;
  hud_font_preset?: string;
  hud_layout?: string;
  exit_code?: number | null;
  error?: string | null;
};

export type ProfileItem = {
  profile_name: string;
  path: string;
  has_profile: boolean;
  has_parameters: boolean;
  has_preflight: boolean;
  best_score?: number | null;
  engine?: string | null;
  serial?: string | null;
  updated_at_unix_ms?: number | null;
};

export type RunTail = {
  ok: boolean;
  run_id: string;
  line_count: number;
  lines: string[];
};

export type DeviceInfo = {
  serial: string;
  state: string;
  model?: string | null;
  api_level?: number | null;
  android_release?: string | null;
  stream_port?: number | null;
};

export type Diagnostics = {
  ok: boolean;
  daemon_health: Record<string, unknown>;
  adb_version: string;
  adb_devices_raw: string;
  profile_root: string;
  runs_count: number;
};

export type DatasetSummary = {
  run_id: string;
  profile_name: string;
  status: string;
  run_artifacts_dir: string;
  started_at_unix_ms?: number | null;
  finished_at_unix_ms?: number | null;
  has_run_json: boolean;
  has_parameters: boolean;
  has_profile: boolean;
  has_preflight: boolean;
  has_logs: boolean;
  best_trial?: string | null;
  best_score?: number | null;
  last_recompute_at_unix_ms?: number | null;
};

export type DatasetDetail = {
  ok: boolean;
  dataset: DatasetSummary;
  run: Record<string, unknown>;
  parameters: Record<string, unknown>;
  profile: Record<string, unknown>;
  preflight: Record<string, unknown>;
  recompute: Record<string, unknown>;
};

export type ProfileDetail = {
  ok: boolean;
  profile_name: string;
  profile: Record<string, unknown>;
  parameters: Record<string, unknown>;
  preflight: Record<string, unknown>;
};

export type HudSnapshot = {
  trialId: string;
  score: string;
  presentFps: string;
  recvFps: string;
  bitrateMbps: string;
  e2eP95Ms: string;
  dropsPerSec: string;
  progress: string;
  generation: string;
  mode: string;
};

export type HudSeries = {
  score: number[];
  present: number[];
  recv: number[];
  drops: number[];
};

export type LiveStage = {
  label: string;
  detail: string;
  ts: string;
  level: "info" | "warn" | "risk" | "ok";
};

export type LivePatchChange = {
  key: string;
  label: string;
  from: string;
  to: string;
  restart: boolean;
};

export type CurrentChildPreset = {
  trialId: string;
  encoder: string;
  size: string;
  fps: string;
  bitrateKbps: string;
};

export type DatasetTrialPoint = {
  trial_id: string;
  score: number;
  present_fps_mean: number;
  recv_fps_mean: number;
  bitrate_mbps_mean: number;
  drop_rate_per_sec: number;
  notes: string;
};

export type SettingsModel = {
  compactDensity: boolean;
  animateUi: boolean;
  autoOpenLiveTab: boolean;
  autoOpenRunResults: boolean;
  pollingMs: number;
};

export type LiveSeries = {
  present: number[];
  recv: number[];
  drop: number[];
  mbps: number[];
  latency: number[];
  score: number[];
};
