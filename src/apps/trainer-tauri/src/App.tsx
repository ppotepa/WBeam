import { For, Show, createMemo, createSignal, onCleanup, onMount } from "solid-js";

type Tab =
  | "train"
  | "hud"
  | "profiles"
  | "runs"
  | "compare"
  | "devices"
  | "validation"
  | "diagnostics";

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
  e2eP95Ms: string;
  dropsPerSec: string;
  progress: string;
};

function formatTs(ms?: number | null): string {
  if (!ms) return "-";
  const date = new Date(ms);
  if (Number.isNaN(date.getTime())) return "-";
  return date.toLocaleString();
}

async function fetchJson<T>(path: string): Promise<T> {
  const resp = await fetch(path);
  if (!resp.ok) {
    const body = await resp.text();
    throw new Error(`${resp.status} ${resp.statusText}: ${body}`.slice(0, 300));
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

function parseHud(lines: string[]): HudSnapshot {
  let trialId = "-";
  let score = "-";
  let presentFps = "-";
  let recvFps = "-";
  let e2eP95Ms = "-";
  let dropsPerSec = "-";
  let progress = "-";

  const trialStartRe = /^\[(t\d+)] apply /;
  const trialScoreRe =
    /^\[(t\d+)] score=([0-9.\-]+) present=([0-9.\-]+) recv=([0-9.\-]+) e2e95=([0-9.\-]+)ms drops\/s=([0-9.\-]+)/;
  const progressRe = /^trial space=(\d+) running=(\d+)/;

  for (const line of lines) {
    const start = line.match(trialStartRe);
    if (start) {
      trialId = start[1];
    }
    const scoreMatch = line.match(trialScoreRe);
    if (scoreMatch) {
      trialId = scoreMatch[1];
      score = scoreMatch[2];
      presentFps = scoreMatch[3];
      recvFps = scoreMatch[4];
      e2eP95Ms = scoreMatch[5];
      dropsPerSec = scoreMatch[6];
    }
    const p = line.match(progressRe);
    if (p) {
      progress = `${p[2]}/${p[1]}`;
    }
  }
  return { trialId, score, presentFps, recvFps, e2eP95Ms, dropsPerSec, progress };
}

export default function App() {
  const [tab, setTab] = createSignal<Tab>("train");
  const [health, setHealth] = createSignal<Health>({});
  const [runs, setRuns] = createSignal<RunItem[]>([]);
  const [profiles, setProfiles] = createSignal<ProfileItem[]>([]);
  const [devices, setDevices] = createSignal<DeviceInfo[]>([]);
  const [diagnostics, setDiagnostics] = createSignal<Diagnostics | null>(null);
  const [tail, setTail] = createSignal<RunTail | null>(null);
  const [busy, setBusy] = createSignal(false);
  const [lastError, setLastError] = createSignal("");
  const [preflightText, setPreflightText] = createSignal("not started");

  const [serial, setSerial] = createSignal("");
  const [profileName, setProfileName] = createSignal("baseline");
  const [mode, setMode] = createSignal("quality");
  const [trials, setTrials] = createSignal(24);
  const [overlay, setOverlay] = createSignal(true);
  const [selectedRunId, setSelectedRunId] = createSignal("");
  const [leftProfile, setLeftProfile] = createSignal("");
  const [rightProfile, setRightProfile] = createSignal("");
  const [leftDetail, setLeftDetail] = createSignal<ProfileDetail | null>(null);
  const [rightDetail, setRightDetail] = createSignal<ProfileDetail | null>(null);

  let pollId: number | undefined;

  const serviceOnline = createMemo(() => Boolean(health().ok));
  const activeRun = createMemo(() => {
    const selected = selectedRunId();
    if (selected) {
      return runs().find((r) => r.run_id === selected) || runs()[0];
    }
    return runs()[0];
  });
  const hud = createMemo(() => parseHud(tail()?.lines || []));

  async function refreshHealth() {
    const data = await fetchJson<Health>("/v1/health");
    setHealth(data);
  }

  async function refreshRuns() {
    const data = await fetchJson<{ ok: boolean; runs: RunItem[] }>("/v1/trainer/runs");
    setRuns(data.runs || []);
    if (!selectedRunId() && data.runs?.length) {
      setSelectedRunId(data.runs[0].run_id);
    }
  }

  async function refreshProfiles() {
    const data = await fetchJson<{ ok: boolean; profiles: ProfileItem[] }>("/v1/trainer/profiles");
    setProfiles(data.profiles || []);
    if (!leftProfile() && data.profiles?.length) {
      setLeftProfile(data.profiles[0].profile_name);
    }
    if (!rightProfile() && data.profiles?.length > 1) {
      setRightProfile(data.profiles[1].profile_name);
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

  async function refreshTail() {
    const run = activeRun();
    if (!run) {
      setTail(null);
      return;
    }
    const data = await fetchJson<RunTail>(`/v1/trainer/runs/${encodeURIComponent(run.run_id)}/tail?lines=220`);
    setTail(data);
  }

  async function loadProfileDetail(which: "left" | "right", name: string) {
    if (!name) return;
    const data = await fetchJson<ProfileDetail>(`/v1/trainer/profiles/${encodeURIComponent(name)}`);
    if (which === "left") {
      setLeftDetail(data);
    } else {
      setRightDetail(data);
    }
  }

  async function withUiGuard(fn: () => Promise<void>) {
    setBusy(true);
    try {
      await fn();
      setLastError("");
    } catch (err) {
      setLastError(String(err));
    } finally {
      setBusy(false);
    }
  }

  async function runPreflight() {
    await withUiGuard(async () => {
      const resp = await fetch("/v1/trainer/preflight", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          serial: serial(),
          adb_push_mb: 8,
          shell_rtt_loops: 10,
        }),
      });
      const body = (await resp.json()) as Record<string, unknown>;
      if (!resp.ok) {
        throw new Error(String(body.error || "preflight failed"));
      }
      const push = Number(valueAt(body, ["adb_push", "throughput_mb_s"]) || 0);
      const rtt = Number(valueAt(body, ["adb_shell_rtt", "rtt_p95_ms"]) || 0);
      setPreflightText(`push=${push.toFixed(2)}MB/s, shell_rtt_p95=${rtt.toFixed(2)}ms`);
      await refreshDiagnostics();
    });
  }

  async function startTraining() {
    await withUiGuard(async () => {
      const resp = await fetch("/v1/trainer/start", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          serial: serial(),
          profile_name: profileName(),
          mode: mode(),
          trials: Number(trials()),
          warmup_sec: 4,
          sample_sec: 12,
          overlay: overlay(),
        }),
      });
      const body = (await resp.json()) as Record<string, unknown>;
      if (!resp.ok) {
        throw new Error(String(body.error || "start failed"));
      }
      await refreshRuns();
      await refreshProfiles();
      await refreshTail();
    });
  }

  async function stopRun(runId: string) {
    await withUiGuard(async () => {
      const resp = await fetch("/v1/trainer/stop", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ run_id: runId }),
      });
      const body = (await resp.json()) as Record<string, unknown>;
      if (!resp.ok) {
        throw new Error(String(body.error || "stop failed"));
      }
      await refreshRuns();
      await refreshTail();
    });
  }

  onMount(async () => {
    await withUiGuard(async () => {
      await refreshHealth();
      await refreshRuns();
      await refreshProfiles();
      await refreshDevices();
      await refreshDiagnostics();
      await refreshTail();
    });
    pollId = window.setInterval(async () => {
      try {
        await refreshHealth();
        await refreshRuns();
        await refreshDevices();
        if (tab() === "profiles" || tab() === "compare") {
          await refreshProfiles();
        }
        if (tab() === "hud" || tab() === "runs") {
          await refreshTail();
        }
        if (tab() === "diagnostics") {
          await refreshDiagnostics();
        }
      } catch (err) {
        setLastError(String(err));
      }
    }, 2200);
  });

  onCleanup(() => {
    if (pollId) window.clearInterval(pollId);
  });

  return (
    <main class="layout">
      <header class="hero">
        <div>
          <h1>WBeam Trainer V2</h1>
          <p>Dedicated training workstation: preflight, runs, HUD, profiles, compare, validation.</p>
        </div>
        <div class={`status ${serviceOnline() ? "ok" : "down"}`}>{serviceOnline() ? "Service: Online" : "Service: Offline"}</div>
      </header>

      <section class="toolbar">
        <button class={tab() === "train" ? "active" : ""} onClick={() => setTab("train")}>Train</button>
        <button class={tab() === "hud" ? "active" : ""} onClick={() => setTab("hud")}>Live HUD</button>
        <button class={tab() === "profiles" ? "active" : ""} onClick={() => setTab("profiles")}>Profiles</button>
        <button class={tab() === "runs" ? "active" : ""} onClick={() => setTab("runs")}>Runs</button>
        <button class={tab() === "compare" ? "active" : ""} onClick={() => setTab("compare")}>Compare</button>
        <button class={tab() === "devices" ? "active" : ""} onClick={() => setTab("devices")}>Devices</button>
        <button class={tab() === "validation" ? "active" : ""} onClick={() => setTab("validation")}>Validation</button>
        <button class={tab() === "diagnostics" ? "active" : ""} onClick={() => setTab("diagnostics")}>Diagnostics</button>
        <button class="ghost" onClick={() => void withUiGuard(async () => {
          await refreshHealth();
          await refreshRuns();
          await refreshProfiles();
          await refreshDevices();
          await refreshDiagnostics();
          await refreshTail();
        })}>Refresh</button>
      </section>

      <Show when={lastError()}>
        <section class="panel error-panel">{lastError()}</section>
      </Show>

      <Show when={tab() === "train"}>
        <section class="panel grid2">
          <article>
            <h2>Quick Start</h2>
            <label>
              Device
              <select value={serial()} onInput={(e) => setSerial(e.currentTarget.value)}>
                <For each={devices()}>
                  {(d) => <option value={d.serial}>{d.serial} ({d.model || "unknown"}, api {d.api_level || "?"})</option>}
                </For>
              </select>
            </label>
            <label>
              Profile name
              <input value={profileName()} onInput={(e) => setProfileName(e.currentTarget.value)} />
            </label>
            <label>
              Goal mode
              <select value={mode()} onInput={(e) => setMode(e.currentTarget.value)}>
                <option value="quality">max_quality</option>
                <option value="balanced">balanced</option>
                <option value="latency">low_latency</option>
                <option value="custom">custom</option>
              </select>
            </label>
            <label>
              Trial budget
              <input type="number" min="1" max="128" value={trials()} onInput={(e) => setTrials(Number(e.currentTarget.value || 24))} />
            </label>
            <label class="checkbox">
              <input type="checkbox" checked={overlay()} onInput={(e) => setOverlay(e.currentTarget.checked)} />
              Show on-stream tuning overlay
            </label>
            <div class="actions">
              <button class="cta" disabled={busy()} onClick={() => void runPreflight()}>Run Preflight</button>
              <button class="cta" disabled={busy()} onClick={() => void startTraining()}>Start Training</button>
            </div>
          </article>
          <article>
            <h2>Run Context</h2>
            <div class="device-card"><strong>Service state</strong><span>{health().state || "-"}</span></div>
            <div class="device-card"><strong>Build revision</strong><span>{health().build_revision || "-"}</span></div>
            <div class="device-card"><strong>Selected serial</strong><span>{serial() || "-"}</span></div>
            <div class="device-card"><strong>Preflight</strong><span>{preflightText()}</span></div>
          </article>
        </section>
      </Show>

      <Show when={tab() === "hud"}>
        <section class="panel">
          <h2>Live HUD</h2>
          <div class="hud-topline">
            <span>run={activeRun()?.run_id || "-"}</span>
            <span>status={activeRun()?.status || "idle"}</span>
            <span>trial={hud().trialId}</span>
            <span>progress={hud().progress}</span>
            <span>profile={activeRun()?.profile_name || profileName()}</span>
          </div>
          <div class="grid2">
            <div class="device-card"><strong>Score</strong><span>{hud().score}</span></div>
            <div class="device-card"><strong>Present FPS</strong><span>{hud().presentFps}</span></div>
            <div class="device-card"><strong>Recv FPS</strong><span>{hud().recvFps}</span></div>
            <div class="device-card"><strong>E2E p95 (ms)</strong><span>{hud().e2eP95Ms}</span></div>
            <div class="device-card"><strong>Drops/s</strong><span>{hud().dropsPerSec}</span></div>
            <div class="device-card"><strong>Gate note</strong><span>{hud().presentFps === "-" ? "waiting for metrics" : "stream active"}</span></div>
          </div>
          <h3>Live Event Log</h3>
          <pre class="log-tail"><For each={tail()?.lines || []}>{(line) => <div>{line}</div>}</For></pre>
        </section>
      </Show>

      <Show when={tab() === "profiles"}>
        <section class="panel">
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
                  <th>Path</th>
                </tr>
              </thead>
              <tbody>
                <For each={profiles()}>
                  {(p) => (
                    <tr>
                      <td>{p.profile_name}</td>
                      <td>{p.engine || "-"}</td>
                      <td>{p.serial || "-"}</td>
                      <td>{typeof p.best_score === "number" ? p.best_score.toFixed(2) : "-"}</td>
                      <td>{p.has_preflight ? "yes" : "no"}</td>
                      <td>{formatTs(p.updated_at_unix_ms || undefined)}</td>
                      <td class="mono">{p.path}</td>
                    </tr>
                  )}
                </For>
              </tbody>
            </table>
          </div>
        </section>
      </Show>

      <Show when={tab() === "runs"}>
        <section class="panel">
          <h2>Runs</h2>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Run ID</th>
                  <th>Status</th>
                  <th>Profile</th>
                  <th>Serial</th>
                  <th>Started</th>
                  <th>Finished</th>
                  <th>Artifacts</th>
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                <For each={runs()}>
                  {(r) => (
                    <tr>
                      <td>
                        <button onClick={() => {
                          setSelectedRunId(r.run_id);
                          void refreshTail();
                        }}>{r.run_id}</button>
                      </td>
                      <td>{r.status}</td>
                      <td>{r.profile_name}</td>
                      <td>{r.serial}</td>
                      <td>{formatTs(r.started_at_unix_ms)}</td>
                      <td>{formatTs(r.finished_at_unix_ms)}</td>
                      <td class="mono">{r.run_artifacts_dir}</td>
                      <td>
                        <Show when={r.status === "running" || r.status === "stopping"}>
                          <button onClick={() => void stopRun(r.run_id)}>Stop</button>
                        </Show>
                      </td>
                    </tr>
                  )}
                </For>
              </tbody>
            </table>
          </div>
          <h3>Selected Run Tail</h3>
          <pre class="log-tail"><For each={tail()?.lines || []}>{(line) => <div>{line}</div>}</For></pre>
        </section>
      </Show>

      <Show when={tab() === "compare"}>
        <section class="panel grid2">
          <article>
            <h2>Left Profile</h2>
            <select value={leftProfile()} onInput={(e) => {
              const name = e.currentTarget.value;
              setLeftProfile(name);
              void withUiGuard(async () => loadProfileDetail("left", name));
            }}>
              <For each={profiles()}>{(p) => <option value={p.profile_name}>{p.profile_name}</option>}</For>
            </select>
            <Show when={leftDetail()}>
              <div class="device-list">
                <div class="device-card"><strong>Encoder</strong><span>{pickRuntimeValue(leftDetail()!.profile, "encoder")}</span></div>
                <div class="device-card"><strong>Size</strong><span>{pickRuntimeValue(leftDetail()!.profile, "size")}</span></div>
                <div class="device-card"><strong>FPS</strong><span>{pickRuntimeValue(leftDetail()!.profile, "fps")}</span></div>
                <div class="device-card"><strong>Bitrate</strong><span>{pickRuntimeValue(leftDetail()!.profile, "bitrate_kbps")}</span></div>
              </div>
            </Show>
          </article>
          <article>
            <h2>Right Profile</h2>
            <select value={rightProfile()} onInput={(e) => {
              const name = e.currentTarget.value;
              setRightProfile(name);
              void withUiGuard(async () => loadProfileDetail("right", name));
            }}>
              <For each={profiles()}>{(p) => <option value={p.profile_name}>{p.profile_name}</option>}</For>
            </select>
            <Show when={rightDetail()}>
              <div class="device-list">
                <div class="device-card"><strong>Encoder</strong><span>{pickRuntimeValue(rightDetail()!.profile, "encoder")}</span></div>
                <div class="device-card"><strong>Size</strong><span>{pickRuntimeValue(rightDetail()!.profile, "size")}</span></div>
                <div class="device-card"><strong>FPS</strong><span>{pickRuntimeValue(rightDetail()!.profile, "fps")}</span></div>
                <div class="device-card"><strong>Bitrate</strong><span>{pickRuntimeValue(rightDetail()!.profile, "bitrate_kbps")}</span></div>
              </div>
            </Show>
          </article>
        </section>
      </Show>

      <Show when={tab() === "devices"}>
        <section class="panel">
          <h2>ADB Devices</h2>
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
                    <tr>
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
        </section>
      </Show>

      <Show when={tab() === "validation"}>
        <section class="panel">
          <h2>Validation</h2>
          <p class="hint">Validation currently runs quick preflight on the selected device/profile pair.</p>
          <div class="device-card"><strong>Selected profile</strong><span>{profileName()}</span></div>
          <div class="device-card"><strong>Selected device</strong><span>{serial() || "-"}</span></div>
          <div class="actions">
            <button class="cta" disabled={busy()} onClick={() => void runPreflight()}>Run Quick Validation</button>
          </div>
          <p class="hint">{preflightText()}</p>
        </section>
      </Show>

      <Show when={tab() === "diagnostics"}>
        <section class="panel">
          <h2>Diagnostics</h2>
          <div class="device-card"><strong>Runs in memory</strong><span>{diagnostics()?.runs_count ?? "-"}</span></div>
          <div class="device-card"><strong>Profile root</strong><span class="mono">{diagnostics()?.profile_root || "-"}</span></div>
          <h3>ADB Version</h3>
          <pre class="log-tail">{diagnostics()?.adb_version || "-"}</pre>
          <h3>ADB Devices Raw</h3>
          <pre class="log-tail">{diagnostics()?.adb_devices_raw || "-"}</pre>
          <h3>Daemon Health Snapshot</h3>
          <pre class="log-tail">{JSON.stringify(diagnostics()?.daemon_health || {}, null, 2)}</pre>
        </section>
      </Show>
    </main>
  );
}
