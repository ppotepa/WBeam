import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const app = readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8");
const css = readFileSync(new URL("../src/styles.css", import.meta.url), "utf8");
const config = JSON.parse(readFileSync(new URL("../src-tauri/tauri.conf.json", import.meta.url), "utf8"));
const dialog = readFileSync(new URL("../src/components/AccessibleDialog.tsx", import.meta.url), "utf8");

test("Tauri window permits small screen layouts", () => {
  assert.ok(config.app.windows[0].minHeight <= 500);
  assert.ok(config.app.windows[0].minWidth <= 360);
  assert.match(css, /mode-advanced/);
  assert.match(css, /max-height: calc\(100dvh - 2rem\)/);
});

test("all dialogs use modal focus/escape management", () => {
  assert.equal((app.match(/<AccessibleDialog\b/g) || []).length, 4);
  assert.doesNotMatch(app, /<dialog open class="modal-backdrop"/);
  assert.match(dialog, /dialog\.showModal\(\)/);
  assert.match(dialog, /dialog\.addEventListener\("cancel", onCancel\)/);
  assert.match(dialog, /previousFocus\?\.focus\(\)/);
});

test("keyboard focus is visible and animation can be reduced", () => {
  assert.match(css, /:focus-visible/);
  assert.match(css, /prefers-reduced-motion/);
  assert.match(app, /aria-label=\{\x60Connect \$\{device\.model\}/);
});
