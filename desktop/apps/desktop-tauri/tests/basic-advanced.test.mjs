import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { loadAppFunction, loadTypeScriptModule } from "./app-functions.mjs";

const app = readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8");
const indicators = readFileSync(new URL("../src/components/DeviceIndicators.tsx", import.meta.url), "utf8");

test("Basic and Advanced have different visible control sets", () => {
  assert.match(app, /<Show when=\{mode\(\) === "advanced"\}>/);
  assert.match(app, /<Show when=\{mode\(\) === "basic" && !session\.service\(\)\.active\}>/);
  assert.match(app, /async function basicConnect\(/);
  assert.match(app, /mode\(\) === "basic" \? void basicConnect\(device\) : openConnectDialog\(device\)/);
  assert.match(app, /async function ensureBasicServiceReady\(/);
  assert.match(app, /<ServiceControls/);
});

test("Basic connects with the supported adaptive profile and blocks legacy capture", async () => {
  const { resolveSupportedBackend } = await loadTypeScriptModule("../src/managers/backendPolicy.ts");
  const { buildDefaultConnectConfig } = await loadTypeScriptModule("../src/managers/connectDefaults.ts");
  const device = { serial: "tablet" };
  for (const backend of ["evdi", "wayland_portal", "x11_gst"]) {
    const requests = [];
    const errors = [];
    const session = {
      deviceSnapshotStale: () => false,
      hostProbe: () => ({ reachable: true, supported: true, captureMode: backend, availableBackends: [backend] }),
      setError: (error) => errors.push(error),
      connectDevice: async (...args) => requests.push(args),
      refreshSnapshot: async () => {},
    };
    const connect = loadAppFunction("basicConnect", {
      session, connectDisabledReason: () => "", resolveSupportedBackend, buildDefaultConnectConfig,
    });
    await connect(device);
    if (backend === "x11_gst") {
      assert.equal(requests.length, 0);
      assert.match(errors[0], /No supported backend/);
    } else {
      assert.deepEqual(requests, [[device, "virtual_monitor", {
        captureBackend: backend, encoder: "h264", runtimeProfile: "adaptive",
      }]]);
      assert.equal(errors.length, 0);
    }
  }
});

test("device indicators are extracted without changing icon behavior", () => {
  assert.match(indicators, /export function BatteryIcon/);
  assert.match(indicators, /export function DeviceTypeIcon/);
  assert.match(app, /import \{ BatteryIcon, DeviceTypeIcon \} from "\.\/components\/DeviceIndicators";/);
  const controls = readFileSync(new URL("../src/components/ServiceControls.tsx", import.meta.url), "utf8");
  assert.match(controls, /export function ServiceControls/);
  assert.match(controls, /props\.onStart\(\)/);
});
