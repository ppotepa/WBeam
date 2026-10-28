import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const app = readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8");
const native = readFileSync(new URL("../src-tauri/src/virtual_monitor.rs", import.meta.url), "utf8");
const main = readFileSync(new URL("../src-tauri/src/main.rs", import.meta.url), "utf8");
const client = readFileSync(new URL("../src/managers/hostApiManager.ts", import.meta.url), "utf8");

test("EVDI host setup is reachable from the toolbar", () => {
  assert.match(app, /onClick=\{\(\) => void openVirtualSetup\(\)\}/);
  assert.match(app, /async function openVirtualSetup\(\)/);
  assert.match(app, /getEvdiDepsDoctor/);
  assert.match(app, /hasActiveStreams\(\)/);
  assert.doesNotMatch(app, /onClick=\{\(\) => setEvdiWizardVisible\(true\)\}/);
});

test("native EVDI check bypasses the legacy X11 virtual doctor", () => {
  assert.match(main, /evdi_deps_check,/);
  assert.match(native, /async fn evdi_deps_check\(/);
  assert.match(native, /virtual-deps-check\.sh/);
  assert.match(native, /"evdi_kernel"/);
  assert.match(client, /invoke<VirtualDoctor>\("evdi_deps_check"\)/);
});

test("EVDI dependency check does not require Xvfb or xrandr", () => {
  const check = readFileSync(new URL("../../../../scripts/virtual-deps-check.sh", import.meta.url), "utf8");
  const evdi = check.slice(check.indexOf('if [[ "$BACKEND" == "evdi" ]]'), check.indexOf("else\n  # Portal is a user-session service"));
  assert.match(evdi, /modinfo evdi/);
  assert.doesNotMatch(evdi, /command_exists (Xvfb|xrandr)/);
  assert.match(check, /--backend/);
});

test("installer state does not claim finished before status.done", () => {
  assert.match(app, /setVirtualSetupInstalling\(false\);/);
  assert.match(app, /installation outcome unknown/);
  assert.match(app, /disabled=\{virtualSetupInstalling\(\) \|\| !doctor.actionable \|\| doctor.ok \|\| hasActiveStreams\(\)\}/);
});
