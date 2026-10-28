import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";

async function load(relPath) {
  const source = readFileSync(new URL(relPath, import.meta.url), "utf8");
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 } }).outputText;
  return js;
}
const backend = await load("../src/managers/backendPolicy.ts");
const policy = await load("../src/managers/autoconnectPolicy.ts");
const asText = backend.replace("export function resolveSupportedBackend", "function resolveSupportedBackend")
  + "\n" + policy.replace(/import \{ resolveSupportedBackend \} from [^;]+;/, "");
// Static source validation supplements real browser build tests.
test("multi-device EVDI preferred and Portal-only rejected", () => {
  assert.match(policy, /deviceCount > 1/);
  assert.match(policy, /resolveSupportedBackend\("evdi"/);
  assert.match(policy, /multi-device streaming is not validated/);
  assert.doesNotMatch(policy, /"x11_gst"/);
});

test("autoconnect has per-device outcome and completion summary", () => {
  const app = readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8");
  assert.match(app, /decideAutoConnectBackend\(session\.hostProbe\(\), session\.devices\(\)\.length\)/);
  assert.match(app, /const result = await session\.connectDevice\(device, "virtual_monitor", connectConfig\)/);
  assert.match(app, /if \(result\.ok\) accepted \+= 1/);
  assert.match(app, /setAutoConnectSummary/);
  assert.match(app, /finally \{\s*setAutoConnectBusy\(false\)/);
});
