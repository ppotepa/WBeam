import { For, Show, createMemo, createSignal } from "solid-js";

type Tab = "train" | "hud";

const devices = [
  { serial: "HVA6PKNT", api: 34, status: "ready" },
  { serial: "DM7S55KRBQEQU4VO", api: 17, status: "legacy" },
];

const fpsSeries = [59, 60, 58, 61, 60, 59, 58, 57, 59, 60, 60, 61, 60];
const latencySeries = [26, 25, 29, 31, 28, 30, 27, 26, 24, 25, 27, 28, 26];
const dropSeries = [0, 1, 0, 0, 2, 1, 0, 0, 1, 0, 0, 0, 1];

function MiniBar(props: { values: number[]; max: number; tone: "good" | "warn" | "risk" }) {
  return (
    <div class="mini-bar-grid">
      <For each={props.values}>
        {(value) => (
          <span
            class={`mini-bar mini-bar-${props.tone}`}
            style={{ height: `${Math.max(6, (value / props.max) * 100)}%` }}
            title={String(value)}
          />
        )}
      </For>
    </div>
  );
}

export default function App() {
  const [tab, setTab] = createSignal<Tab>("train");
  const [serviceUp, setServiceUp] = createSignal(false);

  const serviceBadge = createMemo(() => (serviceUp() ? "Service: Online" : "Service: Offline"));

  return (
    <main class="layout">
      <header class="hero">
        <div>
          <h1>WBeam Trainer V2</h1>
          <p>Training, telemetry and profile lifecycle in one workspace.</p>
        </div>
        <div class={`status ${serviceUp() ? "ok" : "down"}`}>{serviceBadge()}</div>
      </header>

      <section class="toolbar">
        <button class={tab() === "train" ? "active" : ""} onClick={() => setTab("train")}>Train</button>
        <button class={tab() === "hud" ? "active" : ""} onClick={() => setTab("hud")}>Live HUD</button>
        <button class="ghost" onClick={() => setServiceUp((v) => !v)}>
          {serviceUp() ? "Stop Service" : "Start Service"}
        </button>
      </section>

      <Show when={tab() === "train"}>
        <section class="panel grid2">
          <article>
            <h2>Training Setup</h2>
            <label>
              Profile name
              <input value="baseline_tb350fu_v1" />
            </label>
            <label>
              Goal mode
              <select>
                <option>max_quality</option>
                <option>balanced</option>
                <option>low_latency</option>
              </select>
            </label>
            <label>
              Codec set
              <input value="h264,h265,mjpeg" />
            </label>
            <label>
              Trial budget
              <input value="36" />
            </label>
            <button class="cta">Run Preflight + Train</button>
          </article>

          <article>
            <h2>Devices</h2>
            <div class="device-list">
              <For each={devices}>
                {(device) => (
                  <div class="device-card">
                    <strong>{device.serial}</strong>
                    <span>API {device.api}</span>
                    <span>{device.status}</span>
                  </div>
                )}
              </For>
            </div>
            <p class="hint">Each run persists: profile_name.json + parameters.json + preflight.json.</p>
          </article>
        </section>
      </Show>

      <Show when={tab() === "hud"}>
        <section class="panel">
          <h2>Live Training HUD</h2>
          <div class="hud-topline">
            <span>Generation 2/8</span>
            <span>Trial 11/36</span>
            <span>codec=h265</span>
            <span>size=2000x1200</span>
            <span>fps=60</span>
            <span>bitrate=120000kbps</span>
            <span>score=87.2</span>
          </div>

          <div class="hud-grid">
            <article>
              <h3>FPS Timeline</h3>
              <MiniBar values={fpsSeries} max={70} tone="good" />
            </article>
            <article>
              <h3>Latency p95 (ms)</h3>
              <MiniBar values={latencySeries} max={50} tone="warn" />
            </article>
            <article>
              <h3>Drops / Timeout</h3>
              <MiniBar values={dropSeries} max={5} tone="risk" />
            </article>
            <article>
              <h3>Gate Matrix</h3>
              <ul class="gates">
                <li class="pass">present_fps_p10 >= 0.85 target</li>
                <li class="pass">recv_fps_p10 >= 0.80 target</li>
                <li class="warn">drop_rate <= 3.2/s (close)</li>
                <li class="pass">sample_count >= min</li>
              </ul>
            </article>
          </div>
        </section>
      </Show>
    </main>
  );
}
