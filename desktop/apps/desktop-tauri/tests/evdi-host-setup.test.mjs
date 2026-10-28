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

test("native EVDI doctor limits the subprocess independently of JS timeout", () => {
  assert.match(native, /command_output_timeout\(/);
  assert.match(native, /std::time::Duration::from_secs\(4\)/);
  // Missing prerequisites are reported by JSON even if the shell diagnostic
  // itself returns a nonzero exit status.
  assert.match(native, /let ok = response\.get\("ok"\)/);
  assert.match(native, /&& output\.status\.success\(\)/);
});

test("Connect is gated until the EVDI runtime diagnostic succeeds", () => {
  const start = app.indexOf("  const connectDialogBlockReason = () => {");
  const end = app.indexOf("  const [autoConnectBusy", start);
  assert.ok(start >= 0 && end > start);
  const gating = app.slice(start, end);
  assert.match(gating, /if \(backend === "evdi"\)/);
  assert.match(gating, /if \(connectDialogDoctorLoading\(\)\)/);
  assert.match(gating, /if \(!doctor\)/);
  assert.match(gating, /if \(!doctor\.ok\)/);
  assert.match(app, /const blockedReason = connectDialogBlockReason\(\);/);
});

test("Failed EVDI diagnostics block Connect instead of silently proceeding", () => {
  const start = app.indexOf("  async function refreshConnectDialogDoctor()");
  const end = app.indexOf("  function openConnectDialog(", start);
  const refresh = app.slice(start, end);
  assert.match(refresh, /getEvdiDepsDoctor\(\)/);
  assert.match(refresh, /ok: false,/);
  assert.match(refresh, /selectedCaptureBackend\(\) !== "evdi"/);
  assert.match(refresh, /if \(requestId === connectDialogRequestId\) setConnectDialogDoctorLoading\(false\)/);
});

test("Slow connection cannot close a newer Connect dialog", () => {
  const start = app.indexOf("  async function confirmConnect()");
  const end = app.indexOf("  async function openVirtualSetup()", start);
  assert.ok(start >= 0 && end > start);
  const confirm = app.slice(start, end);
  assert.match(confirm, /const requestId = connectDialogRequestId;/);
  assert.match(confirm, /requestId !== connectDialogRequestId/);
  assert.match(confirm, /if \(result\.ok && requestId === connectDialogRequestId/);
  assert.match(confirm, /connectDialogDevice\(\)\?\.serial === device\.serial/);
});
