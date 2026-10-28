import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

function read(path) {
  return readFileSync(new URL(path, import.meta.url), "utf8");
}

const ui = read("../src/App.tsx");
const hostProbe = read("../../../../host/rust/crates/wbeamd-core/src/infra/host_probe.rs");
const gitlab = read("../../../../.gitlab-ci.yml");

test("EVDI capability checks real DRM cardN nodes and not nonexistent evdiN", () => {
  assert.match(hostProbe, /evdi_card_exists\(Path::new\("\/sys\/bus\/platform\/devices"\)/);
  assert.match(hostProbe, /dev_dri\.join\(name\.as_ref\(\)\)\.exists\(\)/);
  assert.match(hostProbe, /name\.len\(\) > 4/);
  assert.doesNotMatch(hostProbe, /starts_with\("evdi"\).*return true;/);
  assert.doesNotMatch(hostProbe, /\|\| Path::new\("\/sys\/devices\/evdi\/add"\)\.exists\(\)/);
});

test("Connect modal rechecks current device and selected concrete backend", () => {
  assert.match(ui, /const connectDialogBlockReason = \(\) => \{/);
  assert.match(ui, /session\.devices\(\)\.find\(\(device\) => device\.serial === selected\.serial\)/);
  assert.match(ui, /return connectDisabledReason\(current, selectedCaptureBackend\(\)\)/);
  assert.match(ui, /waylandPortalHost: backend === "wayland_portal"/);
  assert.doesNotMatch(ui, /setConnectDialogBlockReason/);
});

test("Autoconnect capacity counts only eligible devices", () => {
  assert.match(ui, /decideAutoConnectBackend\(session\.hostProbe\(\), eligible\.length\)/);
  assert.match(ui, /decideAutoConnectBackend\(session\.hostProbe\(\), eligibleCount\)/);
  assert.match(ui, /const reason = connectDisabledReason\(device, backend\)/);
});

test("GitLab master fast-forward refuses a release-branch SHA mismatch", () => {
  assert.match(gitlab, /WBEAM_MASTER_FF_APPROVED/);
  assert.match(gitlab, /if \[ "\$RELEASE_SHA" != "\$CI_COMMIT_SHA" \]/);
  assert.match(gitlab, /git merge --ff-only "\$CI_COMMIT_SHA"/);
  assert.doesNotMatch(gitlab, /git merge --ff-only origin\/release/);
});

test("signed APK CI job explicitly waits for every release quality gate", () => {
  const section = gitlab.split("build_apk_release:")[1].split("build_windows_server:")[0];
  for (const job of [
    "quality_gates", "quality_frontend", "quality_rust_host",
    "quality_tauri_native", "quality_android_unit", "quality_e2e_runner",
  ]) {
    assert.match(section, new RegExp(`job: ${job}\\\\b`));
  }
  assert.match(gitlab, /publish_github_release:[\\s\\S]*?job: build_apk_release/);
});
