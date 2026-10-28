import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (p) => readFileSync(new URL(p, import.meta.url), "utf8");
const tauri = JSON.parse(read("../src-tauri/tauri.conf.json"));
const main = read("../src-tauri/src/main.rs");
const adb = read("../src-tauri/src/adb.rs");
const doctor = read("../src-tauri/src/virtual_monitor.rs");
const service = read("../src-tauri/src/service.rs");
const capabilities = JSON.parse(read("../src-tauri/capabilities/default.json"));

test("release CSP is explicit and permits only Tauri IPC", () => {
  const csp = tauri.app.security.csp;
  assert.notEqual(csp, null);
  assert.equal(csp["default-src"], "'self'");
  assert.equal(csp["connect-src"], "ipc: http://ipc.localhost");
  assert.doesNotMatch(csp["script-src"], /unsafe-eval|unsafe-inline/);
  assert.equal(csp["object-src"], "'none'");
  assert.equal(tauri.app.security.devCsp["connect-src"].includes("ws://localhost:1420"), true);
});

test("Tauri capabilities are restricted to the main window", () => {
  assert.deepEqual(capabilities.windows, ["main"]);
  assert.ok(capabilities.permissions.includes("core:default"));
  assert.equal(capabilities.permissions.some((p) => /fs:allow|shell:allow|http:default/.test(p)), false);
});

test("serials are URL-encoded before building daemon queries", () => {
  assert.match(main, /fn encode_query_component\(raw: &str\)/);
  assert.match(main, /let serial_param = encode_query_component\(serial\);/);
  assert.match(main, /let serial_param = encode_query_component\(&serial\);/);
  assert.match(adb, /crate::encode_query_component\(serial\)/);
  assert.match(doctor, /crate::encode_query_component\(trimmed\)/);
  assert.doesNotMatch(main, /\/v1\/stop\?serial=\{serial\}/);
});

test("system command detection never interpolates a shell parameter", () => {
  assert.match(service, /command -v \\"\$1\\"/);
  assert.doesNotMatch(service, /format!\("command -v \{name\}/);
});

test("profile storage validates the backend before touching the filesystem", () => {
  const profiles = read("../src-tauri/src/profiles.rs");
  assert.match(profiles, /fn validated_backend_component\(raw: &str\) -> Result<String, String>/);
  assert.match(profiles, /normalized\.bytes\(\)\.all\(/);
  const pathFn = profiles.slice(
    profiles.indexOf("pub(crate) fn trained_profiles_path("),
    profiles.indexOf("pub(crate) fn migrate_legacy_profiles_once()"),
  );
  const validated = pathFn.indexOf("validated_backend_component(backend)?");
  const mkdir = pathFn.indexOf("fs::create_dir_all");
  assert.ok(validated >= 0 && mkdir > validated);
  assert.doesNotMatch(pathFn, /\.join\(normalize_backend\(backend\)\)/);
  assert.match(profiles, /fn backend_path_traversal_and_separators_are_rejected\(/);
});
