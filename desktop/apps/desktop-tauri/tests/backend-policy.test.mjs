import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";

const source = readFileSync(new URL("../src/managers/backendPolicy.ts", import.meta.url), "utf8");
const output = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 },
}).outputText;
const { resolveSupportedBackend } = await import(`data:text/javascript;base64,${Buffer.from(output).toString("base64")}`);
const probe = (captureMode, availableBackends, supported = true) => ({
  reachable: true, supported, captureMode, availableBackends,
});
test("explicit EVDI is valid even with a Portal default", () => {
  assert.equal(resolveSupportedBackend("evdi", probe("wayland_portal", ["wayland_portal", "evdi"])), "evdi");
});
test("auto picks a supported backend, never legacy X11", () => {
  assert.equal(resolveSupportedBackend("auto", probe("x11_gst", ["x11_gst", "evdi"])), "evdi");
  assert.equal(resolveSupportedBackend("auto", probe("x11_gst", ["x11_gst"])), null);
  assert.equal(resolveSupportedBackend("x11_gst", probe("x11_gst", ["x11_gst", "evdi"])), null);
});
test("invalid or unavailable explicit selections are blocked", () => {
  assert.equal(resolveSupportedBackend("evdi", probe("wayland_portal", ["wayland_portal"])), null);
  assert.equal(resolveSupportedBackend("", probe("wayland_portal", ["wayland_portal"])), null);
  assert.equal(resolveSupportedBackend("auto", probe("wayland_portal", ["wayland_portal"])), "wayland_portal");
});
test("host is unreachable or unsupported", () => {
  assert.equal(resolveSupportedBackend("auto", { ...probe("evdi", ["evdi"]), reachable: false }), null);
  assert.equal(resolveSupportedBackend("auto", probe("evdi", ["evdi"], false)), null);
});
