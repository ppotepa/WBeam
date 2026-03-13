import type { DatasetTrialPoint, ProfileItem } from "./app-types";

export function formatTs(ms?: number | null): string {
  if (!ms) return "-";
  const date = new Date(ms);
  if (Number.isNaN(date.getTime())) return "-";
  return date.toLocaleString();
}

export function parseNum(value: string): number | null {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

export function toBars(values: number[], dangerAbove = false): { value: number; pct: number; cls: string }[] {
  if (!values.length) {
    return [];
  }
  const maxValue = Math.max(1, ...values);
  return values.map((item) => {
    const pct = Math.max(4, Math.min(100, (item / maxValue) * 100));
    let cls: string;
    if (dangerAbove) {
      cls = item > maxValue * 0.65 ? "risk" : item > maxValue * 0.4 ? "warn" : "good";
    } else {
      cls = item < maxValue * 0.35 ? "risk" : item < maxValue * 0.6 ? "warn" : "good";
    }
    return { value: item, pct, cls };
  });
}

export function kbpsToMbps(kbps: number): number {
  return Math.round((kbps / 1000) * 10) / 10;
}

export function mbpsToKbps(mbps: number): number {
  return Math.round(mbps * 1000);
}

export function seriesSummary(values: number[]): { min: string; max: string; last: string } {
  if (!values.length) return { min: "-", max: "-", last: "-" };
  const min = Math.min(...values);
  const max = Math.max(...values);
  const last = values[values.length - 1];
  return { min: min.toFixed(1), max: max.toFixed(1), last: last.toFixed(1) };
}

export function safeName(input: string): string {
  return input.replaceAll(/[^a-zA-Z0-9._-]+/g, "_");
}

export function downloadJson(filename: string, payload: unknown): void {
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

export function trialOrdinal(trialId: string): number {
  const match = /t(\d+)/i.exec(trialId);
  if (!match) return Number.MAX_SAFE_INTEGER;
  return Number(match[1]);
}

export function valueAt(obj: unknown, path: string[]): unknown {
  let cur = obj as Record<string, unknown> | undefined;
  for (const key of path) {
    if (!cur || typeof cur !== "object") return undefined;
    cur = cur[key] as Record<string, unknown> | undefined;
  }
  return cur;
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return false;
  }
  return true;
}

export function parseHudMetric(value: string): number | null {
  if (!value) return null;
  const cleaned = value.replaceAll(/[^0-9.+-]/g, "");
  const num = Number(cleaned);
  return Number.isFinite(num) ? num : null;
}

export function clampNum(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

export function pickRuntimeValue(profile: Record<string, unknown>, key: string): string {
  const value = valueAt(profile, ["profile", "runtime", key]);
  if (value === undefined || value === null) return "-";
  return String(value);
}

export function pickRuntimeBitrateMbps(profile: Record<string, unknown>): string {
  const value = valueAt(profile, ["profile", "runtime", "bitrate_kbps"]);
  const kbps = Number(value);
  if (!Number.isFinite(kbps) || kbps <= 0) return "-";
  return `${kbpsToMbps(kbps).toFixed(1)} Mbps`;
}

export function parseDatasetTrials(parameters: Record<string, unknown>): DatasetTrialPoint[] {
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

export async function fetchJson<T>(path: string): Promise<T> {
  const resp = await fetch(path);
  if (!resp.ok) {
    const body = await resp.text();
    throw new Error(`${resp.status} ${resp.statusText}: ${body}`.slice(0, 320));
  }
  return (await resp.json()) as T;
}

export function profileUpdatedAt(item: ProfileItem): number {
  return Number(item.updated_at_unix_ms || 0);
}
