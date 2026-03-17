# Sonar Issues Report (wbeam)

- Generated at (UTC): 2026-03-17 08:58:17
- Total issues: 162
- Total files: 39

## Files sorted by number of issues

| File | Issues |
|---|---:|
| `desktop/apps/trainer-tauri/src/App.tsx` | 30 |
| `desktop/apps/desktop-tauri/src/App.tsx` | 17 |
| `android/app/src/main/java/com/wbeam/startup/StartupOverlayModelBuilder.java` | 15 |
| `android/app/src/main/java/com/wbeam/MainActivity.java` | 7 |
| `android/app/src/main/java/com/wbeam/api/StatusPoller.java` | 6 |
| `android/app/src/main/java/com/wbeam/stream/StreamReconnectLoop.java` | 6 |
| `desktop/apps/trainer-tauri/src/app-utils.ts` | 6 |
| `android/app/src/main/java/com/wbeam/hud/ResourceUsageTracker.java` | 5 |
| `android/app/src/main/java/com/wbeam/stream/FramedPngLoop.java` | 5 |
| `android/app/src/main/java/com/wbeam/ui/MainActivitySimpleMenuCoordinator.java` | 5 |
| `android/app/src/main/java/com/wbeam/ui/MainActivityUiBinder.java` | 5 |
| `desktop/apps/trainer-tauri/src/app-hud.ts` | 5 |
| `host/scripts/stream_wayland_portal_h264.py` | 5 |
| `android/app/src/main/java/com/wbeam/api/HostApiClient.java` | 4 |
| `android/app/src/main/java/com/wbeam/hud/TrainerHudOverlayRenderer.java` | 3 |
| `android/app/src/main/java/com/wbeam/hud/TrainerHudShellRenderer.java` | 3 |
| `android/app/src/main/java/com/wbeam/startup/StartupStepStyler.java` | 3 |
| `android/app/src/main/java/com/wbeam/stream/FramedVideoDecodeLoop.java` | 3 |
| `desktop/apps/desktop-tauri/src-tauri/src/main.rs` | 3 |
| `host/rust/crates/wbeamd-core/src/lib.rs` | 3 |
| `android/app/src/main/java/com/wbeam/ui/MainInitializationCoordinator.java` | 2 |
| `host/rust/crates/wbeamd-core/src/infra/x11_real_output.rs` | 2 |
| `host/rust/crates/wbeamd-server/src/server/session_registry.rs` | 2 |
| `host/scripts/probe_host.py` | 2 |
| `android/app/src/main/java/com/wbeam/ClientMetricsSample.java` | 1 |
| `android/app/src/main/java/com/wbeam/api/StatusPollerCallbacksFactory.java` | 1 |
| `android/app/src/main/java/com/wbeam/hud/RuntimeHudTrendComposer.java` | 1 |
| `android/app/src/main/java/com/wbeam/resolver/ClientHelloBuilder.java` | 1 |
| `android/app/src/main/java/com/wbeam/settings/SettingsRepository.java` | 1 |
| `android/app/src/main/java/com/wbeam/stream/StreamNalUtils.java` | 1 |
| `android/app/src/main/java/com/wbeam/stream/StreamSessionController.java` | 1 |
| `desktop/apps/desktop-tauri/src/managers/hostApiManager.ts` | 1 |
| `desktop/apps/trainer-tauri/src/styles.css` | 1 |
| `host/daemon/wbeamd.py` | 1 |
| `host/rust/crates/wbeamd-core/src/domain/policy.rs` | 1 |
| `host/rust/crates/wbeamd-server/src/server/trainer_process.rs` | 1 |
| `host/rust/crates/wbeamd-streamer/src/pipeline/builder.rs` | 1 |
| `host/rust/crates/wbeamd-streamer/src/pipeline/profile.rs` | 1 |
| `host/rust/crates/wbeamd-streamer/src/transport/sender.rs` | 1 |

## Issues by file

### `desktop/apps/trainer-tauri/src/App.tsx` (30 issues)

| Line | Rule | Message |
|---:|---|---|
| 178 | `typescript:S6551` | 'valueAt(liveStatus(), ["base", "state"]) \|\| ""' will use Object's default stringification format ('[object Object]') when stringified. |
| 239 | `typescript:S6551` | 'valueAt(liveStatus(), ["base", "active_config", "profile"]) \|\| ""' will use Object's default stringification format ('[object Object]') when stringified. |
| 319 | `typescript:S6551` | 'prev' will use Object's default stringification format ('[object Object]') when stringified. |
| 320 | `typescript:S6551` | 'nextValue' will use Object's default stringification format ('[object Object]') when stringified. |
| 466 | `typescript:S6551` | 'body.error \|\| "find-optimal failed"' will use Object's default stringification format ('[object Object]') when stringified. |
| 488 | `typescript:S3776` | Refactor this function to reduce its Cognitive Complexity from 36 to the 15 allowed. |
| 560 | `typescript:S6551` | 'body.error \|\| "live start failed"' will use Object's default stringification format ('[object Object]') when stringified. |
| 606 | `typescript:S6551` | 'body.error \|\| "save profile failed"' will use Object's default stringification format ('[object Object]') when stringified. |
| 607 | `typescript:S6551` | 'body.profile_name \|\| name' will use Object's default stringification format ('[object Object]') when stringified. |
| 608 | `typescript:S6551` | 'body.profile_name \|\| name' will use Object's default stringification format ('[object Object]') when stringified. |
| 666 | `typescript:S6551` | 'body.error \|\| "preflight failed"' will use Object's default stringification format ('[object Object]') when stringified. |
| 683 | `typescript:S3358` | Extract this nested ternary operation into an independent statement. |
| 718 | `typescript:S6551` | 'body.error \|\| "start failed"' will use Object's default stringification format ('[object Object]') when stringified. |
| 769 | `typescript:S7764` | Prefer `globalThis` over `window`. |
| 779 | `typescript:S7764` | Prefer `globalThis` over `window`. |
| 1376 | `typescript:S6551` | 'valueAt(liveStatus(), ["base", "state"]) \|\| "idle"' will use Object's default stringification format ('[object Object]') when stringified. |
| 1381 | `typescript:S6551` | 'valueAt(liveStatus(), ["base", "active_config", "size"]) \|\| resolvedLiveSize() \|\| "native"' will use Object's default stringification format ('[object Object]') when stringified. |
| 1383 | `typescript:S6551` | 'valueAt(liveStatus(), ["base", "active_config", "bitrate_kbps"]) \|\| mbpsToKbps(liveTargetMbps())' will use Object's default stringification format ('[object Object]') when stringified. |
| 1407 | `typescript:S6551` | 'valueAt(liveStatus(), ["base", "state"]) \|\| "idle"' will use Object's default stringification format ('[object Object]') when stringified. |
| 1531 | `typescript:S6551` | 'valueAt(liveStatus(), ["base", "active_config", "size"]) \|\| resolvedLiveSize() \|\| "native"' will use Object's default stringification format ('[object Object]') when stringified. |
| 1533 | `typescript:S6551` | 'valueAt(liveStatus(), ["base", "active_config", "bitrate_kbps"]) \|\| mbpsToKbps(liveTargetMbps())' will use Object's default stringification format ('[object Object]') when stringified. |
| 1665 | `typescript:S4624` | Refactor this code to not use nested template literals. |
| 1680 | `typescript:S4624` | Refactor this code to not use nested template literals. |
| 1695 | `typescript:S4624` | Refactor this code to not use nested template literals. |
| 1794 | `typescript:S6551` | 'valueAt(datasetDetail()!.parameters, ["best", "config", "size"]) \|\| "-"' will use Object's default stringification format ('[object Object]') when stringified. |
| 1798 | `typescript:S6551` | 'valueAt(datasetDetail()!.parameters, ["best", "config", "fps"]) \|\| "-"' will use Object's default stringification format ('[object Object]') when stringified. |
| 1851 | `typescript:S4624` | Refactor this code to not use nested template literals. |
| 1869 | `typescript:S4624` | Refactor this code to not use nested template literals. |
| 1887 | `typescript:S4624` | Refactor this code to not use nested template literals. |
| 1905 | `typescript:S4624` | Refactor this code to not use nested template literals. |

### `desktop/apps/desktop-tauri/src/App.tsx` (17 issues)

| Line | Rule | Message |
|---:|---|---|
| 23 | `typescript:S3863` | './types' imported multiple times. |
| 24 | `typescript:S3863` | './types' imported multiple times. |
| 32 | `typescript:S6759` | Mark the props of the component as read-only. |
| 155 | `typescript:S6594` | Use the "RegExp.exec()" method instead. |
| 390 | `typescript:S7764` | Prefer `globalThis` over `window`. |
| 449 | `typescript:S7764` | Prefer `globalThis` over `window`. |
| 484 | `typescript:S7764` | Prefer `globalThis` over `window`. |
| 501 | `typescript:S7764` | Prefer `globalThis` over `window`. |
| 690 | `typescript:S6853` | A form label must have accessible text. |
| 749 | `typescript:S3358` | Extract this nested ternary operation into an independent statement. |
| 754 | `typescript:S3358` | Extract this nested ternary operation into an independent statement. |
| 810 | `typescript:S6853` | A form label must have accessible text. |
| 810 | `typescript:S7735` | Unexpected negated condition. |
| 825 | `typescript:S6853` | A form label must have accessible text. |
| 938 | `typescript:S3358` | Extract this nested ternary operation into an independent statement. |
| 942 | `typescript:S3358` | Extract this nested ternary operation into an independent statement. |
| 945 | `typescript:S3358` | Extract this nested ternary operation into an independent statement. |

### `android/app/src/main/java/com/wbeam/startup/StartupOverlayModelBuilder.java` (15 issues)

| Line | Rule | Message |
|---:|---|---|
| 58 | `java:S1104` | Make step2State a static final constant or non-public and provide accessors if needed. |
| 59 | `java:S1104` | Make step3State a static final constant or non-public and provide accessors if needed. |
| 60 | `java:S1104` | Make step1Detail a static final constant or non-public and provide accessors if needed. |
| 61 | `java:S1104` | Make step2Detail a static final constant or non-public and provide accessors if needed. |
| 62 | `java:S1104` | Make step3Detail a static final constant or non-public and provide accessors if needed. |
| 63 | `java:S1104` | Make subtitle a static final constant or non-public and provide accessors if needed. |
| 64 | `java:S1104` | Make infoLog a static final constant or non-public and provide accessors if needed. |
| 65 | `java:S1104` | Make allOk a static final constant or non-public and provide accessors if needed. |
| 66 | `java:S1104` | Make updatedStartupBeganAtMs a static final constant or non-public and provide accessors if needed. |
| 67 | `java:S1104` | Make updatedControlRetryCount a static final constant or non-public and provide accessors if needed. |
| 68 | `java:S1104` | Make elapsedMs a static final constant or non-public and provide accessors if needed. |
| 73 | `java:S3776` | Refactor this method to reduce its Cognitive Complexity from 81 to the 15 allowed. |
| 73 | `java:S6541` | A "Brain Method" was detected. Refactor it to reduce at least one of the following metrics: LOC from 166 to 64, Complexity from 48 to 14, Nesting Level from 3 to 2, Number of Variables from 21 to 6. |
| 183 | `java:S1192` | Define a constant instead of duplicating this literal "attempt #" 3 times. |
| 214 | `java:S3358` | Extract this nested ternary operation into an independent statement. |

### `android/app/src/main/java/com/wbeam/MainActivity.java` (7 issues)

| Line | Rule | Message |
|---:|---|---|
| 95 | `java:S1068` | Remove this unused "LIVE_TEST_START_TIMEOUT_MS" private field. |
| 99 | `java:S1068` | Remove this unused "BANDWIDTH_TEST_MB" private field. |
| 100 | `java:S1068` | Remove this unused "TEST_VIDEO_URL" private field. |
| 187 | `java:S1068` | Remove this unused "applySettingsButton" private field. |
| 187 | `java:S1450` | Remove the "applySettingsButton" field and declare it as a local variable in the relevant methods. |
| 218 | `java:S1068` | Remove this unused "hwAvcDecodeAvailable" private field. |
| 218 | `java:S1450` | Remove the "hwAvcDecodeAvailable" field and declare it as a local variable in the relevant methods. |

### `android/app/src/main/java/com/wbeam/api/StatusPoller.java` (6 issues)

| Line | Rule | Message |
|---:|---|---|
| 67 | `java:S107` | Method has 11 parameters, which is greater than 7 authorized. |
| 148 | `java:S3398` | Move this method into the anonymous class declared at line 55. |
| 285 | `java:S1192` | Define a constant instead of duplicating this literal "trainer_hud_active" 3 times. |
| 288 | `java:S1192` | Define a constant instead of duplicating this literal "trainer_hud_text" 3 times. |
| 291 | `java:S1192` | Define a constant instead of duplicating this literal "trainer_hud_json" 3 times. |
| 294 | `java:S1192` | Define a constant instead of duplicating this literal "connection_mode" 3 times. |

### `android/app/src/main/java/com/wbeam/stream/StreamReconnectLoop.java` (6 issues)

| Line | Rule | Message |
|---:|---|---|
| 31 | `java:S112` | Replace generic exceptions with specific library exceptions or a custom exception. |
| 46 | `java:S107` | Constructor has 10 parameters, which is greater than 7 authorized. |
| 71 | `java:S3776` | Refactor this method to reduce its Cognitive Complexity from 24 to the 15 allowed. |
| 76 | `java:S2093` | Change this "try" to a try-with-resources. (sonar.java.source not set. Assuming 7 or greater.) |
| 117 | `java:S1181` | Catch Exception instead of Error. |
| 140 | `java:S2140` | Use "java.util.Random.nextLong()" instead. |

### `desktop/apps/trainer-tauri/src/app-utils.ts` (6 issues)

| Line | Rule | Message |
|---:|---|---|
| 24 | `typescript:S3358` | Extract this nested ternary operation into an independent statement. |
| 26 | `typescript:S3358` | Extract this nested ternary operation into an independent statement. |
| 100 | `typescript:S6551` | 'value' will use Object's default stringification format ('[object Object]') when stringified. |
| 110 | `typescript:S3776` | Refactor this function to reduce its Cognitive Complexity from 16 to the 15 allowed. |
| 117 | `typescript:S6551` | 'row.trial_id \|\| ""' will use Object's default stringification format ('[object Object]') when stringified. |
| 124 | `typescript:S6551` | 'row.notes \|\| "-"' will use Object's default stringification format ('[object Object]') when stringified. |

### `android/app/src/main/java/com/wbeam/hud/ResourceUsageTracker.java` (5 issues)

| Line | Rule | Message |
|---:|---|---|
| 61 | `java:S1192` | Define a constant instead of duplicating this literal "state-risk" 3 times. |
| 63 | `java:S1192` | Define a constant instead of duplicating this literal "state-warn" 3 times. |
| 65 | `java:S1192` | Define a constant instead of duplicating this literal "state-ok" 3 times. |
| 91 | `java:S1192` | Define a constant instead of duplicating this literal "</span><div class='spark'>" 3 times. |
| 93 | `java:S1192` | Define a constant instead of duplicating this literal "</div></div>" 3 times. |

### `android/app/src/main/java/com/wbeam/stream/FramedPngLoop.java` (5 issues)

| Line | Rule | Message |
|---:|---|---|
| 43 | `java:S107` | Constructor has 14 parameters, which is greater than 7 authorized. |
| 77 | `java:S3776` | Refactor this method to reduce its Cognitive Complexity from 47 to the 15 allowed. |
| 77 | `java:S6541` | A "Brain Method" was detected. Refactor it to reduce at least one of the following metrics: LOC from 179 to 64, Complexity from 23 to 14, Nesting Level from 3 to 2, Number of Variables from 45 to 6. |
| 100 | `java:S135` | Reduce the total number of break and continue statements in this loop to use at most one. |
| 124 | `java:S1854` | Remove this useless assignment to local variable "expectedSeq". |

### `android/app/src/main/java/com/wbeam/ui/MainActivitySimpleMenuCoordinator.java` (5 issues)

| Line | Rule | Message |
|---:|---|---|
| 11 | `java:S1104` | Make visible a static final constant or non-public and provide accessors if needed. |
| 12 | `java:S1104` | Make mode a static final constant or non-public and provide accessors if needed. |
| 13 | `java:S1104` | Make fps a static final constant or non-public and provide accessors if needed. |
| 68 | `java:S107` | Method has 9 parameters, which is greater than 7 authorized. |
| 127 | `java:S107` | Method has 12 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/ui/MainActivityUiBinder.java` (5 issues)

| Line | Rule | Message |
|---:|---|---|
| 63 | `java:S107` | Method has 9 parameters, which is greater than 7 authorized. |
| 91 | `java:S1186` | Add a nested comment explaining why this method is empty, throw an UnsupportedOperationException or complete the implementation. |
| 101 | `java:S1186` | Add a nested comment explaining why this method is empty, throw an UnsupportedOperationException or complete the implementation. |
| 126 | `java:S1186` | Add a nested comment explaining why this method is empty, throw an UnsupportedOperationException or complete the implementation. |
| 130 | `java:S1186` | Add a nested comment explaining why this method is empty, throw an UnsupportedOperationException or complete the implementation. |

### `desktop/apps/trainer-tauri/src/app-hud.ts` (5 issues)

| Line | Rule | Message |
|---:|---|---|
| 3 | `typescript:S3776` | Refactor this function to reduce its Cognitive Complexity from 21 to the 15 allowed. |
| 21 | `typescript:S5843` | Simplify this regular expression to reduce its complexity from 22 to the 20 allowed. |
| 152 | `typescript:S6353` | Use concise character class syntax '\d' instead of '[0-9]'. |
| 152 | `typescript:S6353` | Use concise character class syntax '\d' instead of '[0-9]'. |
| 155 | `typescript:S6594` | Use the "RegExp.exec()" method instead. |

### `host/scripts/stream_wayland_portal_h264.py` (5 issues)

| Line | Rule | Message |
|---:|---|---|
| 332 | `python:S3776` | Refactor this function to reduce its Cognitive Complexity from 88 to the 15 allowed. |
| 501 | `python:S5713` | Remove this redundant Exception class; it derives from another which is already caught. |
| 501 | `python:S5713` | Remove this redundant Exception class; it derives from another which is already caught. |
| 575 | `python:S3776` | Refactor this function to reduce its Cognitive Complexity from 44 to the 15 allowed. |
| 1098 | `python:S3776` | Refactor this function to reduce its Cognitive Complexity from 17 to the 15 allowed. |

### `android/app/src/main/java/com/wbeam/api/HostApiClient.java` (4 issues)

| Line | Rule | Message |
|---:|---|---|
| 127 | `java:S2140` | Use "java.util.Random.nextLong()" instead. |
| 202 | `java:S1905` | Remove this unnecessary cast to "long". |
| 233 | `java:S3776` | Refactor this method to reduce its Cognitive Complexity from 16 to the 15 allowed. |
| 305 | `java:S1192` | Define a constant instead of duplicating this literal "RECONNECTING" 3 times. |

### `android/app/src/main/java/com/wbeam/hud/TrainerHudOverlayRenderer.java` (3 issues)

| Line | Rule | Message |
|---:|---|---|
| 50 | `java:S1612` | Replace this lambda with method reference 'resourceRowsProvider::buildRows'. (sonar.java.source not set. Assuming 8 or greater.) |
| 95 | `java:S1192` | Define a constant instead of duplicating this literal "PENDING" 5 times. |
| 119 | `java:S1192` | Define a constant instead of duplicating this literal "pending" 6 times. |

### `android/app/src/main/java/com/wbeam/hud/TrainerHudShellRenderer.java` (3 issues)

| Line | Rule | Message |
|---:|---|---|
| 14 | `java:S107` | Method has 43 parameters, which is greater than 7 authorized. |
| 73 | `java:S1192` | Define a constant instead of duplicating this literal "PENDING" 12 times. |
| 201 | `java:S1192` | Define a constant instead of duplicating this literal "scale-2x" 3 times. |

### `android/app/src/main/java/com/wbeam/startup/StartupStepStyler.java` (3 issues)

| Line | Rule | Message |
|---:|---|---|
| 15 | `java:S107` | Method has 12 parameters, which is greater than 7 authorized. |
| 52 | `java:S1192` | Define a constant instead of duplicating this literal "#FCA5A5" 4 times. |
| 70 | `java:S1192` | Define a constant instead of duplicating this literal "#64748B" 3 times. |

### `android/app/src/main/java/com/wbeam/stream/FramedVideoDecodeLoop.java` (3 issues)

| Line | Rule | Message |
|---:|---|---|
| 178 | `java:S3358` | Extract this nested ternary operation into an independent statement. |
| 258 | `java:S1854` | Remove this useless assignment to local variable "expectedSeq". |
| 341 | `java:S1192` | Use already-defined constant 'PAYLOAD_LABEL' instead of duplicating its value here. |

### `desktop/apps/desktop-tauri/src-tauri/src/main.rs` (3 issues)

| Line | Rule | Message |
|---:|---|---|
| 804 | `rust:S3776` | Refactor this function to reduce its Cognitive Complexity from 26 to the 15 allowed. |
| 1217 | `rust:S3776` | Refactor this function to reduce its Cognitive Complexity from 17 to the 15 allowed. |
| 1572 | `rust:S3776` | Refactor this function to reduce its Cognitive Complexity from 24 to the 15 allowed. |

### `host/rust/crates/wbeamd-core/src/lib.rs` (3 issues)

| Line | Rule | Message |
|---:|---|---|
| 954 | `rust:S3776` | Refactor this function to reduce its Cognitive Complexity from 48 to the 15 allowed. |
| 1363 | `rust:S3776` | Refactor this function to reduce its Cognitive Complexity from 67 to the 15 allowed. |
| 1761 | `rust:S3776` | Refactor this function to reduce its Cognitive Complexity from 18 to the 15 allowed. |

### `android/app/src/main/java/com/wbeam/ui/MainInitializationCoordinator.java` (2 issues)

| Line | Rule | Message |
|---:|---|---|
| 38 | `java:S107` | Method has 21 parameters, which is greater than 7 authorized. |
| 110 | `java:S107` | Method has 10 parameters, which is greater than 7 authorized. |

### `host/rust/crates/wbeamd-core/src/infra/x11_real_output.rs` (2 issues)

| Line | Rule | Message |
|---:|---|---|
| 62 | `rust:S3776` | Refactor this function to reduce its Cognitive Complexity from 18 to the 15 allowed. |
| 647 | `rust:S3776` | Refactor this function to reduce its Cognitive Complexity from 24 to the 15 allowed. |

### `host/rust/crates/wbeamd-server/src/server/session_registry.rs` (2 issues)

| Line | Rule | Message |
|---:|---|---|
| 53 | `rust:S3776` | Refactor this function to reduce its Cognitive Complexity from 21 to the 15 allowed. |
| 150 | `rust:S3776` | Refactor this function to reduce its Cognitive Complexity from 22 to the 15 allowed. |

### `host/scripts/probe_host.py` (2 issues)

| Line | Rule | Message |
|---:|---|---|
| 122 | `python:S3776` | Refactor this function to reduce its Cognitive Complexity from 53 to the 15 allowed. |
| 353 | `python:S1192` | Define a constant instead of duplicating this literal "powershell.exe" 4 times. |

### `android/app/src/main/java/com/wbeam/ClientMetricsSample.java` (1 issues)

| Line | Rule | Message |
|---:|---|---|
| 29 | `java:S107` | Constructor has 16 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/api/StatusPollerCallbacksFactory.java` (1 issues)

| Line | Rule | Message |
|---:|---|---|
| 8 | `java:S107` | Method has 11 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/hud/RuntimeHudTrendComposer.java` (1 issues)

| Line | Rule | Message |
|---:|---|---|
| 28 | `java:S1905` | Remove this unnecessary cast to "double". |

### `android/app/src/main/java/com/wbeam/resolver/ClientHelloBuilder.java` (1 issues)

| Line | Rule | Message |
|---:|---|---|
| 19 | `java:S1192` | Define a constant instead of duplicating this literal "unknown" 3 times. |

### `android/app/src/main/java/com/wbeam/settings/SettingsRepository.java` (1 issues)

| Line | Rule | Message |
|---:|---|---|
| 101 | `java:S107` | Constructor has 8 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/stream/StreamNalUtils.java` (1 issues)

| Line | Rule | Message |
|---:|---|---|
| 27 | `java:S135` | Reduce the total number of break and continue statements in this loop to use at most one. |

### `android/app/src/main/java/com/wbeam/stream/StreamSessionController.java` (1 issues)

| Line | Rule | Message |
|---:|---|---|
| 22 | `java:S1068` | Remove this unused "TAG" private field. |

### `desktop/apps/desktop-tauri/src/managers/hostApiManager.ts` (1 issues)

| Line | Rule | Message |
|---:|---|---|
| 13 | `typescript:S6551` | 'err ?? "unknown error"' will use Object's default stringification format ('[object Object]') when stringified. |

### `desktop/apps/trainer-tauri/src/styles.css` (1 issues)

| Line | Rule | Message |
|---:|---|---|
| 241 | `css:S7924` | Text does not meet the minimal contrast requirement with its background. |

### `host/daemon/wbeamd.py` (1 issues)

| Line | Rule | Message |
|---:|---|---|
| 407 | `python:S5890` | Replace the type hint "WBeamDaemon" with "Optional[WBeamDaemon]" or don't assign "None" to "daemon_ref" |

### `host/rust/crates/wbeamd-core/src/domain/policy.rs` (1 issues)

| Line | Rule | Message |
|---:|---|---|
| 108 | `rust:S3776` | Refactor this function to reduce its Cognitive Complexity from 21 to the 15 allowed. |

### `host/rust/crates/wbeamd-server/src/server/trainer_process.rs` (1 issues)

| Line | Rule | Message |
|---:|---|---|
| 36 | `rust:S3776` | Refactor this function to reduce its Cognitive Complexity from 22 to the 15 allowed. |

### `host/rust/crates/wbeamd-streamer/src/pipeline/builder.rs` (1 issues)

| Line | Rule | Message |
|---:|---|---|
| 27 | `rust:S3776` | Refactor this function to reduce its Cognitive Complexity from 76 to the 15 allowed. |

### `host/rust/crates/wbeamd-streamer/src/pipeline/profile.rs` (1 issues)

| Line | Rule | Message |
|---:|---|---|
| 15 | `rust:S3776` | Refactor this function to reduce its Cognitive Complexity from 19 to the 15 allowed. |

### `host/rust/crates/wbeamd-streamer/src/transport/sender.rs` (1 issues)

| Line | Rule | Message |
|---:|---|---|
| 56 | `rust:S3776` | Refactor this function to reduce its Cognitive Complexity from 162 to the 15 allowed. |

## Rule summary

| Rule | Count |
|---|---:|
| `typescript:S6551` | 23 |
| `java:S1192` | 19 |
| `rust:S3776` | 15 |
| `java:S1104` | 14 |
| `java:S107` | 13 |
| `typescript:S3358` | 8 |
| `typescript:S4624` | 7 |
| `java:S1068` | 6 |
| `typescript:S7764` | 6 |
| `java:S1186` | 4 |
| `java:S3776` | 4 |
| `python:S3776` | 4 |
| `typescript:S3776` | 3 |
| `typescript:S6853` | 3 |
| `java:S135` | 2 |
| `java:S1450` | 2 |
| `java:S1854` | 2 |
| `java:S1905` | 2 |
| `java:S2140` | 2 |
| `java:S3358` | 2 |
| `java:S6541` | 2 |
| `python:S5713` | 2 |
| `typescript:S3863` | 2 |
| `typescript:S6353` | 2 |
| `typescript:S6594` | 2 |
| `css:S7924` | 1 |
| `java:S112` | 1 |
| `java:S1181` | 1 |
| `java:S1612` | 1 |
| `java:S2093` | 1 |
| `java:S3398` | 1 |
| `python:S1192` | 1 |
| `python:S5890` | 1 |
| `typescript:S5843` | 1 |
| `typescript:S6759` | 1 |
| `typescript:S7735` | 1 |
