import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const app = readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8");
const indicators = readFileSync(new URL("../src/components/DeviceIndicators.tsx", import.meta.url), "utf8");

test("Basic and Advanced have different visible control sets", () => {
  assert.match(app, /<Show when=\{mode\(\) === "advanced"\}>/);
  assert.match(app, /<Show when=\{mode\(\) === "basic" && !session\.service\(\)\.active\}>/);
  assert.match(app, /async function basicConnect\(/);
  assert.match(app, /mode\(\) === "basic" \? void basicConnect\(device\) : openConnectDialog\(device\)/);
  assert.match(app, /async function ensureBasicServiceReady\(/);
});

test("Basic path selects supported backend, does not force legacy X11", () => {
  assert.match(app, /resolveSupportedBackend\("auto", session\.hostProbe\(\)\)/);
  assert.match(app, /runtimeProfile: "adaptive"/);
  assert.match(app, /encoder: "h264"/);
});

test("device indicators are extracted without changing icon behavior", () => {
  assert.match(indicators, /export function BatteryIcon/);
  assert.match(indicators, /export function DeviceTypeIcon/);
  assert.match(app, /import \{ BatteryIcon, DeviceTypeIcon \} from "\.\/components\/DeviceIndicators";/);
});
