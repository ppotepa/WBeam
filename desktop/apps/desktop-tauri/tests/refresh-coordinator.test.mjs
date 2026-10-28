import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";

const source = readFileSync(new URL("../src/managers/refreshCoordinator.ts", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 },
}).outputText;
const { createRefreshCoordinator } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString("base64")}`);

function deferred() {
  let release;
  const promise = new Promise((resolve) => { release = resolve; });
  return { promise, release };
}

test("manual request follows an active silent poll and exposes progress", async () => {
  const held = deferred();
  const runs = [];
  const states = [];
  const queue = createRefreshCoordinator(async (request) => {
    runs.push(request);
    if (runs.length === 1) await held.promise;
  }, (busy, interactive) => states.push({ busy, interactive }));
  const first = queue.refresh({ silent: true, forceDevices: false });
  const manual = queue.refresh({ silent: false, forceDevices: true });
  assert.equal(first, manual);
  assert.deepEqual(states.at(-1), { busy: true, interactive: true });
  held.release();
  await manual;
  assert.deepEqual(runs, [
    { silent: true, forceDevices: false },
    { silent: false, forceDevices: true },
  ]);
  assert.deepEqual(states.at(-1), { busy: false, interactive: false });
});

test("multiple focus refreshes during background poll share one follow-up", async () => {
  const held = deferred();
  const runs = [];
  const queue = createRefreshCoordinator(async (request) => {
    runs.push(request);
    if (runs.length === 1) await held.promise;
  }, () => {});
  const first = queue.refresh({ silent: true, forceDevices: false });
  queue.refresh({ silent: true, forceDevices: true });
  queue.refresh({ silent: true, forceDevices: true });
  held.release();
  await first;
  assert.equal(runs.length, 2);
  assert.equal(runs[1].forceDevices, true);
});

test("duplicate visibility events do not repeat an already-forced poll", async () => {
  const held = deferred();
  const runs = [];
  const queue = createRefreshCoordinator(async (request) => {
    runs.push(request);
    await held.promise;
  }, () => {});
  const first = queue.refresh({ silent: true, forceDevices: true });
  queue.refresh({ silent: true, forceDevices: true });
  held.release();
  await first;
  assert.equal(runs.length, 1);
});

test("silent scheduled ticks cannot pile up while another poll is in flight", async () => {
  const held = deferred();
  let count = 0;
  const queue = createRefreshCoordinator(async () => { count++; await held.promise; }, () => {});
  const first = queue.refresh({ silent: true, forceDevices: false });
  queue.refresh({ silent: true, forceDevices: false });
  held.release();
  await first;
  assert.equal(count, 1);
});

test("failed poll restores idle state and allows retry", async () => {
  const states = [];
  let count = 0;
  const queue = createRefreshCoordinator(async () => {
    count++;
    if (count === 1) throw new Error("offline");
  }, (busy, interactive) => states.push([busy, interactive]));
  await assert.rejects(queue.refresh({ silent: true, forceDevices: true }), /offline/);
  await queue.refresh({ silent: false, forceDevices: true });
  assert.equal(count, 2);
  assert.deepEqual(states.at(-1), [false, false]);
});
