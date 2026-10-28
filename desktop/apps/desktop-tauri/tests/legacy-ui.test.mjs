import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const app = readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8");
const native = readFileSync(new URL("../src-tauri/src/main.rs", import.meta.url), "utf8");

test("only supported backends are offered in the Connect dropdown", () => {
  const options = [...app.matchAll(/<option value="([^"]+)">/g)].map((m) => m[1]);
  const backends = options.filter((value) => ["auto", "evdi", "wayland_portal", "x11", "x11_gst"].includes(value));
  assert.deepEqual(backends, ["auto", "evdi", "wayland_portal"]);
  assert.doesNotMatch(app, /Enable multiple virtual monitors via EVDI \(X11 backend\)/);
});

test("legacy diagnostics have presentation guards", () => {
  assert.match(app, /function safeUiText\(/);
  assert.match(app, /\{safeUiText\(session\.error\(\)\)\}/);
  assert.match(app, /capture=\{supportedCaptureLabel\(session\.hostProbe\(\)\.captureMode\)\}/);
  assert.match(app, /\{safeUiText\(doctor\.installHint\)\}/);
  assert.match(app, /\{safeUiText\(d\(\)\.installHint\)\}/);
  assert.match(app, /Host detected: \$\{supportedCaptureLabel\(session\.hostProbe\(\)\.captureMode\)\}/);
  assert.doesNotMatch(app, /simulated monitor space on X11/);
});

test("legacy notice implementation is retained but not registered in app builder", () => {
  assert.match(native, /fn show_x11_startup_notice_window\(/);
  const builder = native.slice(native.indexOf("fn main() {"));
  assert.doesNotMatch(builder, /show_x11_startup_notice_window\(/);
  assert.doesNotMatch(builder, /should_show_x11_startup_notice\(/);
});
