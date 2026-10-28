import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(path, import.meta.url), "utf8");
const probe = read("../../../../host/rust/crates/wbeamd-core/src/infra/host_probe.rs");
const core = read("../../../../host/rust/crates/wbeamd-core/src/lib.rs");
const cli = read("../../../../host/rust/crates/wbeamd-streamer/src/cli.rs");
const capture = read("../../../../host/rust/crates/wbeamd-streamer/src/capture/mod.rs");
const app = read("../src/App.tsx");

test("X11 session never selects or advertises legacy capture in host policy", () => {
  const resolver = probe.slice(probe.indexOf("impl HostBackendResolver for DefaultHostBackendResolver"), probe.indexOf("#[derive(Debug, Clone, PartialEq, Eq)]\npub struct HostProbe"));
  assert.doesNotMatch(resolver, /SessionKind::X11\) => CaptureMode::X11Gst/);
  assert.match(resolver, /SessionKind::Wayland\) => CaptureMode::WaylandPortal/);
  assert.match(probe, /fn release_backends\(default: CaptureMode, evdi_available: bool\)/);
  assert.match(probe, /if default == CaptureMode::WaylandPortal/);
  assert.match(probe, /fn legacy_resolver_value_is_not_release_advertised/);
});

test("daemon validates selected supported backend before side effects", () => {
  assert.match(core, /!matches!\(capture_backend, "evdi" \| "wayland_portal"\)/);
  assert.match(core, /self\.host_probe\.supports_capture_backend\(capture_backend\)/);
  const start = core.slice(core.indexOf("async fn start_with_config("), core.indexOf("async fn start_with_config(") + 1350);
  assert.ok(start.indexOf("self.ensure_start_supported(") < start.indexOf("self.touch_reverse_refresh().await"));
  assert.match(core, /benchmark_mode \|\| capture_backend == "evdi"/);
});

test("streamer Auto does not fall back to DISPLAY/X11 and CLI hides legacy option", () => {
  assert.match(cli, /#\[value\(hide = true\)\]\s*X11/);
  assert.match(cli, /fn resolve_release_capture_backend\(/);
  assert.match(cli, /AUTO cannot capture an X11 session/);
  assert.doesNotMatch(cli, /std::env::var_os\("DISPLAY"\)\.is_some\(\)/);
  assert.doesNotMatch(capture, /Ok\(PreparedCapture::X11\)/);
  assert.match(capture, /unresolved AUTO capture backend/);
  // The original variant and implementation remain in source for archival
  // compatibility, but are unreachable through supported release entrypoints.
  assert.match(capture, /PreparedCapture::X11 => backend::x11::build_source\(\)/);
});

test("Connect UI asks the EVDI-only doctor and Auto resolves to profile namespace", () => {
  assert.match(app, /api\.getEvdiDepsDoctor\(\)/);
  assert.doesNotMatch(app, /api\.getVirtualDoctor\(/);
  assert.match(app, /const concreteBackendChosen = \(\) => selectedCaptureBackend\(\) !== null/);
  assert.doesNotMatch(app, /Select a specific backend \(not Auto\) to use profiles/);
  assert.match(app, /void refreshConnectDialogDoctor\(\)/);
});
