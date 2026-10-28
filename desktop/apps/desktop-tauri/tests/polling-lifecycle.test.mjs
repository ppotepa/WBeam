import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";

const source = readFileSync(new URL("../src/managers/pollingLoop.ts", import.meta.url), "utf8");
const transpiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 },
}).outputText;
const { createPollingLoop } = await import(`data:text/javascript;base64,${Buffer.from(transpiled).toString("base64")}`);

function fakeClock() {
  let id = 0;
  const jobs = new Map();
  return {
    jobs,
    clock: {
      schedule(callback) { jobs.set(++id, callback); return id; },
      cancel(handle) { jobs.delete(handle); },
    },
    fire() {
      const [key, callback] = jobs.entries().next().value ?? [];
      if (!callback) throw new Error("nothing scheduled");
      jobs.delete(key);
      callback();
    },
  };
}

test("serialized polling: no request overlap while work is in flight", async () => {
  const fake = fakeClock();
  let resolve;
  let count = 0;
  const pending = new Promise((r) => { resolve = r; });
  const poll = createPollingLoop({
    delayMs: 1200, shouldPoll: () => true,
    work: () => { count++; return pending; },
    onError: (error) => { throw error; }, clock: fake.clock,
  });
  poll.start();
  assert.equal(fake.jobs.size, 1);
  fake.fire();
  assert.equal(count, 1);
  assert.equal(fake.jobs.size, 0);
  resolve();
  await pending;
  await new Promise((r) => setImmediate(r));
  assert.equal(fake.jobs.size, 1);
  poll.stop();
  assert.equal(fake.jobs.size, 0);
});

test("blocked or hidden refresh only reschedules without polling", async () => {
  const fake = fakeClock();
  let visible = false;
  let count = 0;
  const poll = createPollingLoop({
    delayMs: 1200, shouldPoll: () => visible,
    work: async () => { count++; },
    onError: (error) => { throw error; }, clock: fake.clock,
  });
  poll.start();
  fake.fire();
  assert.equal(count, 0);
  assert.equal(fake.jobs.size, 1);
  visible = true;
  fake.fire();
  await new Promise((r) => setImmediate(r));
  assert.equal(count, 1);
  poll.stop();
  assert.equal(fake.jobs.size, 0);
});

test("stop during async work prevents the next schedule", async () => {
  const fake = fakeClock();
  let resolve;
  const pending = new Promise((r) => { resolve = r; });
  const poll = createPollingLoop({
    delayMs: 1200, shouldPoll: () => true,
    work: () => pending, onError: () => {}, clock: fake.clock,
  });
  poll.start();
  fake.fire();
  poll.stop();
  resolve();
  await pending;
  await new Promise((r) => setImmediate(r));
  assert.equal(fake.jobs.size, 0);
});

test("owner cleanup is registered in synchronous onMount", () => {
  const app = readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8");
  assert.match(app, /onMount\(\(\) => \{/);
  assert.doesNotMatch(app, /onMount\(async/);
  assert.match(app, /onCleanup\(\(\) => \{[\s\S]*?poller.stop\(\)/);
  assert.doesNotMatch(app, /setInterval\(/);
});
