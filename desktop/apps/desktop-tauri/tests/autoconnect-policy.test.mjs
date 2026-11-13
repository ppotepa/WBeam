import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";
import { loadAppFunction } from "./app-functions.mjs";

function transpile(relPath) {
  const source = readFileSync(new URL(relPath, import.meta.url), "utf8");
  return ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 },
  }).outputText;
}

const backendSource = transpile("../src/managers/backendPolicy.ts");
const policySource = transpile("../src/managers/autoconnectPolicy.ts")
  .replace(/import\s+\{\s*resolveSupportedBackend\s*\}\s+from\s+["']\.\/backendPolicy["'];?/, "");
const defaultConfigSource = transpile("../src/managers/connectDefaults.ts");
const combined = `${backendSource}\n${policySource}\n${defaultConfigSource}`;
const { decideAutoConnectBackend, buildDefaultConnectConfig, resolveSupportedBackend } = await import(`data:text/javascript;base64,${Buffer.from(combined).toString("base64")}`);

const probe = (captureMode, availableBackends) => ({
  reachable: true, supported: true, captureMode, availableBackends,
});

test("multi-device chooses EVDI even with Portal as host default", () => {
  const result = decideAutoConnectBackend(probe("wayland_portal", ["wayland_portal", "evdi"]), 2);
  assert.deepEqual(result, { backend: "evdi", reason: "" });
});

test("multi-device Portal-only does not promise unsupported concurrency", () => {
  const result = decideAutoConnectBackend(probe("wayland_portal", ["wayland_portal"]), 2);
  assert.equal(result.backend, null);
  assert.match(result.reason, /multi-device streaming is not validated/);
});

test("single device selects Portal when available and host default", () => {
  const result = decideAutoConnectBackend(probe("wayland_portal", ["wayland_portal"]), 1);
  assert.deepEqual(result, { backend: "wayland_portal", reason: "" });
});

test("neither Auto nor multi-device uses X11 legacy", () => {
  assert.equal(decideAutoConnectBackend(probe("x11_gst", ["x11_gst"]), 1).backend, null);
  assert.equal(decideAutoConnectBackend(probe("x11_gst", ["x11_gst"]), 3).backend, null);
});

function autoConnectHarness(doctor = async () => ({ ok: true })) {
  const busy = [];
  const summaries = [];
  const requests = [];
  const devices = ["accepted", "failed", "skipped"].map((serial) => ({ serial }));
  const session = {
    devices: () => devices,
    hostProbe: () => probe("evdi", ["evdi"]),
    deviceSnapshotStale: () => false,
    setError: () => {},
    refreshSnapshot: async () => {},
    connectDevice: async (device) => {
      requests.push(device.serial);
      return device.serial === "accepted" ? { ok: true } : { ok: false, error: "start refused" };
    },
  };
  const connect = loadAppFunction("autoConnectAll", {
    session, api: { getEvdiDepsDoctor: doctor }, autoConnectBusy: () => false,
    autoConnectDisabledReason: () => "",
    connectDisabledReason: (device) => device.serial === "skipped" ? "APK missing" : "",
    decideAutoConnectBackend, resolveSupportedBackend, buildDefaultConnectConfig,
    setAutoConnectBusy: (value) => busy.push(value),
    setAutoConnectSummary: (value) => summaries.push(value),
  });
  return { connect, busy, summaries, requests };
}

test("autoconnect reports each outcome and releases its busy guard", async () => {
  const run = autoConnectHarness();
  await run.connect();
  assert.deepEqual(run.requests, ["accepted", "failed"]);
  assert.deepEqual(run.busy, [true, false]);
  const summary = run.summaries.at(-1);
  assert.match(summary, /1 accepted, 1 skipped, 1 failed/);
  assert.match(summary, /failed: failed \(start refused\)/);
  assert.match(summary, /skipped: skipped \(APK missing\)/);
});

test("unavailable EVDI diagnostics never start a session or leave Auto-connect busy", async () => {
  for (const doctor of [async () => ({ ok: false, message: "missing library" }),
    async () => { throw new Error("doctor unavailable"); }]) {
    const run = autoConnectHarness(doctor);
    await run.connect();
    assert.deepEqual(run.requests, []);
    assert.deepEqual(run.busy, [true, false]);
    assert.match(run.summaries.at(-1), /Autoconnect blocked/);
  }
});

test("Basic and Auto-connect use identical adaptive config for both supported backends", () => {
  for (const backend of ["evdi", "wayland_portal"]) {
    assert.deepEqual(buildDefaultConnectConfig(backend), {
      captureBackend: backend,
      encoder: "h264",
      runtimeProfile: "adaptive",
    });
    assert.equal(buildDefaultConnectConfig(backend).manualFps, undefined);
    assert.equal(buildDefaultConnectConfig(backend).manualBitrateKbps, undefined);
  }
});

test("Auto-connect refreshes device list before each attempt and stops on stale capability", () => {
  const app = readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8");
  const start = app.indexOf("  async function autoConnectAll()");
  const end = app.indexOf("  async function basicConnect(", start);
  assert.ok(start > 0 && end > start);
  const auto = app.slice(start, end);
  assert.match(auto, /for \(const snapshot of devices\) \{[\s\S]*?await session\.refreshSnapshot\(\{ silent: true, forceDevices: true \}\)/);
  assert.match(auto, /if \(session\.deviceSnapshotStale\(\)\)/);
  assert.match(auto, /resolveSupportedBackend\(backend, session\.hostProbe\(\)\) !== backend/);
  assert.match(auto, /const connectConfig = buildDefaultConnectConfig\(backend\)/);
  assert.doesNotMatch(auto, /manualFps: 60|manualBitrateKbps: 20000/);
  assert.match(auto, /outcomes\.push/);
});

test("EVDI Auto-connect must pass a real EVDI doctor before starting devices", () => {
  const app = readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8");
  const auto = app.slice(app.indexOf("  async function autoConnectAll()"), app.indexOf("  async function basicConnect("));
  assert.match(auto, /if \(backend === "evdi"\)/);
  assert.match(auto, /doctor = await api\.getEvdiDepsDoctor\(\)/);
  assert.match(auto, /if \(!doctor\.ok\)/);
  assert.match(auto, /Autoconnect blocked/);
  assert.match(auto, /finally \{[\s\S]*?setAutoConnectBusy\(false\)/);
});
