const state = {
  discovery: { adb: true, lan: false, wifi: false },
  devices: [],
  selectedDeviceId: null,
  profileMode: "automatic",
  session: {
    active: false,
    desiredActive: false,
    startRequestedAtMs: 0,
    startTimeoutNotified: false,
    startedAtMs: 0,
    modeText: "n/a",
    fps: 0,
    latencyMs: 0,
    drops: 0,
    bitrateMbps: 0,
    metricsLastAtMs: 0,
  },
  backend: {
    adbAvailable: false,
    adbResponsive: false,
    error: null,
    lastProbeAtMs: 0,
    tauriReady: false,
    deployInFlight: false,
    actionInFlight: false,
    sessionInFlight: false,
    hostInFlight: false,
    hostRunning: null,
    hostState: "UNKNOWN",
    metricsInFlight: false,
  },
  commandOutput: {
    label: "none",
    at: 0,
    text: "No command output yet.",
  },
  events: [{ level: "info", text: "UI ready", at: Date.now() - 2000 }],
};

const MAX_EVENT_TEXT = 320;
const MAX_OUTPUT_TEXT = 12000;
const SESSION_START_TIMEOUT_MS = 25000;

function resolveTauriInvoke() {
  const globalInvoke = window.__TAURI__?.core?.invoke;
  if (typeof globalInvoke === "function") return globalInvoke;
  const internalsInvoke = window.__TAURI_INTERNALS__?.invoke;
  if (typeof internalsInvoke === "function") return internalsInvoke;
  return null;
}

function resolveTauriEventListen() {
  const globalListen = window.__TAURI__?.event?.listen;
  if (typeof globalListen === "function") return globalListen;
  const internalsListen = window.__TAURI_INTERNALS__?.event?.listen;
  if (typeof internalsListen === "function") return internalsListen;
  return null;
}

function hasTauriBridge() {
  return Boolean(resolveTauriInvoke());
}

state.backend.tauriReady = hasTauriBridge();
let usbRefreshTimer = null;
let usbBurstTimer = null;
let usbBurstCount = 0;
let usbBurstLastAction = "change";

function logUi(level, message, data = null) {
  const dataText =
    data === null || data === undefined
      ? ""
      : ` ${typeof data === "string" ? data : JSON.stringify(data)}`;
  const prefix = `[wbeam-ui][${level}] ${message}`;
  const merged = `${message}${dataText}`;

  const invoke = resolveTauriInvoke();
  if (invoke) {
    invoke("ui_log", { level, message: merged }).catch(() => {
      // best effort only
    });
  }

  if (level === "error") {
    if (data !== null) console.error(prefix, data);
    else console.error(prefix);
    return;
  }
  if (level === "warn") {
    if (data !== null) console.warn(prefix, data);
    else console.warn(prefix);
    return;
  }
  if (data !== null) console.log(prefix, data);
  else console.log(prefix);
}

const el = {
  deviceCount: document.getElementById("device-count"),
  deviceList: document.getElementById("device-list"),
  discoveryFilters: document.getElementById("discovery-filters"),
  selectedDeviceLabel: document.getElementById("selected-device-label"),
  deviceDetails: document.getElementById("device-details"),
  sessionState: document.getElementById("session-state"),
  extendedViewStatus: document.getElementById("extended-view-status"),
  extendedViewReason: document.getElementById("extended-view-reason"),
  sessionMode: document.getElementById("session-mode"),
  sessionUptime: document.getElementById("session-uptime"),
  metricFps: document.getElementById("metric-fps"),
  metricLatency: document.getElementById("metric-latency"),
  metricDrops: document.getElementById("metric-drops"),
  metricBitrate: document.getElementById("metric-bitrate"),
  metricHealth: document.getElementById("metric-health"),
  eventLog: document.getElementById("event-log"),
  startSession: document.getElementById("start-session"),
  stopSession: document.getElementById("stop-session"),
  refreshProbe: document.getElementById("refresh-probe"),
  installAll: document.getElementById("install-all"),
  statusRow: document.getElementById("status-row"),
  commandOutput: document.getElementById("command-output"),
  commandOutputMeta: document.getElementById("command-output-meta"),
  clearOutput: document.getElementById("clear-output"),
  hostState: document.getElementById("host-state"),
  hostStatusBtn: document.getElementById("host-status-btn"),
  preflightSummary: document.getElementById("preflight-summary"),
  preflightList: document.getElementById("preflight-list"),
};

function esc(value) {
  return String(value || "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function selectedDevice() {
  return state.devices.find((d) => d.serial === state.selectedDeviceId) || null;
}

function visibleDevices() {
  if (!state.discovery.adb) return [];
  return state.devices;
}

function ensureSelectedDeviceStillValid() {
  if (!state.devices.length) {
    state.selectedDeviceId = null;
    return;
  }
  if (!state.selectedDeviceId) {
    state.selectedDeviceId = state.devices[0].serial;
    return;
  }
  const exists = state.devices.some((d) => d.serial === state.selectedDeviceId);
  if (!exists) state.selectedDeviceId = state.devices[0].serial;
}

function pushEvent(level, text) {
  const raw = String(text ?? "");
  const compact =
    raw.length > MAX_EVENT_TEXT ? `${raw.slice(0, MAX_EVENT_TEXT - 3)}...` : raw;
  state.events.unshift({ level, text: compact, at: Date.now() });
  state.events = state.events.slice(0, 40);
}

function setCommandOutput(label, stdout, stderr, exitCode = null) {
  const out = (stdout || "").trim();
  const err = (stderr || "").trim();
  const lines = [];
  lines.push(`$ ${label}`);
  if (exitCode !== null && exitCode !== undefined) lines.push(`exit: ${exitCode}`);
  if (out) lines.push(`stdout:\n${out}`);
  if (err) lines.push(`stderr:\n${err}`);
  if (!out && !err) lines.push("no output");
  let merged = lines.join("\n\n");
  if (merged.length > MAX_OUTPUT_TEXT) {
    merged = `${merged.slice(0, MAX_OUTPUT_TEXT - 16)}\n\n...[truncated]`;
  }
  state.commandOutput = {
    label,
    at: Date.now(),
    text: merged,
  };
}

function renderStatus() {
  state.backend.tauriReady = hasTauriBridge();
  const bridgeClass = state.backend.tauriReady ? "ok" : "error";
  const bridgeLabel = state.backend.tauriReady ? "BRIDGE: READY" : "BRIDGE: OFF";

  const adbStates = state.devices.map((d) => d.adb_state);
  const hasUnauthorized = adbStates.includes("unauthorized");
  const hasOffline = adbStates.includes("offline");
  const adbClass = !state.backend.adbAvailable
    ? "idle"
    : !state.backend.adbResponsive
    ? "warn"
    : hasUnauthorized || hasOffline
    ? "warn"
    : "ok";
  const adbLabel = !state.backend.adbAvailable
    ? "ADB: MISSING"
    : !state.backend.adbResponsive
    ? "ADB: ERROR"
    : hasUnauthorized
    ? "ADB: AUTH"
    : hasOffline
    ? "ADB: OFFLINE"
    : "ADB: READY";

  const hostClass =
    state.backend.hostRunning === null ? "idle" : state.backend.hostRunning ? "ok" : "warn";
  const hostLabel = state.backend.hostRunning === null
    ? "HOST: CHECK"
    : state.backend.hostRunning
    ? `HOST: ${state.backend.hostState || "READY"}`
    : "HOST: DOWN";

  const device = selectedDevice();
  const deviceReady = Boolean(device && device.adb_state === "device");
  const view = computeExtendedViewStatus(deviceReady);
  const sessionClass = view.level;
  const sessionLabel = `VIEW: ${view.state}`;

  el.statusRow.innerHTML = `
    <span class="pill ${bridgeClass}">${bridgeLabel}</span>
    <span class="pill ${adbClass}">${adbLabel}</span>
    <span class="pill ${hostClass}">${hostLabel}</span>
    <span class="pill ${sessionClass}">${sessionLabel}</span>
  `;
}

function computeExtendedViewStatus(deviceReady) {
  const streamingStates = new Set(["STARTING", "STREAMING", "RECONNECTING"]);
  const waitingForStream =
    state.session.desiredActive &&
    !state.session.active &&
    state.session.startRequestedAtMs > 0 &&
    Date.now() - state.session.startRequestedAtMs < SESSION_START_TIMEOUT_MS;
  const startTimedOut =
    state.session.desiredActive &&
    !state.session.active &&
    state.session.startRequestedAtMs > 0 &&
    Date.now() - state.session.startRequestedAtMs >= SESSION_START_TIMEOUT_MS;

  if (state.backend.hostState === "STREAMING") {
    return { state: "ACTIVE", level: "ok", reason: "host stream is live" };
  }
  if (state.backend.sessionInFlight || streamingStates.has(state.backend.hostState)) {
    return { state: "STARTING", level: "warn", reason: "session/host is transitioning" };
  }
  if (waitingForStream) {
    return { state: "STARTING", level: "warn", reason: "awaiting host stream confirmation" };
  }
  if (startTimedOut) {
    return { state: "BLOCKED", level: "warn", reason: "session start timeout; check host logs/events" };
  }
  if (!state.backend.tauriReady) {
    return { state: "BLOCKED", level: "idle", reason: "tauri bridge unavailable" };
  }
  if (!state.backend.adbAvailable) {
    return { state: "BLOCKED", level: "idle", reason: "adb binary missing on host" };
  }
  if (!state.backend.adbResponsive) {
    return { state: "BLOCKED", level: "idle", reason: "adb daemon not responsive" };
  }
  if (!deviceReady) {
    return { state: "BLOCKED", level: "idle", reason: "no authorized online device selected" };
  }
  if (!state.backend.hostRunning) {
    return { state: "BLOCKED", level: "idle", reason: "host service not reachable" };
  }
  return { state: "READY", level: "ok", reason: "all checks pass; ready to start stream" };
}

function deployButtonLabel() {
  return state.backend.deployInFlight ? "Deploying..." : "Deploy APK";
}

function renderDeviceList() {
  const devices = visibleDevices();
  el.deviceCount.textContent = `DISCOVERED: ${devices.length}`;
  el.deviceList.innerHTML = "";
  if (!devices.length) {
    const message = state.backend.error
      ? `No ADB devices (${state.backend.error})`
      : "No ADB devices detected.";
    el.deviceList.innerHTML = `<p class="placeholder">${esc(message)}</p>`;
    return;
  }
  for (const d of devices) {
    const isOnline = d.adb_state === "device";
    const modelTitle = d.model || d.device_name || d.serial;
    const androidText = d.android_release
      ? `Android ${d.android_release} (API ${d.api_level || "?"})`
      : `State ${d.adb_state}`;
    const btn = document.createElement("button");
    btn.className = `device-card ${d.serial === state.selectedDeviceId ? "selected" : ""}`;
    btn.innerHTML = `
      <div class="line-1">
        <strong>${esc(modelTitle)}</strong>
        <span>${esc(androidText)}</span>
      </div>
      <div class="line-2">ADB SERIAL: ${esc(d.serial)} ${isOnline ? "" : `(${esc(d.adb_state)})`}</div>
    `;
    btn.addEventListener("click", () => {
      state.selectedDeviceId = d.serial;
      pushEvent("info", `Selected ${modelTitle}`);
      render();
    });
    el.deviceList.appendChild(btn);
  }
}

function renderDeviceDetails() {
  const d = selectedDevice();
  if (!d) {
    el.selectedDeviceLabel.textContent = "SELECTED: NONE";
    el.deviceDetails.innerHTML = '<p class="placeholder">Connect a device with USB debugging enabled and run probe.</p>';
    return;
  }
  const modelTitle = d.model || d.device_name || d.serial;
  el.selectedDeviceLabel.textContent = `SELECTED: ${modelTitle}`;
  el.deviceDetails.innerHTML = `
    <h3>Device / Host</h3>
    <p>Manufacturer: ${esc(d.manufacturer || "n/a")}</p>
    <p>Model: ${esc(d.model || d.device_name || "n/a")}</p>
    <p>Serial: ${esc(d.serial)}</p>
    <p>State: ${esc(d.adb_state)}</p>
    <p>Android: ${esc(d.android_release || "n/a")} (API ${esc(d.api_level || "n/a")})</p>
    <p>ABI: ${esc(d.abi || "n/a")}</p>
    <p>Battery: ${esc(d.battery_level || "?")} / status ${esc(d.battery_status || "?")}</p>

    <h3>Profile</h3>
    <div class="switch">
      <button class="switch-btn ${state.profileMode === "automatic" ? "active" : ""}" data-mode="automatic">AUTOMATIC</button>
      <button class="switch-btn ${state.profileMode === "manual" ? "active" : ""}" data-mode="manual">MANUAL</button>
    </div>
    <p>ADB-only discovery mode</p>

    <h3>Actions</h3>
    <div class="actions">
      <button data-action="refresh">Refresh</button>
      <button data-action="details">ADB State</button>
      <button data-action="screenshot">Screenshot</button>
      <button data-action="reverse">Reverse 27183</button>
      <button data-action="forward">Forward 27183</button>
      <button data-action="install">Deploy APK</button>
      <button data-action="clear_forwards">Clear Forwards</button>
    </div>
  `;

  for (const btn of el.deviceDetails.querySelectorAll(".switch-btn")) {
    btn.addEventListener("click", () => {
      state.profileMode = btn.dataset.mode || "automatic";
      pushEvent("info", `Profile mode set to ${state.profileMode}`);
      render();
    });
  }

  for (const btn of el.deviceDetails.querySelectorAll(".actions button")) {
    btn.addEventListener("click", () => {
      const action = btn.dataset.action || "action";
      if (action === "refresh") {
        refreshFromAdb("manual");
        return;
      }
      if (action === "details") {
        runDeviceAction(d.serial, "get_state");
        return;
      }
      if (action === "reverse") {
        runDeviceAction(d.serial, "reverse_default");
        return;
      }
      if (action === "forward") {
        runDeviceAction(d.serial, "forward_default");
        return;
      }
      if (action === "clear_forwards") {
        runDeviceAction(d.serial, "clear_forwards");
        return;
      }
      if (action === "install") {
        installSelectedDevice(d.serial);
        return;
      }
      pushEvent("warn", `${action} not wired yet`);
      render();
    });
  }
}

function renderSession() {
  const d = selectedDevice();
  const preflight = getSessionPreflight();
  const canStart = canStartSession(preflight);
  const view = computeExtendedViewStatus(Boolean(d && d.adb_state === "device"));
  el.startSession.disabled = !canStart || state.session.active || state.backend.sessionInFlight;
  el.stopSession.disabled = !state.session.active || state.backend.sessionInFlight;
  if (!state.session.active || !d) {
    el.sessionState.textContent = "State: Idle";
    el.extendedViewStatus.textContent = `Extended View: ${view.state}`;
    el.extendedViewReason.textContent = `Reason: ${view.reason}`;
    el.sessionMode.textContent = "Mode: n/a";
    el.sessionUptime.textContent = "Uptime: 0s";
    el.metricFps.textContent = "FPS: 0";
    el.metricLatency.textContent = "Latency: 0 ms";
    el.metricDrops.textContent = "Drops: 0";
    el.metricBitrate.textContent = "Bitrate: 0 Mbps";
    el.metricHealth.textContent = "Metrics: n/a";
    el.metricFps.className = "";
    el.metricLatency.className = "";
    el.metricDrops.className = "";
    el.metricBitrate.className = "";
    el.metricHealth.className = "";
    return;
  }
  const uptime = Math.max(0, Math.floor((Date.now() - state.session.startedAtMs) / 1000));
  el.sessionState.textContent = `State: ${state.backend.hostState || "STREAMING"}`;
  el.extendedViewStatus.textContent = `Extended View: ${view.state}`;
  el.extendedViewReason.textContent = `Reason: ${view.reason}`;
  el.sessionMode.textContent = `Mode: ${state.session.modeText || `ADB (${d.serial})`}`;
  el.sessionUptime.textContent = `Uptime: ${uptime}s`;
  el.metricFps.textContent = `FPS: ${state.session.fps}`;
  el.metricLatency.textContent = `Latency: ${state.session.latencyMs} ms`;
  el.metricDrops.textContent = `Drops: ${state.session.drops}`;
  el.metricBitrate.textContent = `Bitrate: ${state.session.bitrateMbps.toFixed(1)} Mbps`;
  const ageMs = state.session.metricsLastAtMs > 0 ? Date.now() - state.session.metricsLastAtMs : Infinity;
  const stale = ageMs > 3000;
  el.metricHealth.textContent = stale
    ? `Metrics: stale (${Math.floor(ageMs / 1000)}s)`
    : `Metrics: live (${Math.floor(ageMs / 1000)}s)`;
  el.metricHealth.className = stale ? "kpi-warn" : "kpi-ok";
  applyKpiClass(el.metricFps, kpiClassFromFps(state.session.fps));
  applyKpiClass(el.metricLatency, kpiClassFromLatency(state.session.latencyMs));
  applyKpiClass(el.metricDrops, kpiClassFromDrops(state.session.drops));
  applyKpiClass(el.metricBitrate, "kpi-ok");
}

function applyKpiClass(node, cls) {
  node.classList.remove("kpi-ok", "kpi-warn", "kpi-fail");
  if (cls) node.classList.add(cls);
}

function kpiClassFromFps(fps) {
  if (fps >= 55) return "kpi-ok";
  if (fps >= 40) return "kpi-warn";
  return "kpi-fail";
}

function kpiClassFromLatency(latencyMs) {
  if (latencyMs <= 80) return "kpi-ok";
  if (latencyMs <= 150) return "kpi-warn";
  return "kpi-fail";
}

function kpiClassFromDrops(dropsPerSec) {
  if (dropsPerSec <= 1) return "kpi-ok";
  if (dropsPerSec <= 5) return "kpi-warn";
  return "kpi-fail";
}

function getSessionPreflight() {
  const d = selectedDevice();
  const apiLevel = Number.parseInt(d?.api_level || "", 10);
  const isLegacyApi17 = Number.isFinite(apiLevel) && apiLevel <= 17;
  const checks = [
    {
      id: "adb_binary",
      title: "ADB on PATH",
      level: state.backend.adbAvailable ? "ok" : "fail",
      fix: "Install adb and ensure it is in PATH.",
    },
    {
      id: "adb_responsive",
      title: "ADB daemon responsive",
      level: state.backend.adbResponsive ? "ok" : "fail",
      fix: "Run `adb kill-server && adb start-server` and retry probe.",
    },
    {
      id: "device_selected",
      title: "Device selected",
      level: d ? "ok" : "fail",
      fix: "Select a device in Discovery panel.",
    },
    {
      id: "device_auth",
      title: "Device authorized/online",
      level: d && d.adb_state === "device" ? "ok" : d ? "fail" : "warn",
      fix: "Accept USB debugging prompt and reconnect USB if needed.",
    },
    {
      id: "host_reachable",
      title: "Host reachable",
      level: state.backend.hostRunning === true ? "ok" : state.backend.hostRunning === false ? "warn" : "warn",
      fix: "Desktop app auto-starts host runtime; if still down run project build and restart desktop app.",
    },
    {
      id: "legacy_tether",
      title: "Legacy API17 networking mode",
      level: isLegacyApi17 ? "warn" : "ok",
      fix: "For API17 use USB tethering (or same LAN host path) before session/deploy.",
    },
  ];
  return checks;
}

function canStartSession(preflight) {
  const critical = ["adb_binary", "adb_responsive", "device_selected", "device_auth"];
  return critical.every((id) => preflight.find((c) => c.id === id)?.level === "ok");
}

function renderPreflight() {
  const checks = getSessionPreflight();
  el.preflightList.innerHTML = "";
  const failed = checks.filter((c) => c.level === "fail");
  const warned = checks.filter((c) => c.level === "warn");
  if (failed.length === 0 && warned.length === 0) {
    el.preflightSummary.textContent = "Preflight: all checks pass.";
  } else if (failed.length > 0) {
    el.preflightSummary.textContent = `Preflight: ${failed.length} blocking issue(s), ${warned.length} warning(s).`;
  } else {
    el.preflightSummary.textContent = `Preflight: ${warned.length} warning(s), no blockers.`;
  }

  for (const c of checks) {
    const row = document.createElement("div");
    row.className = `preflight-item ${c.level}`;
    row.textContent = `${c.title}: ${c.level.toUpperCase()}${c.level !== "ok" ? ` - ${c.fix}` : ""}`;
    el.preflightList.appendChild(row);
  }
}

function renderEvents() {
  el.eventLog.innerHTML = "";
  if (!state.events.length) {
    el.eventLog.innerHTML = '<p class="placeholder">No events yet.</p>';
    return;
  }
  for (const e of state.events) {
    const row = document.createElement("div");
    row.className = `event-item ${e.level}`;
    row.textContent = `[${new Date(e.at).toLocaleTimeString()}] ${e.text}`;
    el.eventLog.appendChild(row);
  }
}

function render() {
  el.installAll.disabled = state.backend.deployInFlight;
  el.installAll.textContent = deployButtonLabel();
  el.hostStatusBtn.disabled = state.backend.hostInFlight;
  if (state.backend.hostRunning === null) el.hostState.textContent = "Host: unknown";
  if (state.backend.hostRunning === true) {
    el.hostState.textContent = `Host: reachable (${state.backend.hostState})`;
  }
  if (state.backend.hostRunning === false) el.hostState.textContent = "Host: not reachable";
  renderStatus();
  renderDeviceList();
  renderDeviceDetails();
  renderSession();
  renderPreflight();
  renderEvents();
  renderCommandOutput();
}

function renderCommandOutput() {
  const metaTime = state.commandOutput.at
    ? new Date(state.commandOutput.at).toLocaleTimeString()
    : "n/a";
  el.commandOutputMeta.textContent = `Last: ${state.commandOutput.label} @ ${metaTime}`;
  el.commandOutput.textContent = state.commandOutput.text;
}

async function refreshFromAdb(source = "manual") {
  const invoke = resolveTauriInvoke();
  state.backend.tauriReady = Boolean(invoke);
  if (!invoke) {
    state.backend.error = "Tauri invoke API not available";
    if (source !== "poll") {
      logUi("error", "refreshFromAdb blocked: invoke API unavailable");
      pushEvent("error", "Cannot probe: invoke API not available");
    }
    render();
    return;
  }
  if (source !== "poll") logUi("info", `refreshFromAdb start source=${source}`);
  try {
    const snapshot = await invoke("adb_probe_once", { source });
    state.backend.adbAvailable = Boolean(snapshot.adb_available);
    state.backend.adbResponsive = Boolean(snapshot.adb_responsive);
    state.backend.error = snapshot.error || null;
    state.backend.lastProbeAtMs = snapshot.probed_at_unix_ms || Date.now();
    state.devices = Array.isArray(snapshot.devices) ? snapshot.devices : [];
    if (source !== "poll") {
      logUi("info", "refreshFromAdb snapshot", {
        devices: state.devices.length,
        adbAvailable: state.backend.adbAvailable,
        adbResponsive: state.backend.adbResponsive,
        error: state.backend.error,
      });
    }
    ensureSelectedDeviceStillValid();
    if (source !== "poll") {
      const probeOut = `devices=${state.devices.length}\nadb_available=${state.backend.adbAvailable}\nadb_responsive=${state.backend.adbResponsive}`;
      const probeErr = snapshot.error || "";
      setCommandOutput("adb_probe_once", probeOut, probeErr, snapshot.error ? 1 : 0);
    }
    if (source !== "poll") {
      pushEvent("info", `Probe complete: ${state.devices.length} device(s)`);
      if (snapshot.error) pushEvent("warn", snapshot.error);
      const unauthorized = state.devices.filter((d) => d.adb_state === "unauthorized").length;
      const offline = state.devices.filter((d) => d.adb_state === "offline").length;
      if (unauthorized > 0) {
        pushEvent("warn", "ADB unauthorized device detected. Confirm USB debugging prompt on device.");
      }
      if (offline > 0) {
        pushEvent("warn", "ADB offline device detected. Reconnect USB cable or restart adb server.");
      }
    }
  } catch (err) {
    if (source !== "poll") logUi("error", "refreshFromAdb failed", err);
    state.backend.adbAvailable = true;
    state.backend.adbResponsive = false;
    state.backend.error = String(err);
    if (source !== "poll") {
      setCommandOutput("adb_probe_once", "", String(err), 1);
      pushEvent("error", `Probe failed: ${err}`);
    }
  }
  render();
}

function summarizeCommandOutput(stdout, stderr) {
  const joined = [stdout, stderr].filter(Boolean).join("\n").trim();
  if (!joined) return "no output";
  const lines = joined
    .split("\n")
    .map((l) => l.trim())
    .filter(Boolean);
  return lines.slice(-3).join(" | ");
}

async function deployAndroidAll() {
  const invoke = resolveTauriInvoke();
  state.backend.tauriReady = Boolean(invoke);
  if (!invoke) {
    logUi("error", "deployAndroidAll blocked: invoke API unavailable");
    pushEvent("error", "Cannot deploy: invoke API not available");
    render();
    return;
  }
  if (state.backend.deployInFlight) return;
  state.backend.deployInFlight = true;
  pushEvent("warn", "Deploy started for all connected ADB devices");
  render();
  try {
    logUi("info", "deployAndroidAll invoke start");
    const result = await invoke("adb_deploy_all");
    logUi("info", "deployAndroidAll invoke result", {
      success: result.success,
      exit: result.exit_code,
    });
    setCommandOutput("deploy-android-all", result.stdout, result.stderr, result.exit_code);
    const summary = summarizeCommandOutput(result.stdout, result.stderr);
    if (result.success) {
      pushEvent("info", `Deploy complete (exit ${result.exit_code ?? 0}): ${summary}`);
    } else {
      pushEvent("error", `Deploy failed (exit ${result.exit_code ?? "?"}): ${summary}`);
    }
  } catch (err) {
    logUi("error", "deployAndroidAll failed", err);
    setCommandOutput("deploy-android-all", "", String(err), 1);
    pushEvent("error", `Deploy command failed: ${err}`);
  } finally {
    state.backend.deployInFlight = false;
    render();
  }
}

async function runDeviceAction(serial, action) {
  const invoke = resolveTauriInvoke();
  state.backend.tauriReady = Boolean(invoke);
  if (!invoke) {
    logUi("error", `runDeviceAction blocked: invoke API unavailable action=${action} serial=${serial}`);
    pushEvent("error", "Cannot run device action: invoke API not available");
    render();
    return;
  }
  if (state.backend.actionInFlight) return;
  state.backend.actionInFlight = true;
  pushEvent("info", `Running ${action} on ${serial}`);
  render();
  try {
    logUi("info", `runDeviceAction invoke start action=${action} serial=${serial}`);
    const result = await invoke("adb_device_action", { serial, action });
    logUi("info", "runDeviceAction invoke result", {
      action,
      serial,
      success: result.success,
      exit: result.exit_code,
    });
    setCommandOutput(`adb_device_action ${action} ${serial}`, result.stdout, result.stderr, result.exit_code);
    const summary = summarizeCommandOutput(result.stdout, result.stderr);
    if (result.success) {
      pushEvent("info", `${action} ok (${result.exit_code ?? 0}): ${summary}`);
    } else {
      pushEvent("error", `${action} failed (${result.exit_code ?? "?"}): ${summary}`);
    }
  } catch (err) {
    logUi("error", `runDeviceAction failed action=${action} serial=${serial}`, err);
    setCommandOutput(`adb_device_action ${action} ${serial}`, "", String(err), 1);
    pushEvent("error", `${action} command failed: ${err}`);
  } finally {
    state.backend.actionInFlight = false;
    render();
  }
}

async function installSelectedDevice(serial) {
  const invoke = resolveTauriInvoke();
  state.backend.tauriReady = Boolean(invoke);
  if (!invoke) {
    logUi("error", `installSelectedDevice blocked: invoke API unavailable serial=${serial}`);
    pushEvent("error", "Cannot install APK: invoke API not available");
    render();
    return;
  }
  if (state.backend.actionInFlight) return;
  state.backend.actionInFlight = true;
  pushEvent("warn", `Deploying APK on ${serial}`);
  render();
  try {
    logUi("info", `installSelectedDevice invoke start serial=${serial}`);
    const result = await invoke("adb_install_device", { serial });
    logUi("info", "installSelectedDevice invoke result", {
      serial,
      success: result.success,
      exit: result.exit_code,
    });
    setCommandOutput(`adb_install_device ${serial}`, result.stdout, result.stderr, result.exit_code);
    const summary = summarizeCommandOutput(result.stdout, result.stderr);
    if (result.success) {
      pushEvent("info", `Deploy OK on ${serial}: ${summary}`);
      refreshFromAdb("manual");
    } else {
      pushEvent("error", `Deploy failed on ${serial} (${result.exit_code ?? "?"}): ${summary}`);
    }
  } catch (err) {
    logUi("error", `installSelectedDevice failed serial=${serial}`, err);
    setCommandOutput(`adb_install_device ${serial}`, "", String(err), 1);
    pushEvent("error", `Deploy command failed on ${serial}: ${err}`);
  } finally {
    state.backend.actionInFlight = false;
    render();
  }
}

async function startSession() {
  const d = selectedDevice();
  const preflight = getSessionPreflight();
  if (!canStartSession(preflight)) {
    state.session.desiredActive = false;
    state.session.startRequestedAtMs = 0;
    state.session.startTimeoutNotified = false;
    const blockers = preflight.filter((c) => c.level === "fail");
    if (blockers.length > 0) {
      pushEvent("error", `Session blocked: ${blockers[0].title}. ${blockers[0].fix}`);
    }
    render();
    return;
  }
  if (!d || d.adb_state !== "device") return;
  const invoke = resolveTauriInvoke();
  state.backend.tauriReady = Boolean(invoke);
  if (!invoke) {
    pushEvent("error", "Cannot start session: invoke API not available");
    render();
    return;
  }
  if (state.backend.sessionInFlight) return;
  state.session.desiredActive = true;
  state.session.startRequestedAtMs = Date.now();
  state.session.startTimeoutNotified = false;
  state.backend.sessionInFlight = true;
  pushEvent("info", `Starting ADB session tunnel for ${d.serial}`);
  render();
  try {
    const hostReady = await ensureHostReadyForSession();
    if (!hostReady) {
      state.session.desiredActive = false;
      pushEvent("error", "Host is not ready; session start aborted");
      return;
    }
    const tunnel = await invoke("adb_session_start", { serial: d.serial });
    setCommandOutput(`adb_session_start ${d.serial}`, tunnel.stdout, tunnel.stderr, tunnel.exit_code);
    if (!tunnel.success) {
      state.session.desiredActive = false;
      pushEvent("error", `Session tunnel start failed: ${summarizeCommandOutput(tunnel.stdout, tunnel.stderr)}`);
      return;
    }

    const hostStart = await runHostAction("host_stream_start");
    if (!hostStart || !hostStart.success) {
      state.session.desiredActive = false;
      pushEvent("error", "Host stream start failed; removing session tunnel");
      const rollback = await invoke("adb_session_stop", { serial: d.serial });
      setCommandOutput(`adb_session_stop ${d.serial} (rollback)`, rollback.stdout, rollback.stderr, rollback.exit_code);
      return;
    }

    state.session.active = true;
    state.session.desiredActive = true;
    state.session.startedAtMs = Date.now();
    state.session.fps = 55;
    state.session.latencyMs = 36;
    state.session.drops = 0;
    state.session.bitrateMbps = 10.2;
    state.session.metricsLastAtMs = 0;
    pushEvent("info", `Session started for ${d.serial}`);
  } catch (err) {
    state.session.desiredActive = false;
    setCommandOutput(`adb_session_start ${d.serial}`, "", String(err), 1);
    pushEvent("error", `Session start command failed: ${err}`);
  } finally {
    state.backend.sessionInFlight = false;
    render();
  }
}

async function stopSession() {
  const d = selectedDevice();
  if (!d) {
    state.session.active = false;
    state.session.desiredActive = false;
    state.session.startRequestedAtMs = 0;
    state.session.startTimeoutNotified = false;
    render();
    return;
  }
  const invoke = resolveTauriInvoke();
  state.backend.tauriReady = Boolean(invoke);
  if (!invoke) {
    pushEvent("error", "Cannot stop session: invoke API not available");
    render();
    return;
  }
  if (state.backend.sessionInFlight) return;
  state.session.desiredActive = false;
  state.session.startRequestedAtMs = 0;
  state.session.startTimeoutNotified = false;
  state.backend.sessionInFlight = true;
  pushEvent("warn", `Stopping ADB session tunnel for ${d.serial}`);
  render();
  try {
    const hostStop = await runHostAction("host_stream_stop");
    if (!hostStop || !hostStop.success) {
      pushEvent("warn", "Host stream stop had warnings; continuing tunnel cleanup");
    }

    const tunnel = await invoke("adb_session_stop", { serial: d.serial });
    setCommandOutput(`adb_session_stop ${d.serial}`, tunnel.stdout, tunnel.stderr, tunnel.exit_code);
    if (!tunnel.success) {
      pushEvent("warn", `Session stop had warnings: ${summarizeCommandOutput(tunnel.stdout, tunnel.stderr)}`);
    } else {
      pushEvent("info", `Session stopped for ${d.serial}`);
    }
  } catch (err) {
    setCommandOutput(`adb_session_stop ${d.serial}`, "", String(err), 1);
    pushEvent("error", `Session stop command failed: ${err}`);
  } finally {
    state.session.active = false;
    state.session.metricsLastAtMs = 0;
    state.backend.sessionInFlight = false;
    render();
  }
}

function markHostStatusFromResult(action, result) {
  if (!["host_status", "host_status_api", "host_up", "host_down", "host_ensure"].includes(action)) {
    return;
  }
  if (action === "host_down") {
    state.backend.hostRunning = false;
    state.backend.hostState = "DOWN";
    state.session.active = false;
    state.session.desiredActive = false;
    state.session.startRequestedAtMs = 0;
    state.session.startTimeoutNotified = false;
    state.session.modeText = "n/a";
    return;
  }
  if (!result.success) {
    state.backend.hostRunning = false;
    if (action === "host_status_api") {
      state.backend.hostState = "UNREACHABLE";
      state.session.active = false;
      state.session.modeText = "n/a";
    }
    return;
  }
  if (action === "host_status_api") {
    const payload = parseStatusPayload(result.stdout);
    if (payload) {
      applyHostStatusPayload(payload);
      return;
    }
  }
  const blob = `${result.stdout || ""}\n${result.stderr || ""}`;
  state.backend.hostRunning = !blob.includes("Control API not reachable");
}

function parseStatusPayload(stdout) {
  const text = String(stdout || "");
  const lines = text.split("\n");
  const jsonStart = lines.findIndex((l) => l.trim().startsWith("{"));
  if (jsonStart < 0) return null;
  const jsonText = lines.slice(jsonStart).join("\n").trim();
  if (!jsonText) return null;
  try {
    return JSON.parse(jsonText);
  } catch {
    return null;
  }
}

function applyHostStatusPayload(payload) {
  const hostState = String(payload?.state || "UNKNOWN").toUpperCase();
  const cfg = payload?.active_config || {};
  const prevHostState = state.backend.hostState;
  state.backend.hostRunning = Boolean(payload?.ok);
  state.backend.hostState = hostState;
  if (prevHostState !== hostState) {
    pushEvent("info", `Host state: ${prevHostState} -> ${hostState}`);
  }

  if (cfg && (cfg.profile || cfg.size || cfg.fps)) {
    const size = cfg.size || "auto";
    const fps = Number.isFinite(cfg.fps) ? cfg.fps : "?";
    const profile = cfg.profile || "auto";
    state.session.modeText = `WBTP ${size}@${fps} ${profile}`;
  }

  const streamingStates = new Set(["STARTING", "STREAMING", "RECONNECTING"]);
  if (streamingStates.has(hostState)) {
    if (!state.session.active) state.session.startedAtMs = Date.now();
    state.session.active = true;
    state.session.desiredActive = true;
    state.session.startRequestedAtMs = 0;
    state.session.startTimeoutNotified = false;
    return;
  }

  const idleStates = new Set(["IDLE", "STOPPING", "ERROR", "DOWN", "UNREACHABLE"]);
  if (idleStates.has(hostState) && !state.backend.sessionInFlight) {
    state.session.active = false;
    if (!state.session.desiredActive) {
      state.session.startRequestedAtMs = 0;
      state.session.startTimeoutNotified = false;
    }
    state.session.metricsLastAtMs = 0;
  }
}

async function runHostAction(action, options = {}) {
  const silent = Boolean(options.silent);
  const invoke = resolveTauriInvoke();
  state.backend.tauriReady = Boolean(invoke);
  if (!invoke) {
    logUi("error", `runHostAction blocked: invoke API unavailable action=${action}`);
    if (!silent) pushEvent("error", "Cannot run host action: invoke API not available");
    render();
    return null;
  }
  if (state.backend.hostInFlight) return null;
  state.backend.hostInFlight = true;
  if (!silent) pushEvent("info", `Running ${action}`);
  if (!silent) logUi("info", `runHostAction invoke start action=${action}`);
  render();
  try {
    const result = await invoke(action);
    if (!silent) {
      logUi("info", "runHostAction invoke result", {
        action,
        success: result.success,
        exit: result.exit_code,
      });
    }
    if (!silent) setCommandOutput(action, result.stdout, result.stderr, result.exit_code);
    markHostStatusFromResult(action, result);
    const summary = summarizeCommandOutput(result.stdout, result.stderr);
    if (result.success) {
      if (!silent) pushEvent("info", `${action} ok: ${summary}`);
    } else {
      if (!silent) pushEvent("error", `${action} failed: ${summary}`);
    }
    return result;
  } catch (err) {
    if (!silent) logUi("error", `runHostAction failed action=${action}`, err);
    if (!silent) setCommandOutput(action, "", String(err), 1);
    state.backend.hostRunning = false;
    if (!silent) pushEvent("error", `${action} command failed: ${err}`);
    return null;
  } finally {
    state.backend.hostInFlight = false;
    render();
  }
}

async function ensureHostReadyForSession() {
  const ensured = await runHostAction("host_ensure");
  if (!ensured || !ensured.success) return false;
  const check = await runHostAction("host_status_api");
  return Boolean(check && check.success && state.backend.hostRunning);
}

function parseMetricsPayload(stdout) {
  const text = String(stdout || "");
  const lines = text.split("\n");
  const jsonStart = lines.findIndex((l) => l.trim().startsWith("{"));
  if (jsonStart < 0) return null;
  const jsonText = lines.slice(jsonStart).join("\n").trim();
  if (!jsonText) return null;
  try {
    return JSON.parse(jsonText);
  } catch {
    return null;
  }
}

async function pollHostMetrics() {
  if (!state.session.active) return;
  const invoke = resolveTauriInvoke();
  state.backend.tauriReady = Boolean(invoke);
  if (!invoke || state.backend.metricsInFlight) return;
  state.backend.metricsInFlight = true;
  try {
    const result = await invoke("host_metrics");
    if (!result || !result.success) return;
    const payload = parseMetricsPayload(result.stdout);
    if (!payload || !payload.metrics) return;
    const metrics = payload.metrics;
    const kpi = metrics.kpi || {};
    const latest = metrics.latest_client_metrics || {};

    state.session.fps = Number.isFinite(kpi.present_fps)
      ? Number(kpi.present_fps.toFixed(1))
      : Number.isFinite(kpi.recv_fps)
      ? Number(kpi.recv_fps.toFixed(1))
      : state.session.fps;
    state.session.latencyMs = Number.isFinite(kpi.e2e_latency_ms_p95)
      ? Number(kpi.e2e_latency_ms_p95.toFixed(1))
      : state.session.latencyMs;

    const dropsPerSec = Number.isFinite(latest.too_late_frames)
      ? Number(latest.too_late_frames)
      : Number.isFinite(latest.dropped_frames)
      ? Number(latest.dropped_frames)
      : 0;
    state.session.drops = dropsPerSec;
    state.session.bitrateMbps = Number.isFinite(metrics.bitrate_actual_bps)
      ? Number((metrics.bitrate_actual_bps / 1_000_000).toFixed(2))
      : state.session.bitrateMbps;
    state.session.metricsLastAtMs = Date.now();
    renderSession();
  } catch (err) {
    logUi("warn", "pollHostMetrics failed", err);
  } finally {
    state.backend.metricsInFlight = false;
  }
}

function scheduleUsbRefresh() {
  if (usbRefreshTimer) return;
  usbRefreshTimer = setTimeout(() => {
    usbRefreshTimer = null;
    refreshFromAdb("udev");
  }, 450);
}

function scheduleUsbBurstEvent(action) {
  usbBurstCount += 1;
  usbBurstLastAction = action || usbBurstLastAction;
  if (usbBurstTimer) return;
  usbBurstTimer = setTimeout(() => {
    const count = usbBurstCount;
    const lastAction = usbBurstLastAction;
    usbBurstTimer = null;
    usbBurstCount = 0;
    usbBurstLastAction = "change";
    pushEvent("info", `USB ${lastAction}: ${count} event(s) -> adb probe refresh`);
    logUi("info", `usb-udev burst action=${lastAction} count=${count}`);
    renderEvents();
  }, 900);
}

function setupUsbUdevListener() {
  const listen = resolveTauriEventListen();
  if (!listen) {
    logUi("warn", "usb-udev listener unavailable on this runtime bridge");
    return;
  }

  listen("usb-udev", (event) => {
    const action = event?.payload?.action || "change";
    scheduleUsbBurstEvent(action);
    scheduleUsbRefresh();
  })
    .then(() => {
      logUi("info", "usb-udev listener active");
    })
    .catch((err) => {
      logUi("warn", "usb-udev listener setup failed", err);
    });
}

function hookEvents() {
  for (const btn of el.discoveryFilters.querySelectorAll("button")) {
    btn.addEventListener("click", () => {
      const source = btn.dataset.source;
      if (!source || source !== "adb") {
        pushEvent("warn", "Only ADB path is enabled for now");
        return;
      }
      state.discovery.adb = !state.discovery.adb;
      btn.classList.toggle("chip-on", state.discovery.adb);
      pushEvent("info", `ADB discovery ${state.discovery.adb ? "enabled" : "disabled"}`);
      render();
    });
  }

  el.refreshProbe.addEventListener("click", () => {
    refreshFromAdb("manual");
  });

  el.installAll.addEventListener("click", () => {
    deployAndroidAll();
  });

  el.startSession.addEventListener("click", () => {
    startSession();
  });

  el.stopSession.addEventListener("click", () => {
    stopSession();
  });

  el.hostStatusBtn.addEventListener("click", () => {
    runHostAction("host_status_api");
  });

  el.clearOutput.addEventListener("click", () => {
    state.commandOutput = {
      label: "cleared",
      at: Date.now(),
      text: "No command output yet.",
    };
    render();
  });
}

setInterval(() => {
  if (state.session.active) {
    pollHostMetrics();
    renderSession();
  }
}, 1000);

setInterval(() => {
  if (!state.session.desiredActive || state.session.active || state.backend.sessionInFlight) return;
  if (!state.backend.hostInFlight) {
    runHostAction("host_status_api", { silent: true });
  }
  if (
    state.session.startRequestedAtMs > 0 &&
    Date.now() - state.session.startRequestedAtMs >= SESSION_START_TIMEOUT_MS &&
    !state.session.startTimeoutNotified
  ) {
    state.session.startTimeoutNotified = true;
    pushEvent("warn", "Session start timeout: stream not confirmed within 25s");
    render();
  }
}, 2000);

setInterval(() => {
  if (state.discovery.adb) refreshFromAdb("poll");
}, 60000);

setInterval(() => {
  if (!state.backend.hostInFlight) {
    runHostAction("host_status_api", { silent: true });
  }
}, 30000);

hookEvents();
state.backend.tauriReady = hasTauriBridge();
if (!state.backend.tauriReady) {
  state.backend.error = "Tauri invoke API not available";
  setCommandOutput("startup", "", "Tauri invoke API not available. Run via ./devtool gui", 1);
  pushEvent("error", "Tauri bridge missing. Run this UI via ./devtool gui.");
  logUi("warn", "Tauri bridge missing; app likely opened directly as static HTML.");
}
render();
setupUsbUdevListener();
refreshFromAdb("startup");
runHostAction("host_ensure", { silent: true }).then(() => {
  runHostAction("host_status_api", { silent: true });
});
