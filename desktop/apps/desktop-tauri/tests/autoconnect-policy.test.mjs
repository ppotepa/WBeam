import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";

function transpile(relPath) {
  const source = readFileSync(new URL(relPath, import.meta.url), "utf8");
  return ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 },
  }).outputText;
}

const backendSource = transpile("../src/managers/backendPolicy.ts");
const policySource = transpile("../src/managers/autoconnectPolicy.ts")
  .replace(/import\s+\{\s*resolveSupportedBackend\s*\}\s+from\s+["']\.\/backendPolicy["'];?/, "");
const combined = `${backendSource}\n${policySource}`;
const { decideAutoConnectBackend } = await import(`data:text/javascript;base64,${Buffer.from(combined).toString("base64")}`);

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

test("autoconnect renders per-device results and releases its busy guard", () => {
  const app = readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8");
  assert.match(app, /const result = await session\.connectDevice\(device, "virtual_monitor", connectConfig\)/);
  assert.match(app, /if \(result\.ok\) accepted \+= 1/);
  assert.match(app, /setAutoConnectSummary/);
  assert.match(app, /finally \{\s*setAutoConnectBusy\(false\)/);
});
