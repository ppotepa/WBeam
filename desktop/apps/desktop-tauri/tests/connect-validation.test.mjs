import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";

const raw = readFileSync(new URL("../src/managers/connectValidation.ts", import.meta.url), "utf8");
const js = ts.transpileModule(raw, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 },
}).outputText;
const { validateConnectForm } = await import(`data:text/javascript;base64,${Buffer.from(js).toString("base64")}`);
const profile = { key: "p1", backend: "evdi", encoder: "h264" };
const base = {
  captureBackend: "evdi",
  encoder: "h264",
  qualityMode: "manual",
  profileKey: "",
  profiles: [profile],
  manualFps: 60,
  manualMbps: 10,
};

test("valid manual and adaptive configurations", () => {
  assert.deepEqual(validateConnectForm(base), []);
  assert.deepEqual(validateConnectForm({ ...base, qualityMode: "adaptive" }), []);
});

test("profile mode blocks when profile missing or stale", () => {
  assert.match(validateConnectForm({ ...base, qualityMode: "profile" }).join(" "), /Select a trained profile/);
  assert.match(validateConnectForm({ ...base, qualityMode: "profile", profileKey: "missing" }).join(" "), /no longer available/);
  assert.deepEqual(validateConnectForm({ ...base, qualityMode: "profile", profileKey: "p1" }), []);
});

test("wrong codec/backend in profile is rejected", () => {
  assert.match(validateConnectForm({ ...base, qualityMode: "profile", profileKey: "p1", encoder: "h265" }).join(" "), /codec/);
  assert.match(validateConnectForm({ ...base, qualityMode: "profile", profileKey: "p1", captureBackend: "wayland_portal" }).join(" "), /different backend/);
});

test("no backend/codec or invalid manual values cannot connect", () => {
  const errors = validateConnectForm({ ...base, captureBackend: null, encoder: "", manualFps: 1000, manualMbps: 500 });
  assert.equal(errors.length, 4);
});

test("connect dialog invalidates stale profile and doctor requests", () => {
  const app = readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8");
  assert.match(app, /requestId !== profilesRequestId/);
  assert.match(app, /requestId === connectDialogRequestId/);
  assert.match(app, /session\.devices\(\)\.find\(\(candidate\) => candidate\.serial === selectedDevice\.serial\)/);
  assert.match(app, /connectFormErrors\(\)\.length > 0/);
});
