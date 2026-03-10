import { For, Show, createEffect, createMemo, createSignal, onCleanup, onMount } from "solid-js";

type Tab =
  | "train"
  | "live"
  | "runs"
  | "profiles"
  | "datasets"
  | "compare"
  | "devices"
  | "validation"
  | "diagnostics"
  | "settings";

type Health = {
  ok?: boolean;
  state?: string;
  build_revision?: string;
};

type RunItem = {
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
  exit_code?: number | null;
  error?: string | null;
};

type ProfileItem = {
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

type RunTail = {
  ok: boolean;
  run_id: string;
  line_count: number;
  lines: string[];
};

type DeviceInfo = {
  serial: string;
  state: string;
  model?: string | null;
  api_level?: number | null;
  android_release?: string | null;
  stream_port?: number | null;
};

type Diagnostics = {
  ok: boolean;
  daemon_health: Record<string, unknown>;
  adb_version: string;
  adb_devices_raw: string;
  profile_root: string;
  runs_count: number;
};

type DatasetSummary = {
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

type DatasetDetail = {
  ok: boolean;
  dataset: DatasetSummary;
  run: Record<string, unknown>;
  parameters: Record<string, unknown>;
  profile: Record<string, unknown>;
  preflight: Record<string, unknown>;
  recompute: Record<string, unknown>;
};

type ProfileDetail = {
  ok: boolean;
  profile_name: string;
  profile: Record<string, unknown>;
  parameters: Record<string, unknown>;
  preflight: Record<string, unknown>;
};

type HudSnapshot = {
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

type HudSeries = {
  score: number[];
  present: number[];
  recv: number[];
  drops: number[];
};

type LiveStage = {
  label: string;
  detail: string;
  ts: string;
  level: "info" | "warn" | "risk" | "ok";
};

type DatasetTrialPoint = {
  trial_id: string;
  score: number;
  present_fps_mean: number;
  recv_fps_mean: number;
  bitrate_mbps_mean: number;
  drop_rate_per_sec: number;
  notes: string;
};

type SettingsModel = {
  compactDensity: boolean;
  animateUi: boolean;
  autoOpenLiveTab: boolean;
  autoOpenRunResults: boolean;
  pollingMs: number;
};

function formatTs(ms?: number | null): string {
  if (!ms) return "-";
  const date = new Date(ms);
  if (Number.isNaN(date.getTime())) return "-";
  return date.toLocaleString();
}

function parseNum(value: string): number | null {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function toBars(values: number[], dangerAbove = false): { value: number; pct: number; cls: string }[] {
  if (!values.length) {
    return [];
  }
  const maxValue = Math.max(1, ...values);
  return values.map((item) => {
    const pct = Math.max(4, Math.min(100, (item / maxValue) * 100));
    const cls = dangerAbove
      ? item > maxValue * 0.65
        ? "risk"
        : item > maxValue * 0.4
          ? "warn"
          : "good"
      : item < maxValue * 0.35
        ? "risk"
        : item < maxValue * 0.6
          ? "warn"
          : "good";
    return { value: item, pct, cls };
  });
}

function kbpsToMbps(kbps: number): number {
  return Math.round((kbps / 1000) * 10) / 10;
}

function mbpsToKbps(mbps: number): number {
  return Math.round(mbps * 1000);
}

function seriesSummary(values: number[]): { min: string; max: string; last: string } {
  if (!values.length) return { min: "-", max: "-", last: "-" };
  const min = Math.min(...values);
  const max = Math.max(...values);
  const last = values[values.length - 1];
  return { min: min.toFixed(1), max: max.toFixed(1), last: last.toFixed(1) };
}

function safeName(input: string): string {
  return input.replace(/[^a-zA-Z0-9._-]+/g, "_");
}

function downloadJson(filename: string, payload: unknown): void {
  const blob = new Blob([`${JSON.stringify(payload, null, 2)}\n`], { type: "application/json" });
  const href = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = href;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(href);
}

function trialOrdinal(trialId: string): number {
  const match = trialId.match(/t(\d+)/i);
  if (!match) return Number.MAX_SAFE_INTEGER;
  return Number(match[1]);
}

function parseDatasetTrials(parameters: Record<string, unknown>): DatasetTrialPoint[] {
  const raw = valueAt(parameters, ["results"]);
  if (!Array.isArray(raw)) return [];
  const rows: DatasetTrialPoint[] = [];
  for (const item of raw) {
    if (!item || typeof item !== "object") continue;
    const row = item as Record<string, unknown>;
    const trial_id = String(row.trial_id || "").trim();
    if (!trial_id) continue;
    const score = Number(row.score || 0);
    const present_fps_mean = Number(row.present_fps_mean || 0);
    const recv_fps_mean = Number(row.recv_fps_mean || 0);
    const bitrate_mbps_mean = Number(row.bitrate_mbps_mean || 0);
    const drop_rate_per_sec = Number(row.drop_rate_per_sec || 0);
    const notes = String(row.notes || "-");
    rows.push({
      trial_id,
      score: Number.isFinite(score) ? score : 0,
      present_fps_mean: Number.isFinite(present_fps_mean) ? present_fps_mean : 0,
      recv_fps_mean: Number.isFinite(recv_fps_mean) ? recv_fps_mean : 0,
      bitrate_mbps_mean: Number.isFinite(bitrate_mbps_mean) ? bitrate_mbps_mean : 0,
      drop_rate_per_sec: Number.isFinite(drop_rate_per_sec) ? drop_rate_per_sec : 0,
      notes,
    });
  }
  return rows.sort((a, b) => trialOrdinal(a.trial_id) - trialOrdinal(b.trial_id));
}

async function fetchJson<T>(path: string): Promise<T> {
  const resp = await fetch(path);
  if (!resp.ok) {
    const body = await resp.text();
    throw new Error(`${resp.status} ${resp.statusText}: ${body}`.slice(0, 320));
  }
  return (await resp.json()) as T;
}

function valueAt(obj: unknown, path: string[]): unknown {
  let cur = obj as Record<string, unknown> | undefined;
  for (const key of path) {
    if (!cur || typeof cur !== "object") return undefined;
    cur = cur[key] as Record<string, unknown> | undefined;
  }
  return cur;
}

function pickRuntimeValue(profile: Record<string, unknown>, key: string): string {
  const value = valueAt(profile, ["profile", "runtime", key]);
  if (value === undefined || value === null) return "-";
  return String(value);
}

function pickRuntimeBitrateMbps(profile: Record<string, unknown>): string {
  const value = valueAt(profile, ["profile", "runtime", "bitrate_kbps"]);
  const kbps = Number(value);
  if (!Number.isFinite(kbps) || kbps <= 0) return "-";
  return `${kbpsToMbps(kbps).toFixed(1)} Mbps`;
}

function parseHud(lines: string[]): HudSnapshot {
  let trialId = "-";
  let score = "-";
  let presentFps = "-";
  let recvFps = "-";
  let bitrateMbps = "-";
  let e2eP95Ms = "-";
  let dropsPerSec = "-";
  let progress = "-";
  let generation = "-";
  let mode = "-";

  const trialStartRe = /^\[(t\d+)] apply /;
  const trialScoreRe =
    /^\[(t\d+)] score=([0-9.\-]+) present=([0-9.\-]+) recv=([0-9.\-]+) e2e95=([0-9.\-]+)ms drops\/s=([0-9.\-]+)(?: mbps=([0-9.\-]+))?/;
  const progressRe = /^trial space=(\d+) running=(\d+)/;
  const protoTrialRe = /\btrial=([A-Za-z0-9_.:-]+)/;
  const protoDoneRe =
    /done trial=([A-Za-z0-9_.:-]+) score=([0-9.\-]+).*sender_p50=([0-9.\-]+).*pipe_p50=([0-9.\-]+).*timeout_mean=([0-9.\-]+).*drop=([0-9.\-]+)%(?:.*mbps=([0-9.\-]+))?/;
  const protoGenRe = /generation\s+(\d+)\/(\d+):\s+population=(\d+)\s+\(start\)/;
  const modeRe = /"mode"\s*:\s*"([^"]+)"/;

  for (const line of lines) {
    const start = line.match(trialStartRe);
    if (start) trialId = start[1];

    const scoreMatch = line.match(trialScoreRe);
    if (scoreMatch) {
      trialId = scoreMatch[1];
      score = scoreMatch[2];
      presentFps = scoreMatch[3];
      recvFps = scoreMatch[4];
      e2eP95Ms = scoreMatch[5];
      dropsPerSec = scoreMatch[6];
      if (scoreMatch[7]) bitrateMbps = scoreMatch[7];
    }

    const p = line.match(progressRe);
    if (p) progress = `${p[2]}/${p[1]}`;

    const protoTrial = line.match(protoTrialRe);
    if (protoTrial) trialId = protoTrial[1];

    const protoDone = line.match(protoDoneRe);
    if (protoDone) {
      trialId = protoDone[1];
      score = protoDone[2];
      presentFps = protoDone[3];
      recvFps = protoDone[4];
      e2eP95Ms = protoDone[5];
      dropsPerSec = `${protoDone[6]}%`;
      if (protoDone[7]) bitrateMbps = protoDone[7];
    }

    const protoGen = line.match(protoGenRe);
    if (protoGen) {
      generation = `${protoGen[1]}/${protoGen[2]}`;
      progress = `gen ${protoGen[1]}/${protoGen[2]} pop ${protoGen[3]}`;
    }

    const parsedMode = line.match(modeRe);
    if (parsedMode) {
      mode = parsedMode[1];
    }
  }

  return { trialId, score, presentFps, recvFps, bitrateMbps, e2eP95Ms, dropsPerSec, progress, generation, mode };
}

function parseHudSeries(lines: string[]): HudSeries {
  const trialScoreRe =
    /^\[(t\d+)] score=([0-9.\-]+) present=([0-9.\-]+) recv=([0-9.\-]+) e2e95=([0-9.\-]+)ms drops\/s=([0-9.\-]+)/;
  const protoDoneRe =
    /done trial=([A-Za-z0-9_.:-]+) score=([0-9.\-]+).*sender_p50=([0-9.\-]+).*pipe_p50=([0-9.\-]+).*timeout_mean=([0-9.\-]+).*drop=([0-9.\-]+)%/;
  const series: HudSeries = { score: [], present: [], recv: [], drops: [] };
  for (const line of lines) {
    const match = line.match(trialScoreRe);
    if (match) {
      series.score.push(Number(match[2]));
      series.present.push(Number(match[3]));
      series.recv.push(Number(match[4]));
      series.drops.push(Number(match[6]));
      continue;
    }
    const protoDone = line.match(protoDoneRe);
    if (protoDone) {
      series.score.push(Number(protoDone[2]));
      series.present.push(Number(protoDone[3]));
      series.recv.push(Number(protoDone[4]));
      series.drops.push(Number(protoDone[6]));
    }
  }
  const keep = 22;
  return {
    score: series.score.slice(-keep),
    present: series.present.slice(-keep),
    recv: series.recv.slice(-keep),
    drops: series.drops.slice(-keep),
  };
}

function parseLiveStages(lines: string[]): LiveStage[] {
  const output: LiveStage[] = [];
  for (const line of lines) {
    const ts = line.slice(0, 8);
    if (line.includes("generation ") && line.includes("population=")) {
      output.push({ label: "Generation", detail: line.trim(), ts, level: "info" });
      continue;
    }
    if (line.includes("warmup")) {
      output.push({ label: "Warmup", detail: line.trim(), ts, level: "info" });
      continue;
    }
    if (line.includes("sampling")) {
      output.push({ label: "Sampling", detail: line.trim(), ts, level: "info" });
      continue;
    }
    if (line.includes("done trial=") || line.includes(" score=")) {
      output.push({ label: "Trial complete", detail: line.trim(), ts, level: "ok" });
      continue;
    }
    if (line.includes("note: health gate")) {
      output.push({ label: "Health gate", detail: line.trim(), ts, level: "warn" });
      continue;
    }
    if (line.includes("ERROR") || line.includes("failed")) {
      output.push({ label: "Failure", detail: line.trim(), ts, level: "risk" });
      continue;
    }
  }
  return output.slice(-10).reverse();
}

function inferLiveHealth(lines: string[]): { state: string; tone: "ok" | "warn" | "risk" | "info" } {
  for (let i = lines.length - 1; i >= 0; i -= 1) {
    const line = lines[i];
    if (line.includes("ERROR") || line.includes("failed")) {
      return { state: "degraded", tone: "risk" };
    }
    if (line.includes("note: health gate")) {
      return { state: "gated", tone: "warn" };
    }
    if (line.includes("sampling") || line.includes("warmup") || line.includes("trial=")) {
      return { state: "active", tone: "ok" };
    }
  }
  return { state: "idle", tone: "info" };
}

function profileUpdatedAt(item: ProfileItem): number {
  return Number(item.updated_at_unix_ms || 0);
}

export default function App() {
  const tabs: Array<{ id: Tab; label: string; hint: string }> = [
    { id: "train", label: "Train", hint: "Configure and start" },
    { id: "live", label: "Live Run", hint: "Live HUD and events" },
    { id: "runs", label: "Runs", hint: "History and stop control" },
    { id: "profiles", label: "Profiles", hint: "Saved outputs" },
    { id: "datasets", label: "Datasets", hint: "Run artifacts and recompute" },
    { id: "compare", label: "Compare", hint: "Side-by-side profiles" },
    { id: "devices", label: "Devices", hint: "ADB inventory" },
    { id: "validation", label: "Validation", hint: "Quick profile check" },
    { id: "diagnostics", label: "Diagnostics", hint: "System internals" },
    { id: "settings", label: "Settings", hint: "UI behavior" },
  ];

  const [tab, setTab] = createSignal<Tab>("train");
  const [health, setHealth] = createSignal<Health>({});
  const [runs, setRuns] = createSignal<RunItem[]>([]);
  const [profiles, setProfiles] = createSignal<ProfileItem[]>([]);
  const [devices, setDevices] = createSignal<DeviceInfo[]>([]);
  const [diagnostics, setDiagnostics] = createSignal<Diagnostics | null>(null);
  const [datasets, setDatasets] = createSignal<DatasetSummary[]>([]);
  const [selectedDatasetRunId, setSelectedDatasetRunId] = createSignal("");
  const [datasetDetail, setDatasetDetail] = createSignal<DatasetDetail | null>(null);
  const [datasetActionText, setDatasetActionText] = createSignal("");
  const [tail, setTail] = createSignal<RunTail | null>(null);
  const [busyAction, setBusyAction] = createSignal("");
  const [lastError, setLastError] = createSignal("");
  const [lastRefreshAt, setLastRefreshAt] = createSignal<number>(Date.now());
  const [preflightText, setPreflightText] = createSignal("not started");
  const [showAdvanced, setShowAdvanced] = createSignal(false);

  const [serial, setSerial] = createSignal("");
  const [profileName, setProfileName] = createSignal("baseline");
  const [mode, setMode] = createSignal("quality");
  const [trials, setTrials] = createSignal(18);
  const [generations, setGenerations] = createSignal(2);
  const [population, setPopulation] = createSignal(18);
  const [eliteCount, setEliteCount] = createSignal(6);
  const [mutationRate, setMutationRate] = createSignal(0.34);
  const [crossoverRate, setCrossoverRate] = createSignal(0.5);
  const [bitrateMinKbps, setBitrateMinKbps] = createSignal(10000);
  const [bitrateMaxKbps, setBitrateMaxKbps] = createSignal(200000);
  const [encoderMode, setEncoderMode] = createSignal("multi");
  const [encH264, setEncH264] = createSignal(true);
  const [encH265, setEncH265] = createSignal(true);
  const [encRawpng, setEncRawpng] = createSignal(false);
  const [encMjpeg, setEncMjpeg] = createSignal(false);
  const [overlay, setOverlay] = createSignal(true);
  const [hudChartMode, setHudChartMode] = createSignal("bars");
  const [hudFontPreset, setHudFontPreset] = createSignal("compact");
  const [selectedRunId, setSelectedRunId] = createSignal("");
  const [leftProfile, setLeftProfile] = createSignal("");
  const [rightProfile, setRightProfile] = createSignal("");
  const [leftDetail, setLeftDetail] = createSignal<ProfileDetail | null>(null);
  const [rightDetail, setRightDetail] = createSignal<ProfileDetail | null>(null);
  const [previewProfileName, setPreviewProfileName] = createSignal("");
  const [previewProfileDetail, setPreviewProfileDetail] = createSignal<ProfileDetail | null>(null);
  const [settings, setSettings] = createSignal<SettingsModel>({
    compactDensity: true,
    animateUi: true,
    autoOpenLiveTab: true,
    autoOpenRunResults: true,
    pollingMs: 2000,
  });

  let pollId: number | undefined;

  const serviceOnline = createMemo(() => Boolean(health().ok));
  const runningRuns = createMemo(() =>
    runs().filter((item) => item.status === "running" || item.status === "stopping"),
  );
  const selectedEncoders = createMemo(() =>
    [
      encH264() ? "h264" : "",
      encH265() ? "h265" : "",
      encRawpng() ? "rawpng" : "",
      encMjpeg() ? "mjpeg" : "",
    ].filter(Boolean),
  );
  const activeRun = createMemo(() => {
    const selected = selectedRunId();
    if (selected) return runs().find((r) => r.run_id === selected) || runs()[0];
    if (runningRuns().length) return runningRuns()[0];
    return runs()[0];
  });
  const hud = createMemo(() => parseHud(tail()?.lines || []));
  const hudSeries = createMemo(() => parseHudSeries(tail()?.lines || []));
  const liveStages = createMemo(() => parseLiveStages(tail()?.lines || []));
  const liveHealth = createMemo(() => inferLiveHealth(tail()?.lines || []));
  const hasLiveSamples = createMemo(() => hudSeries().score.length > 0 || hudSeries().present.length > 0);

  const hardBlockers = createMemo(() => {
    const problems: string[] = [];
    if (!serviceOnline()) problems.push("Service is offline");
    if (!serial()) problems.push("Select a device");
    if (!profileName().trim()) problems.push("Profile name is required");
    if (selectedEncoders().length === 0) problems.push("At least one encoder must be selected");
    if (encoderMode() === "single" && selectedEncoders().length !== 1) {
      problems.push("Single encoder mode requires exactly one encoder");
    }
    if (bitrateMinKbps() > bitrateMaxKbps()) problems.push("Bitrate min must be <= bitrate max");
    if (generations() <= 0) problems.push("Generations must be greater than 0");
    if (population() <= 1) problems.push("Population must be greater than 1");
    if (eliteCount() <= 0 || eliteCount() >= population()) {
      problems.push("Elite count must be > 0 and < population");
    }
    return problems;
  });

  const softWarnings = createMemo(() => {
    const warnings: string[] = [];
    const selected = devices().find((item) => item.serial === serial());
    if (!selected) return warnings;
    if (selected.state !== "device") warnings.push(`Device state is '${selected.state}'`);
    if ((selected.api_level || 0) <= 19 && encH265()) warnings.push("h265 may underperform on low API devices");
    if (bitrateMaxKbps() > 150000) warnings.push("Very high max bitrate can destabilize weaker USB links");
    if (trials() < 8) warnings.push("Low trial budget can reduce result quality");
    return warnings;
  });

  const canPreflight = createMemo(() => serviceOnline() && !!serial() && selectedEncoders().length > 0);
  const canStart = createMemo(() => hardBlockers().length === 0);

  async function refreshHealth() {
    const data = await fetchJson<Health>("/v1/health");
    setHealth(data);
  }

  async function refreshRuns() {
    const data = await fetchJson<{ ok: boolean; runs: RunItem[] }>("/v1/trainer/runs");
    const incoming = data.runs || [];
    setRuns(incoming);
    if (!selectedRunId() && incoming.length > 0) {
      setSelectedRunId(incoming[0].run_id);
    }
  }

  async function refreshProfiles() {
    const data = await fetchJson<{ ok: boolean; profiles: ProfileItem[] }>("/v1/trainer/profiles");
    const sorted = [...(data.profiles || [])].sort((a, b) => profileUpdatedAt(b) - profileUpdatedAt(a));
    setProfiles(sorted);
    if (!leftProfile() && sorted.length) {
      setLeftProfile(sorted[0].profile_name);
      void loadProfileDetail("left", sorted[0].profile_name);
    }
    if (!rightProfile() && sorted.length > 1) {
      setRightProfile(sorted[1].profile_name);
      void loadProfileDetail("right", sorted[1].profile_name);
    }
    if (!previewProfileName() && sorted.length > 0) {
      setPreviewProfileName(sorted[0].profile_name);
      void loadPreviewProfile(sorted[0].profile_name);
    }
  }

  async function refreshDevices() {
    const data = await fetchJson<{ ok: boolean; devices: DeviceInfo[] }>("/v1/trainer/devices");
    setDevices(data.devices || []);
    if (!serial() && data.devices?.length) {
      const firstReady = data.devices.find((d) => d.state === "device");
      setSerial((firstReady || data.devices[0]).serial);
    }
  }

  async function refreshDiagnostics() {
    const data = await fetchJson<Diagnostics>("/v1/trainer/diagnostics");
    setDiagnostics(data);
  }

  async function refreshDatasets() {
    const data = await fetchJson<{ ok: boolean; datasets: DatasetSummary[] }>("/v1/trainer/datasets");
    const items = data.datasets || [];
    setDatasets(items);
    if (!selectedDatasetRunId() && items.length > 0) {
      setSelectedDatasetRunId(items[0].run_id);
      await refreshDatasetDetail(items[0].run_id);
    }
  }

  async function refreshDatasetDetail(runId: string) {
    if (!runId) {
      setDatasetDetail(null);
      return;
    }
    const data = await fetchJson<DatasetDetail>(`/v1/trainer/datasets/${encodeURIComponent(runId)}`);
    setDatasetDetail(data);
  }

  async function runDatasetFindOptimal(runId: string) {
    await withUiGuard("Recomputing dataset winner", async () => {
      if (!runId) {
        throw new Error("Select dataset first.");
      }
      const resp = await fetch(`/v1/trainer/datasets/${encodeURIComponent(runId)}/find-optimal`, {
        method: "POST",
      });
      const body = (await resp.json()) as Record<string, unknown>;
      if (!resp.ok) {
        throw new Error(String(body.error || "find-optimal failed"));
      }
      const bestTrial = String(body.best_trial || "unknown");
      const bestScore = Number(body.best_score || 0);
      setDatasetActionText(`recompute done: best=${bestTrial} score=${bestScore.toFixed(2)}`);
      await refreshDatasets();
      await refreshDatasetDetail(runId);
    });
  }

  async function refreshTail() {
    const run = activeRun();
    if (!run) {
      setTail(null);
      return;
    }
    const data = await fetchJson<RunTail>(
      `/v1/trainer/runs/${encodeURIComponent(run.run_id)}/tail?lines=280`,
    );
    setTail(data);
  }

  async function loadProfileDetail(which: "left" | "right", name: string) {
    if (!name) return;
    const data = await fetchJson<ProfileDetail>(`/v1/trainer/profiles/${encodeURIComponent(name)}`);
    if (which === "left") setLeftDetail(data);
    else setRightDetail(data);
  }

  async function loadPreviewProfile(name: string) {
    if (!name) return;
    const data = await fetchJson<ProfileDetail>(`/v1/trainer/profiles/${encodeURIComponent(name)}`);
    setPreviewProfileName(name);
    setPreviewProfileDetail(data);
  }

  async function withUiGuard(actionLabel: string, fn: () => Promise<void>) {
    setBusyAction(actionLabel);
    try {
      await fn();
      setLastError("");
    } catch (err) {
      setLastError(String(err));
    } finally {
      setBusyAction("");
    }
  }

  async function runPollingTick() {
    await refreshHealth();
    await refreshRuns();
    await refreshDevices();
    if (tab() === "profiles" || tab() === "compare") await refreshProfiles();
    if (tab() === "datasets") await refreshDatasets();
    if (tab() === "live" || tab() === "runs" || runningRuns().length > 0) await refreshTail();
    if (tab() === "diagnostics") await refreshDiagnostics();
    setLastRefreshAt(Date.now());
  }

  async function runPreflight() {
    await withUiGuard("Running preflight", async () => {
      if (!canPreflight()) {
        throw new Error("Preflight requires online service, selected device and at least one encoder.");
      }
      const resp = await fetch("/v1/trainer/preflight", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          serial: serial(),
          adb_push_mb: 8,
          shell_rtt_loops: 12,
        }),
      });
      const body = (await resp.json()) as Record<string, unknown>;
      if (!resp.ok) throw new Error(String(body.error || "preflight failed"));
      const push = Number(valueAt(body, ["adb_push", "throughput_mb_s"]) || 0);
      const rtt = Number(valueAt(body, ["adb_shell_rtt", "rtt_p95_ms"]) || 0);
      setPreflightText(`push=${push.toFixed(2)}MB/s, shell_rtt_p95=${rtt.toFixed(2)}ms`);
      await refreshDiagnostics();
    });
  }

  async function startTraining() {
    await withUiGuard("Starting training", async () => {
      if (!canStart()) throw new Error(hardBlockers().join("; "));
      setTail(null);
      setSelectedRunId("");
      const encoders =
        encoderMode() === "single" ? [selectedEncoders()[0] || "h264"] : selectedEncoders();
      const resp = await fetch("/v1/trainer/start", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          serial: serial(),
          profile_name: profileName().trim(),
          mode: mode(),
          trials: Number(trials()),
          warmup_sec: 4,
          sample_sec: 12,
          generations: Number(generations()),
          population: Number(population()),
          elite_count: Number(eliteCount()),
          mutation_rate: Number(mutationRate()),
          crossover_rate: Number(crossoverRate()),
          bitrate_min_kbps: Number(bitrateMinKbps()),
          bitrate_max_kbps: Number(bitrateMaxKbps()),
          encoder_mode: encoderMode(),
          encoders,
          overlay: overlay(),
          hud_chart_mode: hudChartMode(),
          hud_font_preset: hudFontPreset(),
        }),
      });
      const body = (await resp.json()) as Record<string, unknown>;
      if (!resp.ok) throw new Error(String(body.error || "start failed"));
      const newRunId = String(body.run_id || "").trim();
      await refreshRuns();
      if (newRunId) {
        setSelectedRunId(newRunId);
      }
      await refreshProfiles();
      await refreshTail();
      if (settings().autoOpenLiveTab) setTab("live");
    });
  }

  async function stopRun(runId: string) {
    await withUiGuard("Stopping run", async () => {
      const resp = await fetch("/v1/trainer/stop", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ run_id: runId }),
      });
      const body = (await resp.json()) as Record<string, unknown>;
      if (!resp.ok) throw new Error(String(body.error || "stop failed"));
      await refreshRuns();
      await refreshTail();
    });
  }

  onMount(async () => {
    await withUiGuard("Loading trainer", async () => {
      await refreshHealth();
      await refreshRuns();
      await refreshProfiles();
      await refreshDevices();
      await refreshDiagnostics();
      await refreshDatasets();
      await refreshTail();
      setLastRefreshAt(Date.now());
    });
    pollId = window.setInterval(async () => {
      try {
        await runPollingTick();
      } catch (err) {
        setLastError(String(err));
      }
    }, settings().pollingMs);
  });

  createEffect(() => {
    const intervalMs = settings().pollingMs;
    if (!pollId) return;
    window.clearInterval(pollId);
    pollId = window.setInterval(async () => {
      try {
        await runPollingTick();
      } catch (err) {
        setLastError(String(err));
      }
    }, intervalMs);
  });

  onCleanup(() => {
    if (pollId) window.clearInterval(pollId);
  });

  const scoreBars = createMemo(() => toBars(hudSeries().score));
  const presentBars = createMemo(() => toBars(hudSeries().present));
  const recvBars = createMemo(() => toBars(hudSeries().recv));
  const dropBars = createMemo(() => toBars(hudSeries().drops, true));
  const scoreSummary = createMemo(() => seriesSummary(hudSeries().score));
  const presentSummary = createMemo(() => seriesSummary(hudSeries().present));
  const recvSummary = createMemo(() => seriesSummary(hudSeries().recv));
  const dropSummary = createMemo(() => seriesSummary(hudSeries().drops));
  const selectedDevice = createMemo(() => devices().find((item) => item.serial === serial()));
  const datasetTrials = createMemo(() => parseDatasetTrials(datasetDetail()?.parameters || {}));
  const datasetScoreBars = createMemo(() => toBars(datasetTrials().map((v) => v.score)));
  const datasetPresentBars = createMemo(() => toBars(datasetTrials().map((v) => v.present_fps_mean)));
  const datasetMbpsBars = createMemo(() => toBars(datasetTrials().map((v) => v.bitrate_mbps_mean)));
  const datasetDropBars = createMemo(() => toBars(datasetTrials().map((v) => v.drop_rate_per_sec), true));
  const datasetScoreSummary = createMemo(() => seriesSummary(datasetTrials().map((v) => v.score)));
  const datasetPresentSummary = createMemo(() => seriesSummary(datasetTrials().map((v) => v.present_fps_mean)));
  const datasetMbpsSummary = createMemo(() => seriesSummary(datasetTrials().map((v) => v.bitrate_mbps_mean)));
  const datasetDropSummary = createMemo(() => seriesSummary(datasetTrials().map((v) => v.drop_rate_per_sec)));
  const previewTrials = createMemo(() => parseDatasetTrials(previewProfileDetail()?.parameters || {}));
  const previewScoreBars = createMemo(() => toBars(previewTrials().map((v) => v.score)));
  const previewFpsBars = createMemo(() => toBars(previewTrials().map((v) => v.present_fps_mean)));
  const previewMbpsBars = createMemo(() => toBars(previewTrials().map((v) => v.bitrate_mbps_mean)));
  const previewScoreSummary = createMemo(() => seriesSummary(previewTrials().map((v) => v.score)));
  const previewFpsSummary = createMemo(() => seriesSummary(previewTrials().map((v) => v.present_fps_mean)));
  const previewMbpsSummary = createMemo(() => seriesSummary(previewTrials().map((v) => v.bitrate_mbps_mean)));

  return (
    <main class={`trainer-shell ${settings().compactDensity ? "density-compact" : "density-roomy"} ${settings().animateUi ? "animate-on" : "animate-off"}`}>
      <header class="topbar">
        <div class="brand">
          <h1>WBeam Trainer</h1>
          <p>Profile lab and live optimization console</p>
        </div>
        <div class="topbar-statuses">
          <span class={`badge ${serviceOnline() ? "ok" : "risk"}`}>{serviceOnline() ? "Service online" : "Service offline"}</span>
          <span class="badge info">Runs: {runs().length}</span>
          <span class="badge info">Devices: {devices().length}</span>
          <span class="badge info">Build: {health().build_revision || "-"}</span>
          <span class="badge muted">Last sync: {new Date(lastRefreshAt()).toLocaleTimeString()}</span>
        </div>
        <div class="topbar-actions">
          <button
            class="quiet"
            disabled={!!busyAction()}
            title="Refresh health, runs, profiles, devices and diagnostics"
            onClick={() =>
              void withUiGuard("Refreshing data", async () => {
                await refreshHealth();
                await refreshRuns();
                await refreshProfiles();
                await refreshDevices();
                await refreshDiagnostics();
                await refreshTail();
              })
            }
          >
            Refresh
          </button>
        </div>
      </header>

      <div class="shell-body">
        <aside class="nav-rail">
          <For each={tabs}>
            {(item) => (
              <button
                class={`nav-btn ${tab() === item.id ? "active" : ""}`}
                title={item.hint}
                onClick={() => setTab(item.id)}
              >
                <span>{item.label}</span>
                <Show when={item.id === "live" && runningRuns().length > 0}>
                  <small class="pulse-dot">live</small>
                </Show>
              </button>
            )}
          </For>
        </aside>

        <section class="content-zone">
          <Show when={busyAction()}>
            <div class="busy-overlay">
              <div class="busy-overlay-card">
                <span class="spinner" />
                <strong>{busyAction()}</strong>
                <small>Please wait, trainer is processing...</small>
              </div>
            </div>
          </Show>
          <Show when={busyAction()}>
            <div class="busy-banner">{busyAction()}...</div>
          </Show>
          <Show when={lastError()}>
            <div class="error-banner">{lastError()}</div>
          </Show>

          <Show when={tab() === "train"}>
            <div class="train-grid">
              <article class="panel card">
                <h2>Run Setup</h2>

                <label>
                  Device
                  <select value={serial()} onInput={(e) => setSerial(e.currentTarget.value)}>
                    <For each={devices()}>
                      {(d) => (
                        <option value={d.serial}>
                          {d.serial} ({d.model || "unknown"}, api {d.api_level || "?"}, {d.state})
                        </option>
                      )}
                    </For>
                  </select>
                </label>

                <label title="Profile folder name used for persistent outputs and run artifacts.">
                  Profile name
                  <input value={profileName()} onInput={(e) => setProfileName(e.currentTarget.value)} />
                </label>

                <div class="two-col">
                  <label title="Optimization objective used to score and rank trials.">
                    Goal mode
                    <select value={mode()} onInput={(e) => setMode(e.currentTarget.value)}>
                      <option value="quality">max_quality</option>
                      <option value="balanced">balanced</option>
                      <option value="latency">low_latency</option>
                      <option value="custom">custom</option>
                    </select>
                  </label>

                  <label title="Single mode enforces one codec, multi mode explores all selected codecs.">
                    Encoder mode
                    <select value={encoderMode()} onInput={(e) => setEncoderMode(e.currentTarget.value)}>
                      <option value="multi">multi</option>
                      <option value="single">single</option>
                    </select>
                  </label>
                </div>

                <div class="chip-row">
                  <label class={`chip ${encH264() ? "on" : ""}`} title="H.264 is broadly compatible and usually stable.">
                    <input type="checkbox" checked={encH264()} onInput={(e) => setEncH264(e.currentTarget.checked)} />
                    h264
                  </label>
                  <label class={`chip ${encH265() ? "on" : ""}`} title="H.265 can improve quality at same bitrate.">
                    <input type="checkbox" checked={encH265()} onInput={(e) => setEncH265(e.currentTarget.checked)} />
                    h265
                  </label>
                  <label class={`chip ${encRawpng() ? "on" : ""}`} title="Raw PNG path for diagnostics, heavy bandwidth usage.">
                    <input type="checkbox" checked={encRawpng()} onInput={(e) => setEncRawpng(e.currentTarget.checked)} />
                    rawpng
                  </label>
                  <label class={`chip ${encMjpeg() ? "on" : ""}`} title="MJPEG fallback path, useful for compatibility probes.">
                    <input type="checkbox" checked={encMjpeg()} onInput={(e) => setEncMjpeg(e.currentTarget.checked)} />
                    mjpeg
                  </label>
                </div>

                <div class="slider-row">
                  <label title="Lower tested bitrate bound.">
                    Bitrate min (Mbps)
                    <input
                      type="number"
                      min="1"
                      max="400"
                      step="0.1"
                      value={kbpsToMbps(bitrateMinKbps())}
                      onInput={(e) => setBitrateMinKbps(mbpsToKbps(Number(e.currentTarget.value || 10)))}
                    />
                  </label>
                  <label title="Upper tested bitrate bound.">
                    Bitrate max (Mbps)
                    <input
                      type="number"
                      min="1"
                      max="400"
                      step="0.1"
                      value={kbpsToMbps(bitrateMaxKbps())}
                      onInput={(e) => setBitrateMaxKbps(mbpsToKbps(Number(e.currentTarget.value || 200)))}
                    />
                  </label>
                </div>

                <div class="slider-wrap" title="Visual range preview for bitrate min/max.">
                  <input
                    type="range"
                    min="1000"
                    max="400000"
                    value={bitrateMinKbps()}
                    onInput={(e) => {
                      const val = parseNum(e.currentTarget.value);
                      if (val !== null) setBitrateMinKbps(Math.min(val, bitrateMaxKbps()));
                    }}
                  />
                  <input
                    type="range"
                    min="1000"
                    max="400000"
                    value={bitrateMaxKbps()}
                    onInput={(e) => {
                      const val = parseNum(e.currentTarget.value);
                      if (val !== null) setBitrateMaxKbps(Math.max(val, bitrateMinKbps()));
                    }}
                  />
                </div>

                <div class="two-col">
                  <label title="Approximate candidate count for first-stage sampling.">
                    Trial budget
                    <input type="number" min="1" max="128" value={trials()} onInput={(e) => setTrials(Number(e.currentTarget.value || 18))} />
                  </label>
                  <label title="Number of evolution loops.">
                    Generations
                    <input type="number" min="1" max="32" value={generations()} onInput={(e) => setGenerations(Number(e.currentTarget.value || 2))} />
                  </label>
                </div>

                <button class="toggle-advanced" onClick={() => setShowAdvanced((prev) => !prev)}>
                  {showAdvanced() ? "Hide advanced tuning" : "Show advanced tuning"}
                </button>

                <Show when={showAdvanced()}>
                  <div class="advanced-box">
                    <div class="two-col">
                      <label title="Population size per generation.">
                        Population
                        <input
                          type="number"
                          min="2"
                          max="256"
                          value={population()}
                          onInput={(e) => setPopulation(Number(e.currentTarget.value || 18))}
                        />
                      </label>
                      <label title="Elite candidates carried to next generation. Must stay below population.">
                        Elite count
                        <input
                          type="number"
                          min="1"
                          max="128"
                          value={eliteCount()}
                          onInput={(e) => setEliteCount(Number(e.currentTarget.value || 6))}
                        />
                      </label>
                    </div>

                    <label title="Higher values explore more aggressively but can destabilize search convergence.">
                      Mutation rate ({mutationRate().toFixed(2)})
                      <div class="slider-pair">
                        <input
                          type="range"
                          min="0"
                          max="1"
                          step="0.01"
                          value={mutationRate()}
                          onInput={(e) => setMutationRate(Number(e.currentTarget.value || 0.34))}
                        />
                        <input
                          type="number"
                          min="0"
                          max="1"
                          step="0.01"
                          value={mutationRate()}
                          onInput={(e) => setMutationRate(Number(e.currentTarget.value || 0.34))}
                        />
                      </div>
                    </label>

                    <label title="Controls how often child candidates merge parameters from two parents.">
                      Crossover rate ({crossoverRate().toFixed(2)})
                      <div class="slider-pair">
                        <input
                          type="range"
                          min="0"
                          max="1"
                          step="0.01"
                          value={crossoverRate()}
                          onInput={(e) => setCrossoverRate(Number(e.currentTarget.value || 0.5))}
                        />
                        <input
                          type="number"
                          min="0"
                          max="1"
                          step="0.01"
                          value={crossoverRate()}
                          onInput={(e) => setCrossoverRate(Number(e.currentTarget.value || 0.5))}
                        />
                      </div>
                    </label>

                    <label class="switch" title="Streams a multi-corner HUD overlay on the Android display during training.">
                      <input type="checkbox" checked={overlay()} onInput={(e) => setOverlay(e.currentTarget.checked)} />
                      <span>Show on-device HUD overlay</span>
                    </label>
                    <label title="Overlay trend style rendered on Android HUD during training.">
                      HUD chart style
                      <select value={hudChartMode()} onInput={(e) => setHudChartMode(e.currentTarget.value)}>
                        <option value="bars">bars</option>
                        <option value="line">line</option>
                      </select>
                    </label>
                    <label title="HUD font preset used on Android overlay. Compact is recommended default.">
                      HUD font preset
                      <select value={hudFontPreset()} onInput={(e) => setHudFontPreset(e.currentTarget.value)}>
                        <option value="compact">compact (JetBrains 13)</option>
                        <option value="dense">dense (JetBrains 12)</option>
                        <option value="arcade">arcade (IBM Plex 14)</option>
                        <option value="system">system mono</option>
                      </select>
                    </label>
                  </div>
                </Show>
              </article>

              <article class="panel card">
                <h2>Readiness</h2>
                <div class="meta-grid">
                  <div class="meta-item"><strong>Service</strong><span>{health().state || "-"}</span></div>
                  <div class="meta-item"><strong>Build</strong><span class="mono">{health().build_revision || "-"}</span></div>
                  <div class="meta-item"><strong>Selected device</strong><span>{serial() || "-"}</span></div>
                  <div class="meta-item"><strong>Device state</strong><span>{selectedDevice()?.state || "-"}</span></div>
                  <div class="meta-item"><strong>Preflight</strong><span>{preflightText()}</span></div>
                  <div class="meta-item"><strong>Encoders</strong><span>{selectedEncoders().join(", ") || "-"}</span></div>
                </div>

                <Show when={hardBlockers().length > 0}>
                  <div class="list-block risk">
                    <h3>Blocking issues</h3>
                    <ul>
                      <For each={hardBlockers()}>{(problem) => <li>{problem}</li>}</For>
                    </ul>
                  </div>
                </Show>

                <Show when={softWarnings().length > 0}>
                  <div class="list-block warn">
                    <h3>Warnings</h3>
                    <ul>
                      <For each={softWarnings()}>{(warning) => <li>{warning}</li>}</For>
                    </ul>
                  </div>
                </Show>

                <div class="actions-row">
                  <button class="primary" disabled={!!busyAction() || !canPreflight()} onClick={() => void runPreflight()}>
                    Run Preflight
                  </button>
                  <button class="primary strong" disabled={!!busyAction() || !canStart()} onClick={() => void startTraining()}>
                    Start Training
                  </button>
                </div>
              </article>
            </div>
          </Show>

          <Show when={tab() === "live"}>
            <div class="live-layout">
              <article class="panel card fixed-left">
                <h2>Run Context</h2>
                <div class="meta-grid">
                  <div class="meta-item"><strong>Run</strong><span class="mono">{activeRun()?.run_id || "-"}</span></div>
                  <div class="meta-item"><strong>Status</strong><span>{activeRun()?.status || "idle"}</span></div>
                  <div class="meta-item"><strong>Profile</strong><span>{activeRun()?.profile_name || profileName()}</span></div>
                  <div class="meta-item"><strong>Device</strong><span>{activeRun()?.serial || serial() || "-"}</span></div>
                  <div class="meta-item"><strong>Mode</strong><span>{hud().mode !== "-" ? hud().mode : activeRun()?.mode || "-"}</span></div>
                  <div class="meta-item"><strong>Generation</strong><span>{hud().generation}</span></div>
                  <div class="meta-item"><strong>Progress</strong><span>{hud().progress}</span></div>
                  <div class="meta-item"><strong>HUD charts</strong><span>{activeRun()?.hud_chart_mode || hudChartMode()}</span></div>
                  <div class="meta-item"><strong>HUD font</strong><span>{activeRun()?.hud_font_preset || hudFontPreset()}</span></div>
                  <div class="meta-item">
                    <strong>Live health</strong>
                    <span class={`live-pill ${liveHealth().tone}`}>{liveHealth().state}</span>
                  </div>
                </div>
                <Show when={activeRun() && (activeRun()!.status === "running" || activeRun()!.status === "stopping")}>
                  <button class="danger" disabled={!!busyAction()} onClick={() => void stopRun(activeRun()!.run_id)}>
                    Stop Run
                  </button>
                </Show>
              </article>

              <article class="panel card live-center">
                <h2>Live HUD</h2>
                <Show when={!hasLiveSamples()}>
                  <div class="empty-live">
                    <strong>Waiting for live samples</strong>
                    <span>When trials emit metrics, charts and KPI cards update automatically.</span>
                  </div>
                </Show>
                <div class="kpi-grid">
                  <div class="kpi-card">
                    <span>Trial</span>
                    <strong>{hud().trialId}</strong>
                  </div>
                  <div class="kpi-card">
                    <span>Score</span>
                    <strong>{hud().score}</strong>
                  </div>
                  <div class="kpi-card">
                    <span>Present FPS</span>
                    <strong>{hud().presentFps}</strong>
                  </div>
                  <div class="kpi-card">
                    <span>Pipe FPS</span>
                    <strong>{hud().recvFps}</strong>
                  </div>
                  <div class="kpi-card">
                    <span>Live Mbps</span>
                    <strong>{hud().bitrateMbps}</strong>
                  </div>
                  <div class="kpi-card">
                    <span>E2E p95 (ms)</span>
                    <strong>{hud().e2eP95Ms}</strong>
                  </div>
                  <div class="kpi-card">
                    <span>Drops</span>
                    <strong>{hud().dropsPerSec}</strong>
                  </div>
                </div>

                <div class="chart-grid">
                  <section class="chart-card">
                    <h3>Score trend</h3>
                    <p class="chart-stats">last {scoreSummary().last} | min {scoreSummary().min} | max {scoreSummary().max}</p>
                    <div class="bar-row">
                      <For each={scoreBars()}>
                        {(bar, idx) => (
                          <span
                            class={`bar ${bar.cls}`}
                            style={{ height: `${bar.pct}%` }}
                            title={`sample ${idx() + 1}: ${bar.value.toFixed(2)}`}
                          />
                        )}
                      </For>
                    </div>
                  </section>
                  <section class="chart-card">
                    <h3>Present FPS trend</h3>
                    <p class="chart-stats">last {presentSummary().last} | min {presentSummary().min} | max {presentSummary().max}</p>
                    <div class="bar-row">
                      <For each={presentBars()}>
                        {(bar, idx) => (
                          <span
                            class={`bar ${bar.cls}`}
                            style={{ height: `${bar.pct}%` }}
                            title={`sample ${idx() + 1}: ${bar.value.toFixed(2)} fps`}
                          />
                        )}
                      </For>
                    </div>
                  </section>
                  <section class="chart-card">
                    <h3>Pipeline FPS trend</h3>
                    <p class="chart-stats">last {recvSummary().last} | min {recvSummary().min} | max {recvSummary().max}</p>
                    <div class="bar-row">
                      <For each={recvBars()}>
                        {(bar, idx) => (
                          <span
                            class={`bar ${bar.cls}`}
                            style={{ height: `${bar.pct}%` }}
                            title={`sample ${idx() + 1}: ${bar.value.toFixed(2)} fps`}
                          />
                        )}
                      </For>
                    </div>
                  </section>
                  <section class="chart-card">
                    <h3>Drop trend</h3>
                    <p class="chart-stats">last {dropSummary().last} | min {dropSummary().min} | max {dropSummary().max}</p>
                    <div class="bar-row">
                      <For each={dropBars()}>
                        {(bar, idx) => (
                          <span
                            class={`bar ${bar.cls}`}
                            style={{ height: `${bar.pct}%` }}
                            title={`sample ${idx() + 1}: ${bar.value.toFixed(2)} drop`}
                          />
                        )}
                      </For>
                    </div>
                  </section>
                </div>

                <section class="timeline-card">
                  <h3>Stage Timeline</h3>
                  <Show
                    when={liveStages().length > 0}
                    fallback={<p class="hint">No stage events yet. Start training or wait for next trial events.</p>}
                  >
                    <div class="timeline-list">
                      <For each={liveStages()}>
                        {(stage) => (
                          <div class={`timeline-item ${stage.level}`}>
                            <span class="timeline-head">
                              <strong>{stage.label}</strong>
                              <small>{stage.ts}</small>
                            </span>
                            <span class="mono">{stage.detail}</span>
                          </div>
                        )}
                      </For>
                    </div>
                  </Show>
                </section>
              </article>

              <article class="panel card fixed-right">
                <h2>Event Log</h2>
                <pre class="log-tail">
                  <For each={tail()?.lines || []}>{(line) => <div>{line}</div>}</For>
                </pre>
              </article>
            </div>
          </Show>

          <Show when={tab() === "runs"}>
            <article class="panel card">
              <h2>Runs</h2>
              <div class="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Run ID</th>
                      <th>Status</th>
                      <th>Profile</th>
                      <th>Device</th>
                      <th>Mode</th>
                      <th>Started</th>
                      <th>Finished</th>
                      <th>Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    <For each={runs()}>
                      {(r) => (
                        <tr class={selectedRunId() === r.run_id ? "selected-row" : ""}>
                          <td>
                            <button
                              class="link-btn"
                              onClick={() => {
                                setSelectedRunId(r.run_id);
                                setTab("live");
                                void refreshTail();
                              }}
                            >
                              {r.run_id}
                            </button>
                          </td>
                          <td>{r.status}</td>
                          <td>{r.profile_name}</td>
                          <td>{r.serial}</td>
                          <td>{r.mode}</td>
                          <td>{formatTs(r.started_at_unix_ms)}</td>
                          <td>{formatTs(r.finished_at_unix_ms)}</td>
                          <td>
                            <Show when={r.status === "running" || r.status === "stopping"}>
                              <button class="danger quiet" onClick={() => void stopRun(r.run_id)}>
                                Stop
                              </button>
                            </Show>
                          </td>
                        </tr>
                      )}
                    </For>
                  </tbody>
                </table>
              </div>
            </article>
          </Show>

          <Show when={tab() === "profiles"}>
            <article class="panel card">
              <h2>Profiles</h2>
              <div class="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Name</th>
                      <th>Engine</th>
                      <th>Serial</th>
                      <th>Best score</th>
                      <th>Preflight</th>
                      <th>Updated</th>
                      <th>Action</th>
                      <th>Path</th>
                    </tr>
                  </thead>
                  <tbody>
                    <For each={profiles()}>
                      {(p) => (
                        <tr class={previewProfileName() === p.profile_name ? "selected-row" : ""}>
                          <td>{p.profile_name}</td>
                          <td>{p.engine || "-"}</td>
                          <td>{p.serial || "-"}</td>
                          <td>{typeof p.best_score === "number" ? p.best_score.toFixed(2) : "-"}</td>
                          <td>{p.has_preflight ? "yes" : "no"}</td>
                          <td>{formatTs(p.updated_at_unix_ms || undefined)}</td>
                          <td>
                            <button
                              class="primary"
                              onClick={() =>
                                void withUiGuard("Loading profile preview", async () => {
                                  await loadPreviewProfile(p.profile_name);
                                })
                              }
                            >
                              Preview
                            </button>
                          </td>
                          <td class="mono">{p.path}</td>
                        </tr>
                      )}
                    </For>
                  </tbody>
                </table>
              </div>

              <Show when={previewProfileDetail()}>
                <section class="dataset-analytics">
                  <h3>Profile Preview: {previewProfileName()}</h3>
                  <div class="meta-grid">
                    <div class="meta-item"><strong>Encoder</strong><span>{pickRuntimeValue(previewProfileDetail()!.profile, "encoder")}</span></div>
                    <div class="meta-item"><strong>Size</strong><span>{pickRuntimeValue(previewProfileDetail()!.profile, "size")}</span></div>
                    <div class="meta-item"><strong>FPS</strong><span>{pickRuntimeValue(previewProfileDetail()!.profile, "fps")}</span></div>
                    <div class="meta-item"><strong>Bitrate</strong><span>{pickRuntimeBitrateMbps(previewProfileDetail()!.profile)}</span></div>
                  </div>
                  <Show when={previewTrials().length > 0} fallback={<p class="hint">No trial history in this profile parameters.</p>}>
                    <div class="chart-grid">
                      <section class="chart-card">
                        <h3>Score history</h3>
                        <p class="chart-stats">last {previewScoreSummary().last} | min {previewScoreSummary().min} | max {previewScoreSummary().max}</p>
                        <div class="bar-row">
                          <For each={previewScoreBars()}>
                            {(bar, idx) => (
                              <span
                                class={`bar ${bar.cls}`}
                                style={{ height: `${bar.pct}%` }}
                                title={`${previewTrials()[idx()]?.trial_id || `t${idx() + 1}`}: ${bar.value.toFixed(2)}`}
                              />
                            )}
                          </For>
                        </div>
                      </section>
                      <section class="chart-card">
                        <h3>Present FPS history</h3>
                        <p class="chart-stats">last {previewFpsSummary().last} | min {previewFpsSummary().min} | max {previewFpsSummary().max}</p>
                        <div class="bar-row">
                          <For each={previewFpsBars()}>
                            {(bar, idx) => (
                              <span
                                class={`bar ${bar.cls}`}
                                style={{ height: `${bar.pct}%` }}
                                title={`${previewTrials()[idx()]?.trial_id || `t${idx() + 1}`}: ${bar.value.toFixed(2)} fps`}
                              />
                            )}
                          </For>
                        </div>
                      </section>
                      <section class="chart-card">
                        <h3>Mbps history</h3>
                        <p class="chart-stats">last {previewMbpsSummary().last} | min {previewMbpsSummary().min} | max {previewMbpsSummary().max}</p>
                        <div class="bar-row">
                          <For each={previewMbpsBars()}>
                            {(bar, idx) => (
                              <span
                                class={`bar ${bar.cls}`}
                                style={{ height: `${bar.pct}%` }}
                                title={`${previewTrials()[idx()]?.trial_id || `t${idx() + 1}`}: ${bar.value.toFixed(2)} Mbps`}
                              />
                            )}
                          </For>
                        </div>
                      </section>
                    </div>
                  </Show>
                </section>
              </Show>
            </article>
          </Show>

          <Show when={tab() === "datasets"}>
            <article class="panel card">
              <h2>Datasets</h2>
              <p class="hint">Datasets expose run artifacts and allow deterministic `Find Optimal Best` recompute.</p>
              <Show when={datasetActionText()}>
                <div class="busy-banner">{datasetActionText()}</div>
              </Show>
              <div class="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Run ID</th>
                      <th>Profile</th>
                      <th>Status</th>
                      <th>Started</th>
                      <th>Finished</th>
                      <th>Best</th>
                      <th>Recompute</th>
                      <th>Artifacts</th>
                      <th>Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    <For each={datasets()}>
                      {(item) => (
                        <tr class={selectedDatasetRunId() === item.run_id ? "selected-row" : ""}>
                          <td class="mono">
                            <button
                              class="link-btn"
                              onClick={() => {
                                setSelectedDatasetRunId(item.run_id);
                                void refreshDatasetDetail(item.run_id);
                              }}
                            >
                              {item.run_id}
                            </button>
                          </td>
                          <td>{item.profile_name}</td>
                          <td>{item.status}</td>
                          <td>{formatTs(item.started_at_unix_ms || undefined)}</td>
                          <td>{formatTs(item.finished_at_unix_ms || undefined)}</td>
                          <td>
                            {item.best_trial || "-"}{" "}
                            {typeof item.best_score === "number" ? `(${item.best_score.toFixed(2)})` : ""}
                          </td>
                          <td>{formatTs(item.last_recompute_at_unix_ms || undefined)}</td>
                          <td class="mono">{item.run_artifacts_dir}</td>
                          <td>
                            <button
                              class="primary"
                              disabled={!!busyAction() || !item.has_parameters}
                              onClick={() => void runDatasetFindOptimal(item.run_id)}
                            >
                              Find Optimal Best
                            </button>
                          </td>
                        </tr>
                      )}
                    </For>
                  </tbody>
                </table>
              </div>
              <Show when={datasetDetail()}>
                <div class="meta-grid">
                  <div class="meta-item">
                    <strong>Dataset run</strong>
                    <span class="mono">{datasetDetail()!.dataset.run_id}</span>
                  </div>
                  <div class="meta-item">
                    <strong>Has parameters</strong>
                    <span>{datasetDetail()!.dataset.has_parameters ? "yes" : "no"}</span>
                  </div>
                  <div class="meta-item">
                    <strong>Has preflight</strong>
                    <span>{datasetDetail()!.dataset.has_preflight ? "yes" : "no"}</span>
                  </div>
                  <div class="meta-item">
                    <strong>Has logs</strong>
                    <span>{datasetDetail()!.dataset.has_logs ? "yes" : "no"}</span>
                  </div>
                  <div class="meta-item">
                    <strong>Best encoder</strong>
                    <span>{String(valueAt(datasetDetail()!.parameters, ["best", "config", "encoder"]) || "-")}</span>
                  </div>
                  <div class="meta-item">
                    <strong>Best size</strong>
                    <span>{String(valueAt(datasetDetail()!.parameters, ["best", "config", "size"]) || "-")}</span>
                  </div>
                  <div class="meta-item">
                    <strong>Best fps</strong>
                    <span>{String(valueAt(datasetDetail()!.parameters, ["best", "config", "fps"]) || "-")}</span>
                  </div>
                  <div class="meta-item">
                    <strong>Best bitrate</strong>
                    <span>
                      {(() => {
                        const kbps = Number(valueAt(datasetDetail()!.parameters, ["best", "config", "bitrate_kbps"]) || 0);
                        if (!Number.isFinite(kbps) || kbps <= 0) return "-";
                        return `${kbpsToMbps(kbps).toFixed(1)} Mbps`;
                      })()}
                    </span>
                  </div>
                </div>

                <section class="dataset-analytics">
                  <h3>Dataset Timeline</h3>
                  <div class="actions-row">
                    <button
                      class="primary"
                      onClick={() =>
                        downloadJson(
                          `${safeName(datasetDetail()!.dataset.run_id)}.dataset.json`,
                          datasetDetail(),
                        )
                      }
                    >
                      Export Dataset JSON
                    </button>
                    <button
                      class="primary"
                      onClick={() =>
                        downloadJson(
                          `${safeName(datasetDetail()!.dataset.profile_name)}.profile.json`,
                          datasetDetail()!.profile,
                        )
                      }
                    >
                      Export Profile JSON
                    </button>
                  </div>
                  <Show when={datasetTrials().length > 0} fallback={<p class="hint">No per-trial results found in this dataset.</p>}>
                    <div class="chart-grid">
                      <section class="chart-card">
                        <h3>Score per trial</h3>
                        <p class="chart-stats">
                          last {datasetScoreSummary().last} | min {datasetScoreSummary().min} | max {datasetScoreSummary().max}
                        </p>
                        <div class="bar-row">
                          <For each={datasetScoreBars()}>
                            {(bar, idx) => (
                              <span
                                class={`bar ${bar.cls}`}
                                style={{ height: `${bar.pct}%` }}
                                title={`${datasetTrials()[idx()]?.trial_id || `t${idx() + 1}`}: ${bar.value.toFixed(2)}`}
                              />
                            )}
                          </For>
                        </div>
                      </section>

                      <section class="chart-card">
                        <h3>Present FPS per trial</h3>
                        <p class="chart-stats">
                          last {datasetPresentSummary().last} | min {datasetPresentSummary().min} | max {datasetPresentSummary().max}
                        </p>
                        <div class="bar-row">
                          <For each={datasetPresentBars()}>
                            {(bar, idx) => (
                              <span
                                class={`bar ${bar.cls}`}
                                style={{ height: `${bar.pct}%` }}
                                title={`${datasetTrials()[idx()]?.trial_id || `t${idx() + 1}`}: ${bar.value.toFixed(2)} fps`}
                              />
                            )}
                          </For>
                        </div>
                      </section>

                      <section class="chart-card">
                        <h3>Live Mbps per trial</h3>
                        <p class="chart-stats">
                          last {datasetMbpsSummary().last} | min {datasetMbpsSummary().min} | max {datasetMbpsSummary().max}
                        </p>
                        <div class="bar-row">
                          <For each={datasetMbpsBars()}>
                            {(bar, idx) => (
                              <span
                                class={`bar ${bar.cls}`}
                                style={{ height: `${bar.pct}%` }}
                                title={`${datasetTrials()[idx()]?.trial_id || `t${idx() + 1}`}: ${bar.value.toFixed(2)} Mbps`}
                              />
                            )}
                          </For>
                        </div>
                      </section>

                      <section class="chart-card">
                        <h3>Drops/s per trial</h3>
                        <p class="chart-stats">
                          last {datasetDropSummary().last} | min {datasetDropSummary().min} | max {datasetDropSummary().max}
                        </p>
                        <div class="bar-row">
                          <For each={datasetDropBars()}>
                            {(bar, idx) => (
                              <span
                                class={`bar ${bar.cls}`}
                                style={{ height: `${bar.pct}%` }}
                                title={`${datasetTrials()[idx()]?.trial_id || `t${idx() + 1}`}: ${bar.value.toFixed(4)} drops/s`}
                              />
                            )}
                          </For>
                        </div>
                      </section>
                    </div>

                    <div class="table-wrap">
                      <table>
                        <thead>
                          <tr>
                            <th>Trial</th>
                            <th>Score</th>
                            <th>Present FPS</th>
                            <th>Pipe FPS</th>
                            <th>Mbps</th>
                            <th>Drops/s</th>
                            <th>State</th>
                          </tr>
                        </thead>
                        <tbody>
                          <For each={datasetTrials()}>
                            {(row) => (
                              <tr>
                                <td class="mono">{row.trial_id}</td>
                                <td>{row.score.toFixed(2)}</td>
                                <td>{row.present_fps_mean.toFixed(1)}</td>
                                <td>{row.recv_fps_mean.toFixed(1)}</td>
                                <td>{row.bitrate_mbps_mean.toFixed(1)}</td>
                                <td>{row.drop_rate_per_sec.toFixed(4)}</td>
                                <td>{row.notes}</td>
                              </tr>
                            )}
                          </For>
                        </tbody>
                      </table>
                    </div>
                  </Show>
                </section>
              </Show>
            </article>
          </Show>

          <Show when={tab() === "compare"}>
            <div class="train-grid">
              <article class="panel card">
                <h2>Left profile</h2>
                <select
                  value={leftProfile()}
                  onInput={(e) => {
                    const name = e.currentTarget.value;
                    setLeftProfile(name);
                    void withUiGuard("Loading profile", async () => loadProfileDetail("left", name));
                  }}
                >
                  <For each={profiles()}>{(p) => <option value={p.profile_name}>{p.profile_name}</option>}</For>
                </select>
                <Show when={leftDetail()}>
                  <div class="meta-grid">
                    <div class="meta-item"><strong>Encoder</strong><span>{pickRuntimeValue(leftDetail()!.profile, "encoder")}</span></div>
                    <div class="meta-item"><strong>Size</strong><span>{pickRuntimeValue(leftDetail()!.profile, "size")}</span></div>
                    <div class="meta-item"><strong>FPS</strong><span>{pickRuntimeValue(leftDetail()!.profile, "fps")}</span></div>
                    <div class="meta-item"><strong>Bitrate</strong><span>{pickRuntimeBitrateMbps(leftDetail()!.profile)}</span></div>
                  </div>
                </Show>
              </article>

              <article class="panel card">
                <h2>Right profile</h2>
                <select
                  value={rightProfile()}
                  onInput={(e) => {
                    const name = e.currentTarget.value;
                    setRightProfile(name);
                    void withUiGuard("Loading profile", async () => loadProfileDetail("right", name));
                  }}
                >
                  <For each={profiles()}>{(p) => <option value={p.profile_name}>{p.profile_name}</option>}</For>
                </select>
                <Show when={rightDetail()}>
                  <div class="meta-grid">
                    <div class="meta-item"><strong>Encoder</strong><span>{pickRuntimeValue(rightDetail()!.profile, "encoder")}</span></div>
                    <div class="meta-item"><strong>Size</strong><span>{pickRuntimeValue(rightDetail()!.profile, "size")}</span></div>
                    <div class="meta-item"><strong>FPS</strong><span>{pickRuntimeValue(rightDetail()!.profile, "fps")}</span></div>
                    <div class="meta-item"><strong>Bitrate</strong><span>{pickRuntimeBitrateMbps(rightDetail()!.profile)}</span></div>
                  </div>
                </Show>
              </article>
            </div>
          </Show>

          <Show when={tab() === "devices"}>
            <article class="panel card">
              <h2>Devices</h2>
              <div class="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Serial</th>
                      <th>State</th>
                      <th>Model</th>
                      <th>API</th>
                      <th>Android</th>
                      <th>Stream port</th>
                    </tr>
                  </thead>
                  <tbody>
                    <For each={devices()}>
                      {(d) => (
                        <tr class={serial() === d.serial ? "selected-row" : ""}>
                          <td class="mono">{d.serial}</td>
                          <td>{d.state}</td>
                          <td>{d.model || "-"}</td>
                          <td>{d.api_level || "-"}</td>
                          <td>{d.android_release || "-"}</td>
                          <td>{d.stream_port || "-"}</td>
                        </tr>
                      )}
                    </For>
                  </tbody>
                </table>
              </div>
            </article>
          </Show>

          <Show when={tab() === "validation"}>
            <article class="panel card">
              <h2>Validation</h2>
              <p class="hint">Validation runs quick preflight for the selected profile and device pair.</p>
              <div class="meta-grid">
                <div class="meta-item"><strong>Profile</strong><span>{profileName()}</span></div>
                <div class="meta-item"><strong>Device</strong><span>{serial() || "-"}</span></div>
                <div class="meta-item"><strong>Result</strong><span>{preflightText()}</span></div>
              </div>
              <button class="primary strong" disabled={!!busyAction() || !canPreflight()} onClick={() => void runPreflight()}>
                Run Quick Validation
              </button>
            </article>
          </Show>

          <Show when={tab() === "diagnostics"}>
            <article class="panel card">
              <h2>Diagnostics</h2>
              <div class="meta-grid">
                <div class="meta-item"><strong>Runs in memory</strong><span>{diagnostics()?.runs_count ?? "-"}</span></div>
                <div class="meta-item"><strong>Profile root</strong><span class="mono">{diagnostics()?.profile_root || "-"}</span></div>
              </div>
              <h3>ADB Version</h3>
              <pre class="log-tail">{diagnostics()?.adb_version || "-"}</pre>
              <h3>ADB Devices Raw</h3>
              <pre class="log-tail">{diagnostics()?.adb_devices_raw || "-"}</pre>
              <h3>Daemon Health Snapshot</h3>
              <pre class="log-tail">{JSON.stringify(diagnostics()?.daemon_health || {}, null, 2)}</pre>
            </article>
          </Show>

          <Show when={tab() === "settings"}>
            <article class="panel card">
              <h2>Settings</h2>
              <div class="switch-grid">
                <label class="switch">
                  <input
                    type="checkbox"
                    checked={settings().compactDensity}
                    onInput={(e) => setSettings((prev) => ({ ...prev, compactDensity: e.currentTarget.checked }))}
                  />
                  <span>Compact density</span>
                </label>
                <label class="switch">
                  <input
                    type="checkbox"
                    checked={settings().animateUi}
                    onInput={(e) => setSettings((prev) => ({ ...prev, animateUi: e.currentTarget.checked }))}
                  />
                  <span>Enable UI animations</span>
                </label>
                <label class="switch">
                  <input
                    type="checkbox"
                    checked={settings().autoOpenLiveTab}
                    onInput={(e) => setSettings((prev) => ({ ...prev, autoOpenLiveTab: e.currentTarget.checked }))}
                  />
                  <span>Auto-open Live Run on start</span>
                </label>
                <label class="switch">
                  <input
                    type="checkbox"
                    checked={settings().autoOpenRunResults}
                    onInput={(e) => setSettings((prev) => ({ ...prev, autoOpenRunResults: e.currentTarget.checked }))}
                  />
                  <span>Auto-open run result summary</span>
                </label>
              </div>
              <label title="Refresh interval for trainer polling.">
                Polling (ms)
                <select
                  value={String(settings().pollingMs)}
                  onInput={(e) => {
                    const val = Number(e.currentTarget.value || "2000");
                    setSettings((prev) => ({ ...prev, pollingMs: val }));
                  }}
                >
                  <option value="1200">1200</option>
                  <option value="1600">1600</option>
                  <option value="2000">2000</option>
                  <option value="2600">2600</option>
                  <option value="3200">3200</option>
                </select>
              </label>
              <p class="hint">Settings apply instantly, including polling interval.</p>
            </article>
          </Show>
        </section>
      </div>
    </main>
  );
}
