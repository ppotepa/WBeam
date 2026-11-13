import assert from "node:assert/strict";
import test from "node:test";
import { loadTypeScriptModule } from "./app-functions.mjs";

const { parseHud, parseHudSeries } = await loadTypeScriptModule("../src/app-hud.ts");
const trial = (latency, availability = "") =>
  `[t1] score=1.0 present=30.0 recv=30.0 e2e95=${latency} drops/s=0.0 ${availability}`;

test("HUD requires explicit availability before showing a numeric E2E timing", () => {
  assert.equal(parseHud([]).e2eP95Ms, "unavailable");
  assert.equal(parseHud([trial("0.0ms")]).e2eP95Ms, "unavailable");
  assert.equal(parseHud([trial("12.3ms", "e2e_latency_available=false")]).e2eP95Ms, "unavailable");
  assert.equal(parseHud([trial("12.3ms", "e2e_latency_available=true")]).e2eP95Ms, "12.3");
});

test("missing or invalid measurements clear a previously available E2E value", () => {
  const measured = trial("12.3ms", "e2e_latency_available=true");
  for (const value of ["unavailable", "NaNms", "-1ms"]) {
    const hud = parseHud([measured, trial(value, "e2e_latency_available=true")]);
    assert.equal(hud.e2eP95Ms, "unavailable");
    assert.equal(hud.presentFps, "30.0");
  }
  assert.equal(parseHud([measured, trial("0.0ms")]).e2eP95Ms, "unavailable");
  assert.deepEqual(parseHudSeries([trial("unavailable")]).present, [30]);
});

test("protocol timeout statistics are never relabeled as E2E latency", () => {
  assert.equal(parseHud(["done trial=t1 score=1 sender_p50=30 pipe_p50=30 timeout_mean=99 drop=0%"])
    .e2eP95Ms, "unavailable");
  assert.equal(parseHud(["done trial=t1 e2e_latency_available=true e2e_latency_ms_p95=14.2"])
    .e2eP95Ms, "14.2");
});
