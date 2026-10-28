import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";

const source = readFileSync(new URL("../src/managers/sessionManager.ts", import.meta.url), "utf8");
// A small synchronous signal harness tests state transitions without a DOM.
// Production Solid primitives are not modified, and the API is fully mocked.
const shim = `
function createSignal(initial) {
  let current = initial;
  return [() => current, (next) => { current = typeof next === "function" ? next(current) : next; return current; }];
}
function createMemo(compute) { return compute; }
`;
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 },
}).outputText;
const coordinatorSource = readFileSync(new URL("../src/managers/refreshCoordinator.ts", import.meta.url), "utf8");
const coordinatorCode = ts.transpileModule(coordinatorSource, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 },
}).outputText;
const code = compiled
  .replace('import { createMemo, createSignal } from "solid-js";', "")
  .replace('import { createRefreshCoordinator } from "./refreshCoordinator";', "");
const { createSessionManager } = await import(`data:text/javascript;base64,${Buffer.from(shim + coordinatorCode + code).toString("base64")}`);

const device = { serial: "usb-1", deviceClass: "Phone", streamState: "IDLE" };
const service = { available: true, installed: true, active: true, enabled: true, summary: "running" };
const probe = { reachable: true, supported: true, os: "linux", session: "wayland", desktop: "gnome", captureMode: "wayland_portal", availableBackends: ["wayland_portal"], evdiAvailable: false };
function api(overrides = {}) {
  return {
    getServiceStatus: async () => service,
    getHostProbe: async () => probe,
    getDevices: async () => ({ hostApkVersion: "1", daemonApkVersion: "1", devices: [device], error: null }),
    connectDevice: async () => {},
    disconnectDevice: async () => {},
    ...overrides,
  };
}

test("failed connect returns a failure and survives silent refresh", async () => {
  const session = createSessionManager(api({ connectDevice: async () => { throw new Error("USB denied"); } }));
  await session.refreshSnapshot({ forceDevices: true });
  const result = await session.connectDevice(device, "duplicate");
  assert.equal(result.ok, false);
  assert.equal(result.serial, device.serial);
  assert.equal(result.error, "USB denied");
  assert.equal(session.deviceActionErrors()[device.serial], "USB denied");
  await session.refreshSnapshot({ silent: true, forceDevices: true });
  assert.equal(session.deviceActionErrors()[device.serial], "USB denied");
});

test("daemon response with error and devices: [] also preserves previous snapshot", async () => {
  let failing = false;
  const session = createSessionManager(api({
    getDevices: async () => failing
      ? { hostApkVersion: "", daemonApkVersion: "", devices: [], error: "ADB devices failed" }
      : { hostApkVersion: "1", daemonApkVersion: "1", devices: [device], error: null },
  }));
  await session.refreshSnapshot({ forceDevices: true });
  failing = true;
  await session.refreshSnapshot({ forceDevices: true });
  assert.equal(session.devices().length, 1);
  assert.equal(session.deviceSnapshotStale(), true);
  assert.match(session.refreshError(), /ADB devices failed/);
});

test("transient device poll failure retains last snapshot with stale flag", async () => {
  let failing = false;
  const session = createSessionManager(api({
    getDevices: async () => {
      if (failing) throw new Error("ADB offline");
      return { hostApkVersion: "1", daemonApkVersion: "1", devices: [device], error: null };
    },
  }));
  await session.refreshSnapshot({ forceDevices: true });
  assert.equal(session.devices().length, 1);
  failing = true;
  await session.refreshSnapshot({ forceDevices: true });
  assert.equal(session.devices().length, 1);
  assert.equal(session.deviceSnapshotStale(), true);
  assert.match(session.refreshError(), /ADB offline/);
  failing = false;
  await session.refreshSnapshot({ forceDevices: true });
  assert.equal(session.deviceSnapshotStale(), false);
  assert.equal(session.refreshError(), "");
});

test("accepted start does not pretend that streaming was confirmed", async () => {
  const session = createSessionManager(api());
  await session.refreshSnapshot({ forceDevices: true });
  const result = await session.connectDevice(device, "duplicate");
  assert.deepEqual(result, { ok: true, action: "connect", serial: "usb-1", state: "accepted" });
  assert.equal(session.devices()[0].streamState, "IDLE");
});

test("duplicate device actions are guarded while the first is pending", async () => {
  let release;
  const blocked = new Promise((resolve) => { release = resolve; });
  const session = createSessionManager(api({ connectDevice: () => blocked }));
  const first = session.connectDevice(device, "duplicate");
  const second = await session.disconnectDevice(device);
  assert.equal(second.ok, false);
  assert.match(second.error, /already in progress/);
  release();
  assert.equal((await first).ok, true);
  assert.equal(session.deviceActionBusy().length, 0);
});

test("manual Refresh during silent poll queues a forced follow-up instead of disappearing", async () => {
  let releaseFirst;
  let startedFirst;
  const firstInFlight = new Promise((resolve) => { startedFirst = resolve; });
  const holdFirst = new Promise((resolve) => { releaseFirst = resolve; });
  let requests = 0;
  const session = createSessionManager(api({
    getDevices: async () => {
      requests++;
      if (requests === 1) {
        startedFirst();
        await holdFirst;
      }
      return { hostApkVersion: "1", daemonApkVersion: "1", devices: [device], error: null };
    },
  }));
  const background = session.refreshSnapshot({ silent: true, forceDevices: false });
  await firstInFlight;
  const manual = session.refreshSnapshot();
  assert.equal(manual, background);
  assert.equal(session.refreshing(), true);
  releaseFirst();
  await manual;
  assert.equal(requests, 2, "manual request must execute despite initial poll being in flight");
  assert.equal(session.refreshInFlight(), false);
  assert.equal(session.refreshing(), false);
});

test("repeated focus/visibility requests during a poll coalesce into one pass", async () => {
  let releaseFirst;
  let startedFirst;
  const firstInFlight = new Promise((resolve) => { startedFirst = resolve; });
  const holdFirst = new Promise((resolve) => { releaseFirst = resolve; });
  let requests = 0;
  const session = createSessionManager(api({
    getDevices: async () => {
      requests++;
      if (requests === 1) {
        startedFirst();
        await holdFirst;
      }
      return { hostApkVersion: "1", daemonApkVersion: "1", devices: [device], error: null };
    },
  }));
  const background = session.refreshSnapshot({ silent: true, forceDevices: false });
  await firstInFlight;
  void session.refreshSnapshot({ silent: true, forceDevices: true });
  void session.refreshSnapshot({ silent: true, forceDevices: true });
  releaseFirst();
  await background;
  assert.equal(requests, 2);
});
