# Sonar Issues Report (live export)

- Generated at (UTC): 2026-03-17 09:17:42
- Total issues: 749
- Total files: 112

## Files sorted by number of issues

| File | Issues |
|---|---:|
| `android/app/src/main/java/com/wbeam/startup/StartupOverlayModelBuilder.java` | 42 |
| `android/app/src/main/java/com/wbeam/hud/MainHudCoordinator.java` | 39 |
| `android/app/src/main/java/com/wbeam/hud/RuntimeHudWebPayloadBuilder.java` | 37 |
| `android/app/src/main/java/com/wbeam/hud/RuntimeHudOverlayRenderer.java` | 36 |
| `android/app/src/main/java/com/wbeam/startup/MainStartupCoordinator.java` | 35 |
| `android/app/src/main/java/com/wbeam/ui/MainDaemonRuntimeCoordinator.java` | 35 |
| `android/app/src/main/java/com/wbeam/ui/MainActivityControlViewsBinder.java` | 33 |
| `android/app/src/main/java/com/wbeam/telemetry/RuntimeTelemetryMapper.java` | 29 |
| `android/app/src/main/java/com/wbeam/startup/StartupOverlayInputFactory.java` | 27 |
| `android/app/src/main/java/com/wbeam/hud/RuntimeHudRenderCoordinator.java` | 25 |
| `android/app/src/main/java/com/wbeam/hud/RuntimeHudStateCoordinator.java` | 22 |
| `android/app/src/main/java/com/wbeam/startup/StartupOverlayViewRenderer.java` | 20 |
| `android/app/src/main/java/com/wbeam/ui/MainActivityPrimaryViewsBinder.java` | 20 |
| `desktop/apps/desktop-tauri/src/App.tsx` | 19 |
| `android/app/src/main/java/com/wbeam/startup/StartupStepStyler.java` | 13 |
| `android/app/src/main/java/com/wbeam/stream/VideoTestController.java` | 13 |
| `android/app/src/main/java/com/wbeam/ui/MainActivityStatusTracker.java` | 13 |
| `android/app/src/main/java/com/wbeam/ui/MainActivityDaemonStatusCoordinator.java` | 12 |
| `android/app/src/main/java/com/wbeam/MainActivity.java` | 11 |
| `android/app/src/main/java/com/wbeam/hud/HudRenderSupport.java` | 10 |
| `android/app/src/main/java/com/wbeam/hud/MainHudState.java` | 10 |
| `android/app/src/main/java/com/wbeam/ui/state/MainUiState.java` | 10 |
| `android/app/src/main/java/com/wbeam/settings/SettingsRepository.java` | 9 |
| `android/app/src/main/java/com/wbeam/ui/state/MainDaemonState.java` | 9 |
| `android/app/src/main/java/com/wbeam/stream/LegacyAnnexBDecodeLoop.java` | 8 |
| `android/app/src/main/java/com/wbeam/ui/MainViewBindingCoordinator.java` | 8 |
| `android/app/src/main/java/com/wbeam/api/HostApiClient.java` | 7 |
| `android/app/src/main/java/com/wbeam/api/StatusPoller.java` | 7 |
| `android/app/src/main/java/com/wbeam/hud/RuntimeHudShellRenderer.java` | 7 |
| `android/app/src/main/java/com/wbeam/ui/MainActivityInteractionPolicy.java` | 7 |
| `host/scripts/stream_wayland_portal_h264.py` | 7 |
| `android/app/src/main/java/com/wbeam/hud/RuntimeHudComputation.java` | 6 |
| `android/app/src/main/java/com/wbeam/stream/FramedVideoDecodeLoop.java` | 6 |
| `android/app/src/main/java/com/wbeam/ui/MainActivitySurfaceSetup.java` | 6 |
| `android/app/src/main/java/com/wbeam/ui/state/MainStatusState.java` | 6 |
| `host/rust/crates/wbeamd-core/src/lib.rs` | 6 |
| `android/app/src/main/java/com/wbeam/hud/ResourceUsageTracker.java` | 5 |
| `android/app/src/main/java/com/wbeam/ui/MainActivityUiBinder.java` | 5 |
| `android/app/src/main/java/com/wbeam/ui/MainInitializationCoordinator.java` | 5 |
| `android/app/src/main/java/com/wbeam/ui/MainUiControlsCoordinator.java` | 5 |
| `host/rust/crates/wbeamd-tuner/src/main.rs` | 5 |
| `android/app/src/main/java/com/wbeam/startup/StartupOverlayCoordinator.java` | 4 |
| `android/app/src/main/java/com/wbeam/startup/StartupOverlayStateSync.java` | 4 |
| `android/app/src/main/java/com/wbeam/ui/MainActivitySettingsPresenter.java` | 4 |
| `desktop/apps/desktop-tauri/src-tauri/src/main.rs` | 4 |
| `host/rust/crates/wbeamd-core/src/infra/x11_real_output.rs` | 4 |
| `host/scripts/probe_host.py` | 4 |
| `android/app/src/main/java/com/wbeam/stream/H264TcpPlayer.java` | 3 |
| `android/app/src/main/java/com/wbeam/stream/StreamNalUtils.java` | 3 |
| `android/app/src/main/java/com/wbeam/ui/MainDaemonRuntimeInputFactory.java` | 3 |
| `android/app/src/main/java/com/wbeam/hud/HudOverlayDisplay.java` | 2 |
| `android/app/src/main/java/com/wbeam/hud/RuntimeHudFallbackFormatter.java` | 2 |
| `android/app/src/main/java/com/wbeam/hud/RuntimeHudOverlayPipeline.java` | 2 |
| `android/app/src/main/java/com/wbeam/hud/RuntimeHudTrendComposer.java` | 2 |
| `android/app/src/main/java/com/wbeam/stream/DecoderCapabilityInspector.java` | 2 |
| `android/app/src/main/java/com/wbeam/stream/LiveViewPlaybackCoordinator.java` | 2 |
| `android/app/src/main/java/com/wbeam/stream/MediaCodecBridge.java` | 2 |
| `android/app/src/main/java/com/wbeam/stream/PngSurfaceRenderer.java` | 2 |
| `android/app/src/main/java/com/wbeam/stream/StreamReconnectLoop.java` | 2 |
| `android/app/src/main/java/com/wbeam/stream/WbtpProtocol.java` | 2 |
| `android/app/src/main/java/com/wbeam/ui/MainActivityDebugInfoFormatter.java` | 2 |
| `android/app/src/main/java/com/wbeam/ui/MainActivitySimpleMenuCoordinator.java` | 2 |
| `android/app/src/main/java/com/wbeam/ui/MainSessionControlCoordinator.java` | 2 |
| `android/app/src/main/java/com/wbeam/ui/MainStatusCoordinator.java` | 2 |
| `android/app/src/main/java/com/wbeam/ui/MainViewBehaviorCoordinator.java` | 2 |
| `desktop/apps/desktop-tauri/src-tauri/src/adb.rs` | 2 |
| `host/rust/crates/wbeamd-core/src/infra/x11_extend.rs` | 2 |
| `host/rust/crates/wbeamd-server/src/server/session_registry.rs` | 2 |
| `host/scripts/x11_virtual_smoke.py` | 2 |
| `android/app/src/main/java/com/wbeam/ClientMetricsSample.java` | 1 |
| `android/app/src/main/java/com/wbeam/StreamService.java` | 1 |
| `android/app/src/main/java/com/wbeam/api/StatusPollerCallbacksFactory.java` | 1 |
| `android/app/src/main/java/com/wbeam/hud/MainHudInputFactory.java` | 1 |
| `android/app/src/main/java/com/wbeam/hud/RuntimeHudUpdateState.java` | 1 |
| `android/app/src/main/java/com/wbeam/hud/RuntimeTrendGridRenderer.java` | 1 |
| `android/app/src/main/java/com/wbeam/startup/MainStartupInputFactory.java` | 1 |
| `android/app/src/main/java/com/wbeam/startup/StartupOverlayHookBuilder.java` | 1 |
| `android/app/src/main/java/com/wbeam/startup/StartupOverlayHooksFactory.java` | 1 |
| `android/app/src/main/java/com/wbeam/stream/FramedPngLoop.java` | 1 |
| `android/app/src/main/java/com/wbeam/stream/MainStreamCoordinator.java` | 1 |
| `android/app/src/main/java/com/wbeam/stream/SessionUiBridge.java` | 1 |
| `android/app/src/main/java/com/wbeam/stream/SessionUiBridgeFactory.java` | 1 |
| `android/app/src/main/java/com/wbeam/stream/StreamBufferMath.java` | 1 |
| `android/app/src/main/java/com/wbeam/stream/StreamSessionController.java` | 1 |
| `android/app/src/main/java/com/wbeam/stream/VideoTestCallbacksFactory.java` | 1 |
| `android/app/src/main/java/com/wbeam/ui/BuildVariantUiCoordinator.java` | 1 |
| `android/app/src/main/java/com/wbeam/ui/HostApiFailureNotifier.java` | 1 |
| `android/app/src/main/java/com/wbeam/ui/HostHintPresenter.java` | 1 |
| `android/app/src/main/java/com/wbeam/ui/IntraOnlyButtonController.java` | 1 |
| `android/app/src/main/java/com/wbeam/ui/MainActivityButtonsSetup.java` | 1 |
| `android/app/src/main/java/com/wbeam/ui/MainActivityLifecycleCleaner.java` | 1 |
| `android/app/src/main/java/com/wbeam/ui/MainActivityRuntimeStateView.java` | 1 |
| `android/app/src/main/java/com/wbeam/ui/MainActivitySettingsInitializer.java` | 1 |
| `android/app/src/main/java/com/wbeam/ui/MainActivitySpinnersSetup.java` | 1 |
| `android/app/src/main/java/com/wbeam/ui/MainActivityStatusPresenter.java` | 1 |
| `android/app/src/main/java/com/wbeam/ui/SettingsPayloadBuilder.java` | 1 |
| `android/app/src/main/java/com/wbeam/ui/SimpleMenuUi.java` | 1 |
| `android/app/src/main/java/com/wbeam/ui/StatusTextFormatter.java` | 1 |
| `android/app/src/main/java/com/wbeam/ui/StreamConfigResolver.java` | 1 |
| `desktop/apps/desktop-tauri/src-tauri/src/service.rs` | 1 |
| `desktop/apps/desktop-tauri/src/app-hud.ts` | 1 |
| `desktop/apps/desktop-tauri/src/managers/hostApiManager.ts` | 1 |
| `host/daemon/wbeamd.py` | 1 |
| `host/rust/crates/wbeamd-api/src/lib.rs` | 1 |
| `host/rust/crates/wbeamd-core/src/domain/policy.rs` | 1 |
| `host/rust/crates/wbeamd-core/src/infra/adb.rs` | 1 |
| `host/rust/crates/wbeamd-core/src/infra/x11_monitor_object.rs` | 1 |
| `host/rust/crates/wbeamd-server/src/main.rs` | 1 |
| `host/rust/crates/wbeamd-streamer/src/capture/backend/evdi.rs` | 1 |
| `host/rust/crates/wbeamd-streamer/src/pipeline/builder.rs` | 1 |
| `host/rust/crates/wbeamd-streamer/src/pipeline/profile.rs` | 1 |
| `host/rust/crates/wbeamd-streamer/src/transport/sender.rs` | 1 |

## Issues by file

### `android/app/src/main/java/com/wbeam/startup/StartupOverlayModelBuilder.java` (42 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 18 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonReachable a static final constant or non-public and provide accessors if needed. |
| 19 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonHostName a static final constant or non-public and provide accessors if needed. |
| 20 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonService a static final constant or non-public and provide accessors if needed. |
| 21 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonBuildRevision a static final constant or non-public and provide accessors if needed. |
| 22 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonState a static final constant or non-public and provide accessors if needed. |
| 23 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonLastError a static final constant or non-public and provide accessors if needed. |
| 24 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make handshakeResolved a static final constant or non-public and provide accessors if needed. |
| 25 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make buildMismatch a static final constant or non-public and provide accessors if needed. |
| 26 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make requiresTransportProbe a static final constant or non-public and provide accessors if needed. |
| 27 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make probeOk a static final constant or non-public and provide accessors if needed. |
| 28 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make probeInFlight a static final constant or non-public and provide accessors if needed. |
| 29 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make probeInfo a static final constant or non-public and provide accessors if needed. |
| 30 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make apiImpl a static final constant or non-public and provide accessors if needed. |
| 31 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make apiBase a static final constant or non-public and provide accessors if needed. |
| 32 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make apiHost a static final constant or non-public and provide accessors if needed. |
| 33 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make streamHost a static final constant or non-public and provide accessors if needed. |
| 34 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make streamPort a static final constant or non-public and provide accessors if needed. |
| 35 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make appBuildRevision a static final constant or non-public and provide accessors if needed. |
| 36 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make lastUiInfo a static final constant or non-public and provide accessors if needed. |
| 37 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make effectiveDaemonState a static final constant or non-public and provide accessors if needed. |
| 38 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make latestPresentFps a static final constant or non-public and provide accessors if needed. |
| 39 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make startupBeganAtMs a static final constant or non-public and provide accessors if needed. |
| 40 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make controlRetryCount a static final constant or non-public and provide accessors if needed. |
| 41 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make nowMs a static final constant or non-public and provide accessors if needed. |
| 42 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make lastStatsLine a static final constant or non-public and provide accessors if needed. |
| 43 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonErrCompact a static final constant or non-public and provide accessors if needed. |
| 52 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make step1State a static final constant or non-public and provide accessors if needed. |
| 53 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make step2State a static final constant or non-public and provide accessors if needed. |
| 54 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make step3State a static final constant or non-public and provide accessors if needed. |
| 55 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make step1Detail a static final constant or non-public and provide accessors if needed. |
| 56 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make step2Detail a static final constant or non-public and provide accessors if needed. |
| 57 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make step3Detail a static final constant or non-public and provide accessors if needed. |
| 58 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make subtitle a static final constant or non-public and provide accessors if needed. |
| 59 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make infoLog a static final constant or non-public and provide accessors if needed. |
| 60 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make allOk a static final constant or non-public and provide accessors if needed. |
| 61 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make updatedStartupBeganAtMs a static final constant or non-public and provide accessors if needed. |
| 62 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make updatedControlRetryCount a static final constant or non-public and provide accessors if needed. |
| 63 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make elapsedMs a static final constant or non-public and provide accessors if needed. |
| 176 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal " \u00b7 " 4 times. |
| 209 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "service=" 4 times. |
| 219 | `java:S1172` | MAJOR | CODE_SMELL | OPEN | Remove this unused method parameter "elapsedMs". |
| 264 | `java:S1172` | MAJOR | CODE_SMELL | OPEN | Remove this unused method parameter "elapsedMs". |

### `android/app/src/main/java/com/wbeam/hud/MainHudCoordinator.java` (39 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 45 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make logTag a static final constant or non-public and provide accessors if needed. |
| 46 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make state a static final constant or non-public and provide accessors if needed. |
| 47 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make overlayState a static final constant or non-public and provide accessors if needed. |
| 49 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make runtimePresentSeries a static final constant or non-public and provide accessors if needed. |
| 50 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make runtimeMbpsSeries a static final constant or non-public and provide accessors if needed. |
| 51 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make runtimeDropSeries a static final constant or non-public and provide accessors if needed. |
| 52 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make runtimeLatencySeries a static final constant or non-public and provide accessors if needed. |
| 53 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make runtimeQueueSeries a static final constant or non-public and provide accessors if needed. |
| 54 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make resourceUsageTracker a static final constant or non-public and provide accessors if needed. |
| 56 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make perfHudWebView a static final constant or non-public and provide accessors if needed. |
| 57 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make perfHudText a static final constant or non-public and provide accessors if needed. |
| 58 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make perfHudPanel a static final constant or non-public and provide accessors if needed. |
| 60 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make transportQueueMaxFrames a static final constant or non-public and provide accessors if needed. |
| 61 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make decodeQueueMaxFrames a static final constant or non-public and provide accessors if needed. |
| 62 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make renderQueueMaxFrames a static final constant or non-public and provide accessors if needed. |
| 63 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make presentFpsStaleGraceMs a static final constant or non-public and provide accessors if needed. |
| 64 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make metricsStaleGraceMs a static final constant or non-public and provide accessors if needed. |
| 65 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make fpsLowAnchor a static final constant or non-public and provide accessors if needed. |
| 66 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make hudTextColorOffline a static final constant or non-public and provide accessors if needed. |
| 67 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make hudTextColorLive a static final constant or non-public and provide accessors if needed. |
| 68 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make appBuildRevision a static final constant or non-public and provide accessors if needed. |
| 70 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make selectedFpsProvider a static final constant or non-public and provide accessors if needed. |
| 71 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make selectedProfileProvider a static final constant or non-public and provide accessors if needed. |
| 72 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make selectedEncoderProvider a static final constant or non-public and provide accessors if needed. |
| 73 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make streamSizeProvider a static final constant or non-public and provide accessors if needed. |
| 75 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonReachableProvider a static final constant or non-public and provide accessors if needed. |
| 76 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonStateProvider a static final constant or non-public and provide accessors if needed. |
| 77 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonHostNameProvider a static final constant or non-public and provide accessors if needed. |
| 78 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonBuildRevisionProvider a static final constant or non-public and provide accessors if needed. |
| 79 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonLastErrorProvider a static final constant or non-public and provide accessors if needed. |
| 80 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonRunIdProvider a static final constant or non-public and provide accessors if needed. |
| 81 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonUptimeSecProvider a static final constant or non-public and provide accessors if needed. |
| 82 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonStateUiProvider a static final constant or non-public and provide accessors if needed. |
| 84 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make debugOverlayVisibleProvider a static final constant or non-public and provide accessors if needed. |
| 85 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make buildDebug a static final constant or non-public and provide accessors if needed. |
| 87 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make debugOverlayHandler a static final constant or non-public and provide accessors if needed. |
| 88 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make hudTextOnlyHandler a static final constant or non-public and provide accessors if needed. |
| 89 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make refreshDebugOverlayHandler a static final constant or non-public and provide accessors if needed. |
| 90 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make snapshotLogHandler a static final constant or non-public and provide accessors if needed. |

### `android/app/src/main/java/com/wbeam/hud/RuntimeHudWebPayloadBuilder.java` (37 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 12 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make selectedProfile a static final constant or non-public and provide accessors if needed. |
| 13 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make selectedEncoder a static final constant or non-public and provide accessors if needed. |
| 14 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make streamWidth a static final constant or non-public and provide accessors if needed. |
| 15 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make streamHeight a static final constant or non-public and provide accessors if needed. |
| 17 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonHostName a static final constant or non-public and provide accessors if needed. |
| 18 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonStateUi a static final constant or non-public and provide accessors if needed. |
| 19 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonBuildRevision a static final constant or non-public and provide accessors if needed. |
| 20 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make appBuildRevision a static final constant or non-public and provide accessors if needed. |
| 21 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonLastError a static final constant or non-public and provide accessors if needed. |
| 22 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make tone a static final constant or non-public and provide accessors if needed. |
| 24 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make targetFps a static final constant or non-public and provide accessors if needed. |
| 25 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make presentFps a static final constant or non-public and provide accessors if needed. |
| 26 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make recvFps a static final constant or non-public and provide accessors if needed. |
| 27 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make decodeFps a static final constant or non-public and provide accessors if needed. |
| 28 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make liveMbps a static final constant or non-public and provide accessors if needed. |
| 29 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make e2eP95 a static final constant or non-public and provide accessors if needed. |
| 30 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make decodeP95 a static final constant or non-public and provide accessors if needed. |
| 31 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make renderP95 a static final constant or non-public and provide accessors if needed. |
| 32 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make frametimeP95 a static final constant or non-public and provide accessors if needed. |
| 33 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make dropsPerSec a static final constant or non-public and provide accessors if needed. |
| 35 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make qT a static final constant or non-public and provide accessors if needed. |
| 36 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make qD a static final constant or non-public and provide accessors if needed. |
| 37 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make qR a static final constant or non-public and provide accessors if needed. |
| 38 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make qTMax a static final constant or non-public and provide accessors if needed. |
| 39 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make qDMax a static final constant or non-public and provide accessors if needed. |
| 40 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make qRMax a static final constant or non-public and provide accessors if needed. |
| 42 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make adaptiveLevel a static final constant or non-public and provide accessors if needed. |
| 43 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make adaptiveAction a static final constant or non-public and provide accessors if needed. |
| 44 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make drops a static final constant or non-public and provide accessors if needed. |
| 45 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make bpHigh a static final constant or non-public and provide accessors if needed. |
| 46 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make bpRecover a static final constant or non-public and provide accessors if needed. |
| 47 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make reason a static final constant or non-public and provide accessors if needed. |
| 48 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make tuningActive a static final constant or non-public and provide accessors if needed. |
| 49 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make tuningLine a static final constant or non-public and provide accessors if needed. |
| 51 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make metricChartsHtml a static final constant or non-public and provide accessors if needed. |
| 52 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make resourceRowsHtml a static final constant or non-public and provide accessors if needed. |
| 84 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "%.2f ms" 3 times. |

### `android/app/src/main/java/com/wbeam/hud/RuntimeHudOverlayRenderer.java` (36 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 9 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonReachable a static final constant or non-public and provide accessors if needed. |
| 10 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make selectedProfile a static final constant or non-public and provide accessors if needed. |
| 11 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make selectedEncoder a static final constant or non-public and provide accessors if needed. |
| 12 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make streamWidth a static final constant or non-public and provide accessors if needed. |
| 13 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make streamHeight a static final constant or non-public and provide accessors if needed. |
| 14 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonHostName a static final constant or non-public and provide accessors if needed. |
| 15 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonStateUi a static final constant or non-public and provide accessors if needed. |
| 16 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonBuildRevision a static final constant or non-public and provide accessors if needed. |
| 17 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make appBuildRevision a static final constant or non-public and provide accessors if needed. |
| 18 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonLastError a static final constant or non-public and provide accessors if needed. |
| 19 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make tone a static final constant or non-public and provide accessors if needed. |
| 20 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make targetFps a static final constant or non-public and provide accessors if needed. |
| 21 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make presentFps a static final constant or non-public and provide accessors if needed. |
| 22 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make recvFps a static final constant or non-public and provide accessors if needed. |
| 23 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make decodeFps a static final constant or non-public and provide accessors if needed. |
| 24 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make liveMbps a static final constant or non-public and provide accessors if needed. |
| 25 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make e2eP95 a static final constant or non-public and provide accessors if needed. |
| 26 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make decodeP95 a static final constant or non-public and provide accessors if needed. |
| 27 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make renderP95 a static final constant or non-public and provide accessors if needed. |
| 28 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make frametimeP95 a static final constant or non-public and provide accessors if needed. |
| 29 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make dropsPerSec a static final constant or non-public and provide accessors if needed. |
| 30 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make qT a static final constant or non-public and provide accessors if needed. |
| 31 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make qD a static final constant or non-public and provide accessors if needed. |
| 32 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make qR a static final constant or non-public and provide accessors if needed. |
| 33 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make qTMax a static final constant or non-public and provide accessors if needed. |
| 34 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make qDMax a static final constant or non-public and provide accessors if needed. |
| 35 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make qRMax a static final constant or non-public and provide accessors if needed. |
| 36 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make adaptiveLevel a static final constant or non-public and provide accessors if needed. |
| 37 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make adaptiveAction a static final constant or non-public and provide accessors if needed. |
| 38 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make drops a static final constant or non-public and provide accessors if needed. |
| 39 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make bpHigh a static final constant or non-public and provide accessors if needed. |
| 40 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make bpRecover a static final constant or non-public and provide accessors if needed. |
| 41 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make reason a static final constant or non-public and provide accessors if needed. |
| 42 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make tuningActive a static final constant or non-public and provide accessors if needed. |
| 43 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make tuningLine a static final constant or non-public and provide accessors if needed. |
| 44 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make metricChartsHtml a static final constant or non-public and provide accessors if needed. |

### `android/app/src/main/java/com/wbeam/startup/MainStartupCoordinator.java` (35 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 29 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make preflightOverlay a static final constant or non-public and provide accessors if needed. |
| 30 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make startupOverlayController a static final constant or non-public and provide accessors if needed. |
| 31 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make startupOverlayViews a static final constant or non-public and provide accessors if needed. |
| 32 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make videoTestController a static final constant or non-public and provide accessors if needed. |
| 33 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make startupVideoTestHintColor a static final constant or non-public and provide accessors if needed. |
| 35 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make transportProbe a static final constant or non-public and provide accessors if needed. |
| 36 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make ioExecutor a static final constant or non-public and provide accessors if needed. |
| 37 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make uiHandler a static final constant or non-public and provide accessors if needed. |
| 39 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonReachable a static final constant or non-public and provide accessors if needed. |
| 40 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonHostName a static final constant or non-public and provide accessors if needed. |
| 41 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonService a static final constant or non-public and provide accessors if needed. |
| 42 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonBuildRevision a static final constant or non-public and provide accessors if needed. |
| 43 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonState a static final constant or non-public and provide accessors if needed. |
| 44 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonLastError a static final constant or non-public and provide accessors if needed. |
| 45 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make handshakeResolved a static final constant or non-public and provide accessors if needed. |
| 47 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make apiImpl a static final constant or non-public and provide accessors if needed. |
| 48 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make apiBase a static final constant or non-public and provide accessors if needed. |
| 49 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make apiHost a static final constant or non-public and provide accessors if needed. |
| 50 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make streamHost a static final constant or non-public and provide accessors if needed. |
| 51 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make streamPort a static final constant or non-public and provide accessors if needed. |
| 52 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make appBuildRevision a static final constant or non-public and provide accessors if needed. |
| 54 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make lastUiInfo a static final constant or non-public and provide accessors if needed. |
| 55 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make latestPresentFps a static final constant or non-public and provide accessors if needed. |
| 56 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make lastStatsLine a static final constant or non-public and provide accessors if needed. |
| 57 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonErrorCompact a static final constant or non-public and provide accessors if needed. |
| 58 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make preflightAnimTick a static final constant or non-public and provide accessors if needed. |
| 60 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make startupBeganAtMs a static final constant or non-public and provide accessors if needed. |
| 61 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make controlRetryCount a static final constant or non-public and provide accessors if needed. |
| 62 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make startupDismissed a static final constant or non-public and provide accessors if needed. |
| 63 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make preflightComplete a static final constant or non-public and provide accessors if needed. |
| 65 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make buildMismatchProvider a static final constant or non-public and provide accessors if needed. |
| 66 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make effectiveDaemonStateProvider a static final constant or non-public and provide accessors if needed. |
| 67 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make overlayChangedHandler a static final constant or non-public and provide accessors if needed. |
| 68 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make infoLogHandler a static final constant or non-public and provide accessors if needed. |
| 69 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make warnLogHandler a static final constant or non-public and provide accessors if needed. |

### `android/app/src/main/java/com/wbeam/ui/MainDaemonRuntimeCoordinator.java` (35 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 49 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make reachable a static final constant or non-public and provide accessors if needed. |
| 50 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make wasReachable a static final constant or non-public and provide accessors if needed. |
| 51 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make hostName a static final constant or non-public and provide accessors if needed. |
| 52 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make state a static final constant or non-public and provide accessors if needed. |
| 53 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make runId a static final constant or non-public and provide accessors if needed. |
| 54 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make lastError a static final constant or non-public and provide accessors if needed. |
| 55 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make errorChanged a static final constant or non-public and provide accessors if needed. |
| 56 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make uptimeSec a static final constant or non-public and provide accessors if needed. |
| 57 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make service a static final constant or non-public and provide accessors if needed. |
| 58 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make buildRevision a static final constant or non-public and provide accessors if needed. |
| 59 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make metrics a static final constant or non-public and provide accessors if needed. |
| 63 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemon a static final constant or non-public and provide accessors if needed. |
| 64 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make uiState a static final constant or non-public and provide accessors if needed. |
| 65 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make requiresTransportProbeNowProvider a static final constant or non-public and provide accessors if needed. |
| 66 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make probeStarter a static final constant or non-public and provide accessors if needed. |
| 67 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make hostConnectedNotifier a static final constant or non-public and provide accessors if needed. |
| 68 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make lineLogger a static final constant or non-public and provide accessors if needed. |
| 69 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make stopLiveViewTask a static final constant or non-public and provide accessors if needed. |
| 70 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make refreshUiTask a static final constant or non-public and provide accessors if needed. |
| 71 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make statsSink a static final constant or non-public and provide accessors if needed. |
| 72 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make perfHudSink a static final constant or non-public and provide accessors if needed. |
| 76 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemon a static final constant or non-public and provide accessors if needed. |
| 77 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make uiState a static final constant or non-public and provide accessors if needed. |
| 78 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make transportProbe a static final constant or non-public and provide accessors if needed. |
| 79 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make stateError a static final constant or non-public and provide accessors if needed. |
| 80 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make apiBase a static final constant or non-public and provide accessors if needed. |
| 81 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make stopLiveViewTask a static final constant or non-public and provide accessors if needed. |
| 82 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make updateActionButtonsTask a static final constant or non-public and provide accessors if needed. |
| 83 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make updateHostHintTask a static final constant or non-public and provide accessors if needed. |
| 84 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make updatePerfHudUnavailableTask a static final constant or non-public and provide accessors if needed. |
| 85 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make refreshStatusTextTask a static final constant or non-public and provide accessors if needed. |
| 86 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make updatePreflightOverlayTask a static final constant or non-public and provide accessors if needed. |
| 87 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make uiStatusSink a static final constant or non-public and provide accessors if needed. |
| 88 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make lineLogger a static final constant or non-public and provide accessors if needed. |
| 89 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make toastSink a static final constant or non-public and provide accessors if needed. |

### `android/app/src/main/java/com/wbeam/ui/MainActivityControlViewsBinder.java` (33 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 14 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make liveLogText a static final constant or non-public and provide accessors if needed. |
| 15 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make resValueText a static final constant or non-public and provide accessors if needed. |
| 16 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make fpsValueText a static final constant or non-public and provide accessors if needed. |
| 17 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make bitrateValueText a static final constant or non-public and provide accessors if needed. |
| 18 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make hostHintText a static final constant or non-public and provide accessors if needed. |
| 20 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make profileSpinner a static final constant or non-public and provide accessors if needed. |
| 21 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make encoderSpinner a static final constant or non-public and provide accessors if needed. |
| 22 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make cursorSpinner a static final constant or non-public and provide accessors if needed. |
| 24 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make resolutionSeek a static final constant or non-public and provide accessors if needed. |
| 25 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make fpsSeek a static final constant or non-public and provide accessors if needed. |
| 26 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make bitrateSeek a static final constant or non-public and provide accessors if needed. |
| 28 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make settingsButton a static final constant or non-public and provide accessors if needed. |
| 29 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make logButton a static final constant or non-public and provide accessors if needed. |
| 30 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make settingsCloseButton a static final constant or non-public and provide accessors if needed. |
| 31 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make applySettingsButton a static final constant or non-public and provide accessors if needed. |
| 32 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make quickStartButton a static final constant or non-public and provide accessors if needed. |
| 33 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make quickStopButton a static final constant or non-public and provide accessors if needed. |
| 34 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make quickTestButton a static final constant or non-public and provide accessors if needed. |
| 35 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make startButton a static final constant or non-public and provide accessors if needed. |
| 36 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make stopButton a static final constant or non-public and provide accessors if needed. |
| 37 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make testButton a static final constant or non-public and provide accessors if needed. |
| 38 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make fullscreenButton a static final constant or non-public and provide accessors if needed. |
| 39 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make cursorOverlayButton a static final constant or non-public and provide accessors if needed. |
| 40 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make intraOnlyButton a static final constant or non-public and provide accessors if needed. |
| 41 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make simpleModeH265Button a static final constant or non-public and provide accessors if needed. |
| 42 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make simpleModeRawButton a static final constant or non-public and provide accessors if needed. |
| 43 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make simpleFps30Button a static final constant or non-public and provide accessors if needed. |
| 44 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make simpleFps45Button a static final constant or non-public and provide accessors if needed. |
| 45 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make simpleFps60Button a static final constant or non-public and provide accessors if needed. |
| 46 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make simpleFps90Button a static final constant or non-public and provide accessors if needed. |
| 47 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make simpleFps120Button a static final constant or non-public and provide accessors if needed. |
| 48 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make simpleFps144Button a static final constant or non-public and provide accessors if needed. |
| 49 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make simpleApplyButton a static final constant or non-public and provide accessors if needed. |

### `android/app/src/main/java/com/wbeam/telemetry/RuntimeTelemetryMapper.java` (29 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 14 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make frameInHost a static final constant or non-public and provide accessors if needed. |
| 15 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make frameOutHost a static final constant or non-public and provide accessors if needed. |
| 16 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make streamUptimeSec a static final constant or non-public and provide accessors if needed. |
| 18 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make targetFps a static final constant or non-public and provide accessors if needed. |
| 19 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make presentFps a static final constant or non-public and provide accessors if needed. |
| 20 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make recvFps a static final constant or non-public and provide accessors if needed. |
| 21 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make decodeFps a static final constant or non-public and provide accessors if needed. |
| 22 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make frametimeP95 a static final constant or non-public and provide accessors if needed. |
| 23 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make decodeP95 a static final constant or non-public and provide accessors if needed. |
| 24 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make renderP95 a static final constant or non-public and provide accessors if needed. |
| 25 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make e2eP95 a static final constant or non-public and provide accessors if needed. |
| 27 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make qT a static final constant or non-public and provide accessors if needed. |
| 28 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make qD a static final constant or non-public and provide accessors if needed. |
| 29 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make qR a static final constant or non-public and provide accessors if needed. |
| 30 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make qTMax a static final constant or non-public and provide accessors if needed. |
| 31 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make qDMax a static final constant or non-public and provide accessors if needed. |
| 32 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make qRMax a static final constant or non-public and provide accessors if needed. |
| 34 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make adaptiveLevel a static final constant or non-public and provide accessors if needed. |
| 35 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make adaptiveAction a static final constant or non-public and provide accessors if needed. |
| 36 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make drops a static final constant or non-public and provide accessors if needed. |
| 37 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make bpHigh a static final constant or non-public and provide accessors if needed. |
| 38 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make bpRecover a static final constant or non-public and provide accessors if needed. |
| 39 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make reason a static final constant or non-public and provide accessors if needed. |
| 41 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make latestDroppedFrames a static final constant or non-public and provide accessors if needed. |
| 42 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make latestTooLateFrames a static final constant or non-public and provide accessors if needed. |
| 43 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make bitrateMbps a static final constant or non-public and provide accessors if needed. |
| 44 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make tuningActive a static final constant or non-public and provide accessors if needed. |
| 45 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make tuningLine a static final constant or non-public and provide accessors if needed. |
| 48 | `java:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this method to reduce its Cognitive Complexity from 38 to the 15 allowed. |

### `android/app/src/main/java/com/wbeam/startup/StartupOverlayInputFactory.java` (27 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 5 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonReachable a static final constant or non-public and provide accessors if needed. |
| 6 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonHostName a static final constant or non-public and provide accessors if needed. |
| 7 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonService a static final constant or non-public and provide accessors if needed. |
| 8 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonBuildRevision a static final constant or non-public and provide accessors if needed. |
| 9 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonState a static final constant or non-public and provide accessors if needed. |
| 10 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonLastError a static final constant or non-public and provide accessors if needed. |
| 11 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make handshakeResolved a static final constant or non-public and provide accessors if needed. |
| 12 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make buildMismatch a static final constant or non-public and provide accessors if needed. |
| 13 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make requiresTransportProbe a static final constant or non-public and provide accessors if needed. |
| 14 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make probeOk a static final constant or non-public and provide accessors if needed. |
| 15 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make probeInFlight a static final constant or non-public and provide accessors if needed. |
| 16 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make probeInfo a static final constant or non-public and provide accessors if needed. |
| 17 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make apiImpl a static final constant or non-public and provide accessors if needed. |
| 18 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make apiBase a static final constant or non-public and provide accessors if needed. |
| 19 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make apiHost a static final constant or non-public and provide accessors if needed. |
| 20 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make streamHost a static final constant or non-public and provide accessors if needed. |
| 21 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make streamPort a static final constant or non-public and provide accessors if needed. |
| 22 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make appBuildRevision a static final constant or non-public and provide accessors if needed. |
| 23 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make lastUiInfo a static final constant or non-public and provide accessors if needed. |
| 24 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make effectiveDaemonState a static final constant or non-public and provide accessors if needed. |
| 25 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make latestPresentFps a static final constant or non-public and provide accessors if needed. |
| 26 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make startupBeganAtMs a static final constant or non-public and provide accessors if needed. |
| 27 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make controlRetryCount a static final constant or non-public and provide accessors if needed. |
| 28 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make nowMs a static final constant or non-public and provide accessors if needed. |
| 29 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make lastStatsLine a static final constant or non-public and provide accessors if needed. |
| 30 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonErrCompact a static final constant or non-public and provide accessors if needed. |
| 36 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 26 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/hud/RuntimeHudRenderCoordinator.java` (25 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 9 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make runtimePresentSeries a static final constant or non-public and provide accessors if needed. |
| 10 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make runtimeMbpsSeries a static final constant or non-public and provide accessors if needed. |
| 11 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make runtimeDropSeries a static final constant or non-public and provide accessors if needed. |
| 12 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make runtimeLatencySeries a static final constant or non-public and provide accessors if needed. |
| 13 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make runtimeQueueSeries a static final constant or non-public and provide accessors if needed. |
| 14 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make state a static final constant or non-public and provide accessors if needed. |
| 15 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make fpsLowAnchor a static final constant or non-public and provide accessors if needed. |
| 17 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonReachable a static final constant or non-public and provide accessors if needed. |
| 18 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make selectedProfile a static final constant or non-public and provide accessors if needed. |
| 19 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make selectedEncoder a static final constant or non-public and provide accessors if needed. |
| 20 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make streamWidth a static final constant or non-public and provide accessors if needed. |
| 21 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make streamHeight a static final constant or non-public and provide accessors if needed. |
| 22 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonHostName a static final constant or non-public and provide accessors if needed. |
| 23 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonStateUi a static final constant or non-public and provide accessors if needed. |
| 24 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonBuildRevision a static final constant or non-public and provide accessors if needed. |
| 25 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make appBuildRevision a static final constant or non-public and provide accessors if needed. |
| 26 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonLastError a static final constant or non-public and provide accessors if needed. |
| 27 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make tuningActive a static final constant or non-public and provide accessors if needed. |
| 28 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make tuningLine a static final constant or non-public and provide accessors if needed. |
| 29 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make resourceUsageTracker a static final constant or non-public and provide accessors if needed. |
| 30 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make perfHudWebView a static final constant or non-public and provide accessors if needed. |
| 31 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make perfHudText a static final constant or non-public and provide accessors if needed. |
| 32 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make perfHudPanel a static final constant or non-public and provide accessors if needed. |
| 33 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make hudOverlayState a static final constant or non-public and provide accessors if needed. |
| 34 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make hudTextColorLive a static final constant or non-public and provide accessors if needed. |

### `android/app/src/main/java/com/wbeam/hud/RuntimeHudStateCoordinator.java` (22 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 10 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make metrics a static final constant or non-public and provide accessors if needed. |
| 11 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make selectedFps a static final constant or non-public and provide accessors if needed. |
| 12 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make transportQueueMaxFrames a static final constant or non-public and provide accessors if needed. |
| 13 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make decodeQueueMaxFrames a static final constant or non-public and provide accessors if needed. |
| 14 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make renderQueueMaxFrames a static final constant or non-public and provide accessors if needed. |
| 15 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make nowMs a static final constant or non-public and provide accessors if needed. |
| 16 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make stablePresentFps a static final constant or non-public and provide accessors if needed. |
| 17 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make stablePresentFpsAtMs a static final constant or non-public and provide accessors if needed. |
| 18 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make presentFpsStaleGraceMs a static final constant or non-public and provide accessors if needed. |
| 19 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make dropPrevCount a static final constant or non-public and provide accessors if needed. |
| 20 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make dropPrevAtMs a static final constant or non-public and provide accessors if needed. |
| 21 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonState a static final constant or non-public and provide accessors if needed. |
| 22 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make latestStreamUptimeSec a static final constant or non-public and provide accessors if needed. |
| 23 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make latestFrameOutHost a static final constant or non-public and provide accessors if needed. |
| 24 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonRunId a static final constant or non-public and provide accessors if needed. |
| 25 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonUptimeSec a static final constant or non-public and provide accessors if needed. |
| 26 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonLastError a static final constant or non-public and provide accessors if needed. |
| 30 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make state a static final constant or non-public and provide accessors if needed. |
| 31 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make daemonStateUi a static final constant or non-public and provide accessors if needed. |
| 32 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make compactLine a static final constant or non-public and provide accessors if needed. |
| 33 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make pressureLog a static final constant or non-public and provide accessors if needed. |
| 34 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make debugSnapshot a static final constant or non-public and provide accessors if needed. |

### `android/app/src/main/java/com/wbeam/startup/StartupOverlayViewRenderer.java` (20 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 13 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make titleText a static final constant or non-public and provide accessors if needed. |
| 14 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make step1Card a static final constant or non-public and provide accessors if needed. |
| 15 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make step2Card a static final constant or non-public and provide accessors if needed. |
| 16 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make step3Card a static final constant or non-public and provide accessors if needed. |
| 17 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make step1Badge a static final constant or non-public and provide accessors if needed. |
| 18 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make step1Label a static final constant or non-public and provide accessors if needed. |
| 19 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make step1Detail a static final constant or non-public and provide accessors if needed. |
| 20 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make step1Status a static final constant or non-public and provide accessors if needed. |
| 21 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make step2Badge a static final constant or non-public and provide accessors if needed. |
| 22 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make step2Label a static final constant or non-public and provide accessors if needed. |
| 23 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make step2Detail a static final constant or non-public and provide accessors if needed. |
| 24 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make step2Status a static final constant or non-public and provide accessors if needed. |
| 25 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make step3Badge a static final constant or non-public and provide accessors if needed. |
| 26 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make step3Label a static final constant or non-public and provide accessors if needed. |
| 27 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make step3Detail a static final constant or non-public and provide accessors if needed. |
| 28 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make step3Status a static final constant or non-public and provide accessors if needed. |
| 29 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make subtitleText a static final constant or non-public and provide accessors if needed. |
| 30 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make infoText a static final constant or non-public and provide accessors if needed. |
| 99 | `java:S3358` | MAJOR | CODE_SMELL | OPEN | Extract this nested ternary operation into an independent statement. |
| 110 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 9 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/ui/MainActivityPrimaryViewsBinder.java` (20 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 14 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make rootLayout a static final constant or non-public and provide accessors if needed. |
| 15 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make topBar a static final constant or non-public and provide accessors if needed. |
| 16 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make quickActionRow a static final constant or non-public and provide accessors if needed. |
| 17 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make settingsPanel a static final constant or non-public and provide accessors if needed. |
| 18 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make simpleMenuPanel a static final constant or non-public and provide accessors if needed. |
| 19 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make statusPanel a static final constant or non-public and provide accessors if needed. |
| 20 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make perfHudPanel a static final constant or non-public and provide accessors if needed. |
| 21 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make debugInfoPanel a static final constant or non-public and provide accessors if needed. |
| 22 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make debugFpsGraphView a static final constant or non-public and provide accessors if needed. |
| 23 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make preflightOverlay a static final constant or non-public and provide accessors if needed. |
| 24 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make debugControlsRow a static final constant or non-public and provide accessors if needed. |
| 25 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make statusLed a static final constant or non-public and provide accessors if needed. |
| 26 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make cursorOverlay a static final constant or non-public and provide accessors if needed. |
| 28 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make statusText a static final constant or non-public and provide accessors if needed. |
| 29 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make detailText a static final constant or non-public and provide accessors if needed. |
| 30 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make bpsText a static final constant or non-public and provide accessors if needed. |
| 31 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make statsText a static final constant or non-public and provide accessors if needed. |
| 32 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make perfHudText a static final constant or non-public and provide accessors if needed. |
| 33 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make perfHudWebView a static final constant or non-public and provide accessors if needed. |
| 34 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make debugInfoText a static final constant or non-public and provide accessors if needed. |

### `desktop/apps/desktop-tauri/src/App.tsx` (19 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 23 | `typescript:S3863` | MINOR | CODE_SMELL | OPEN | './types' imported multiple times. |
| 24 | `typescript:S3863` | MINOR | CODE_SMELL | OPEN | './types' imported multiple times. |
| 157 | `typescript:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 22 to the 15 allowed. |
| 338 | `typescript:S4325` | MINOR | CODE_SMELL | OPEN | This assertion is unnecessary since it does not change the type of the expression. |
| 338 | `typescript:S7735` | MINOR | CODE_SMELL | OPEN | Unexpected negated condition. |
| 627 | `typescript:S3358` | MAJOR | CODE_SMELL | OPEN | Extract this nested ternary operation into an independent statement. |
| 628 | `typescript:S3358` | MAJOR | CODE_SMELL | OPEN | Extract this nested ternary operation into an independent statement. |
| 680 | `typescript:S6772` | MAJOR | CODE_SMELL | OPEN | Ambiguous spacing before next element small |
| 729 | `typescript:S3358` | MAJOR | CODE_SMELL | OPEN | Extract this nested ternary operation into an independent statement. |
| 774 | `typescript:S6819` | MAJOR | CODE_SMELL | OPEN | Use <dialog> instead of the "dialog" role to ensure accessibility across all devices. |
| 786 | `typescript:S7735` | MINOR | CODE_SMELL | OPEN | Unexpected negated condition. |
| 796 | `typescript:S6772` | MAJOR | CODE_SMELL | OPEN | Ambiguous spacing before next element small |
| 810 | `typescript:S6772` | MAJOR | CODE_SMELL | OPEN | Ambiguous spacing before next element small |
| 932 | `typescript:S6819` | MAJOR | CODE_SMELL | OPEN | Use <dialog> instead of the "dialog" role to ensure accessibility across all devices. |
| 957 | `typescript:S6819` | MAJOR | CODE_SMELL | OPEN | Use <dialog> instead of the "dialog" role to ensure accessibility across all devices. |
| 962 | `typescript:S3358` | MAJOR | CODE_SMELL | OPEN | Extract this nested ternary operation into an independent statement. |
| 964 | `typescript:S3358` | MAJOR | CODE_SMELL | OPEN | Extract this nested ternary operation into an independent statement. |
| 966 | `typescript:S3358` | MAJOR | CODE_SMELL | OPEN | Extract this nested ternary operation into an independent statement. |
| 997 | `typescript:S6582` | MAJOR | CODE_SMELL | OPEN | Prefer using an optional chain expression instead, as it's more concise and easier to read. |

### `android/app/src/main/java/com/wbeam/startup/StartupStepStyler.java` (13 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 132 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make state a static final constant or non-public and provide accessors if needed. |
| 133 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make animTick a static final constant or non-public and provide accessors if needed. |
| 134 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make ssOk a static final constant or non-public and provide accessors if needed. |
| 135 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make ssError a static final constant or non-public and provide accessors if needed. |
| 136 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make ssActive a static final constant or non-public and provide accessors if needed. |
| 137 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make number a static final constant or non-public and provide accessors if needed. |
| 138 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make card a static final constant or non-public and provide accessors if needed. |
| 139 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make badge a static final constant or non-public and provide accessors if needed. |
| 140 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make label a static final constant or non-public and provide accessors if needed. |
| 141 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make status a static final constant or non-public and provide accessors if needed. |
| 142 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make detail a static final constant or non-public and provide accessors if needed. |
| 143 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make detailText a static final constant or non-public and provide accessors if needed. |
| 145 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Constructor has 12 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/stream/VideoTestController.java` (13 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 153 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "error" 8 times. |
| 173 | `java:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this method to reduce its Cognitive Complexity from 16 to the 15 allowed. |
| 186 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "preset " 7 times. |
| 192 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "connecting" 4 times. |
| 223 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "streaming" 3 times. |
| 339 | `java:S108` | MAJOR | CODE_SMELL | OPEN | Remove this block of code, fill it in, or add a comment explaining why it is empty. |
| 340 | `java:S108` | MAJOR | CODE_SMELL | OPEN | Remove this block of code, fill it in, or add a comment explaining why it is empty. |
| 341 | `java:S108` | MAJOR | CODE_SMELL | OPEN | Remove this block of code, fill it in, or add a comment explaining why it is empty. |
| 342 | `java:S108` | MAJOR | CODE_SMELL | OPEN | Remove this block of code, fill it in, or add a comment explaining why it is empty. |
| 343 | `java:S108` | MAJOR | CODE_SMELL | OPEN | Remove this block of code, fill it in, or add a comment explaining why it is empty. |
| 344 | `java:S108` | MAJOR | CODE_SMELL | OPEN | Remove this block of code, fill it in, or add a comment explaining why it is empty. |
| 345 | `java:S108` | MAJOR | CODE_SMELL | OPEN | Remove this block of code, fill it in, or add a comment explaining why it is empty. |
| 412 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "[RUN TESTS LIVE] " 6 times. |

### `android/app/src/main/java/com/wbeam/ui/MainActivityStatusTracker.java` (13 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 5 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make lastUiState a static final constant or non-public and provide accessors if needed. |
| 6 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make lastUiInfo a static final constant or non-public and provide accessors if needed. |
| 7 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make lastUiBps a static final constant or non-public and provide accessors if needed. |
| 8 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make lastCriticalErrorInfo a static final constant or non-public and provide accessors if needed. |
| 9 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make lastCriticalErrorLogAtMs a static final constant or non-public and provide accessors if needed. |
| 13 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make state a static final constant or non-public and provide accessors if needed. |
| 14 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make info a static final constant or non-public and provide accessors if needed. |
| 15 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make bps a static final constant or non-public and provide accessors if needed. |
| 16 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make criticalErrorInfo a static final constant or non-public and provide accessors if needed. |
| 17 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make criticalErrorLogAtMs a static final constant or non-public and provide accessors if needed. |
| 18 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make shouldLogCritical a static final constant or non-public and provide accessors if needed. |
| 19 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make criticalLogLine a static final constant or non-public and provide accessors if needed. |
| 25 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 9 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/ui/MainActivityDaemonStatusCoordinator.java` (12 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 11 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make reachable a static final constant or non-public and provide accessors if needed. |
| 12 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make wasReachable a static final constant or non-public and provide accessors if needed. |
| 13 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make hostName a static final constant or non-public and provide accessors if needed. |
| 14 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make state a static final constant or non-public and provide accessors if needed. |
| 15 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make lastError a static final constant or non-public and provide accessors if needed. |
| 16 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make errorChanged a static final constant or non-public and provide accessors if needed. |
| 17 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make service a static final constant or non-public and provide accessors if needed. |
| 18 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make metrics a static final constant or non-public and provide accessors if needed. |
| 19 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make handshakeResolved a static final constant or non-public and provide accessors if needed. |
| 20 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make requiresTransportProbeNow a static final constant or non-public and provide accessors if needed. |
| 24 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make handshakeResolved a static final constant or non-public and provide accessors if needed. |
| 25 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make hostStatsLine a static final constant or non-public and provide accessors if needed. |

### `android/app/src/main/java/com/wbeam/MainActivity.java` (11 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 100 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Use already-defined constant 'DEFAULT_PROFILE' instead of duplicating its value here. |
| 107 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Use already-defined constant 'DEFAULT_CURSOR_MODE' instead of duplicating its value here. |
| 132 | `java:S1068` | MAJOR | CODE_SMELL | OPEN | Remove this unused "rootLayout" private field. |
| 132 | `java:S1450` | MINOR | CODE_SMELL | OPEN | Remove the "rootLayout" field and declare it as a local variable in the relevant methods. |
| 135 | `java:S1068` | MAJOR | CODE_SMELL | OPEN | Remove this unused "settingsPanel" private field. |
| 135 | `java:S1450` | MINOR | CODE_SMELL | OPEN | Remove the "settingsPanel" field and declare it as a local variable in the relevant methods. |
| 144 | `java:S1068` | MAJOR | CODE_SMELL | OPEN | Remove this unused "cursorOverlay" private field. |
| 144 | `java:S1450` | MINOR | CODE_SMELL | OPEN | Remove the "cursorOverlay" field and declare it as a local variable in the relevant methods. |
| 179 | `java:S1068` | MAJOR | CODE_SMELL | OPEN | Remove this unused "cursorOverlayButton" private field. |
| 179 | `java:S1450` | MINOR | CODE_SMELL | OPEN | Remove the "cursorOverlayButton" field and declare it as a local variable in the relevant methods. |
| 426 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 11 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/hud/HudRenderSupport.java` (10 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 14 | `java:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this method to reduce its Cognitive Complexity from 16 to the 15 allowed. |
| 21 | `java:S135` | MINOR | CODE_SMELL | OPEN | Reduce the total number of break and continue statements in this loop to use at most one. |
| 101 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "state-risk" 4 times. |
z| 104 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "state-warn" 4 times. |
| 107 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "state-ok" 4 times. |
| 165 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "PENDING" 3 times. |
| 170 | `java:S135` | MINOR | CODE_SMELL | OPEN | Reduce the total number of break and continue statements in this loop to use at most one. |
| 229 | `java:S1168` | MAJOR | CODE_SMELL | OPEN | Return an empty array instead of null. |
| 234 | `java:S135` | MINOR | CODE_SMELL | OPEN | Reduce the total number of break and continue statements in this loop to use at most one. |
| 247 | `java:S1168` | MAJOR | CODE_SMELL | OPEN | Return an empty array instead of null. |

### `android/app/src/main/java/com/wbeam/hud/MainHudState.java` (10 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 4 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make compactLine a static final constant or non-public and provide accessors if needed. |
| 5 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make latestTargetFps a static final constant or non-public and provide accessors if needed. |
| 6 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make latestPresentFps a static final constant or non-public and provide accessors if needed. |
| 7 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make latestStreamUptimeSec a static final constant or non-public and provide accessors if needed. |
| 8 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make latestFrameOutHost a static final constant or non-public and provide accessors if needed. |
| 9 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make latestStablePresentFps a static final constant or non-public and provide accessors if needed. |
| 10 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make latestStablePresentFpsAtMs a static final constant or non-public and provide accessors if needed. |
| 11 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make lastPerfMetricsAtMs a static final constant or non-public and provide accessors if needed. |
| 12 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make runtimeDropPrevCount a static final constant or non-public and provide accessors if needed. |
| 13 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make runtimeDropPrevAtMs a static final constant or non-public and provide accessors if needed. |

### `android/app/src/main/java/com/wbeam/ui/state/MainUiState.java` (10 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 4 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make debugControlsVisible a static final constant or non-public and provide accessors if needed. |
| 5 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make debugOverlayVisible a static final constant or non-public and provide accessors if needed. |
| 6 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make liveLogVisible a static final constant or non-public and provide accessors if needed. |
| 7 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make surfaceReady a static final constant or non-public and provide accessors if needed. |
| 8 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make preflightComplete a static final constant or non-public and provide accessors if needed. |
| 9 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make preflightAnimTick a static final constant or non-public and provide accessors if needed. |
| 10 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make startupBeganAtMs a static final constant or non-public and provide accessors if needed. |
| 11 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make startupDismissed a static final constant or non-public and provide accessors if needed. |
| 12 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make handshakeResolved a static final constant or non-public and provide accessors if needed. |
| 13 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make controlRetryCount a static final constant or non-public and provide accessors if needed. |

### `android/app/src/main/java/com/wbeam/settings/SettingsRepository.java` (9 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 35 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "default" 3 times. |
| 117 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make profile a static final constant or non-public and provide accessors if needed. |
| 118 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make encoder a static final constant or non-public and provide accessors if needed. |
| 119 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make cursor a static final constant or non-public and provide accessors if needed. |
| 120 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make resScale a static final constant or non-public and provide accessors if needed. |
| 121 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make fps a static final constant or non-public and provide accessors if needed. |
| 122 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make bitrateMbps a static final constant or non-public and provide accessors if needed. |
| 123 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make localCursor a static final constant or non-public and provide accessors if needed. |
| 124 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make intraOnly a static final constant or non-public and provide accessors if needed. |

### `android/app/src/main/java/com/wbeam/ui/state/MainDaemonState.java` (9 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 4 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make reachable a static final constant or non-public and provide accessors if needed. |
| 5 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make hostName a static final constant or non-public and provide accessors if needed. |
| 6 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make service a static final constant or non-public and provide accessors if needed. |
| 7 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make buildRevision a static final constant or non-public and provide accessors if needed. |
| 8 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make state a static final constant or non-public and provide accessors if needed. |
| 9 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make lastError a static final constant or non-public and provide accessors if needed. |
| 10 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make runId a static final constant or non-public and provide accessors if needed. |
| 11 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make uptimeSec a static final constant or non-public and provide accessors if needed. |
| 13 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 8 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/stream/LegacyAnnexBDecodeLoop.java` (8 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 54 | `java:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this method to reduce its Cognitive Complexity from 202 to the 15 allowed. |
| 54 | `java:S6541` | INFO | CODE_SMELL | OPEN | A "Brain Method" was detected. Refactor it to reduce at least one of the following metrics: LOC from 289 to 64, Complexity from 56 to 14, Nesting Level from 8 to 2, Number of Variables from 66 to 6. |
| 101 | `java:S135` | MINOR | CODE_SMELL | OPEN | Reduce the total number of break and continue statements in this loop to use at most one. |
| 118 | `java:S1854` | MAJOR | CODE_SMELL | OPEN | Remove this useless assignment to local variable "avail". |
| 122 | `java:S1854` | MAJOR | CODE_SMELL | OPEN | Remove this useless assignment to local variable "avail". |
| 168 | `java:S135` | MINOR | CODE_SMELL | OPEN | Reduce the total number of break and continue statements in this loop to use at most one. |
| 250 | `java:S1481` | MINOR | CODE_SMELL | OPEN | Remove this unused "avail" local variable. |
| 250 | `java:S1854` | MAJOR | CODE_SMELL | OPEN | Remove this useless assignment to local variable "avail". |

### `android/app/src/main/java/com/wbeam/ui/MainViewBindingCoordinator.java` (8 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 27 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make primaryViews a static final constant or non-public and provide accessors if needed. |
| 28 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make controlViews a static final constant or non-public and provide accessors if needed. |
| 29 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make previewSurface a static final constant or non-public and provide accessors if needed. |
| 30 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make immersiveModeController a static final constant or non-public and provide accessors if needed. |
| 31 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make settingsPanelController a static final constant or non-public and provide accessors if needed. |
| 32 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make startupBuildVersionText a static final constant or non-public and provide accessors if needed. |
| 33 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make startupOverlayController a static final constant or non-public and provide accessors if needed. |
| 34 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make cursorOverlayController a static final constant or non-public and provide accessors if needed. |

### `android/app/src/main/java/com/wbeam/api/HostApiClient.java` (7 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 147 | `java:S2676` | MINOR | BUG | OPEN | Use the original value instead. |
| 302 | `java:S4144` | MAJOR | CODE_SMELL | OPEN | Update this method so that its implementation is not identical to "handleStopRequest" on line 295. |
| 324 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "STREAMING" 3 times. |
| 327 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "RECONNECTING" 3 times. |
| 424 | `java:S1068` | MAJOR | CODE_SMELL | OPEN | Remove this unused "lastClientMetricAtMs" private field. |
| 426 | `java:S1068` | MAJOR | CODE_SMELL | OPEN | Remove this unused "latestRecvFps" private field. |
| 429 | `java:S115` | CRITICAL | CODE_SMELL | OPEN | Rename this constant name to match the regular expression '^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$'. |

### `android/app/src/main/java/com/wbeam/api/StatusPoller.java` (7 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 26 | `java:S1068` | MAJOR | CODE_SMELL | OPEN | Remove this unused "AUTO_START_COOLDOWN_MS" private field. |
| 51 | `java:S1068` | MAJOR | CODE_SMELL | OPEN | Remove this unused "suppressAutoStartUntil" private field. |
| 53 | `java:S1068` | MAJOR | CODE_SMELL | OPEN | Remove this unused "lastAutoStartAt" private field. |
| 53 | `java:S1450` | MINOR | CODE_SMELL | OPEN | Remove the "lastAutoStartAt" field and declare it as a local variable in the relevant methods. |
| 72 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 11 parameters, which is greater than 7 authorized. |
| 153 | `java:S3398` | MINOR | CODE_SMELL | OPEN | Move this method into the anonymous class declared at line 60. |
| 269 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "connection_mode" 3 times. |

### `android/app/src/main/java/com/wbeam/hud/RuntimeHudShellRenderer.java` (7 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 21 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make chipsHtml a static final constant or non-public and provide accessors if needed. |
| 22 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make cardsHtml a static final constant or non-public and provide accessors if needed. |
| 23 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make chartsHtml a static final constant or non-public and provide accessors if needed. |
| 24 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make trendText a static final constant or non-public and provide accessors if needed. |
| 25 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make detailsRowsHtml a static final constant or non-public and provide accessors if needed. |
| 26 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make resourceRowsHtml a static final constant or non-public and provide accessors if needed. |
| 27 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make scaleClass a static final constant or non-public and provide accessors if needed. |

### `android/app/src/main/java/com/wbeam/ui/MainActivityInteractionPolicy.java` (7 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 7 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make volumeUpHeld a static final constant or non-public and provide accessors if needed. |
| 8 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make volumeDownHeld a static final constant or non-public and provide accessors if needed. |
| 9 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make debugOverlayToggleArmed a static final constant or non-public and provide accessors if needed. |
| 13 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make handled a static final constant or non-public and provide accessors if needed. |
| 14 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make scheduleToggle a static final constant or non-public and provide accessors if needed. |
| 15 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make cancelScheduledToggle a static final constant or non-public and provide accessors if needed. |
| 16 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make resetArmed a static final constant or non-public and provide accessors if needed. |

### `host/scripts/stream_wayland_portal_h264.py` (7 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 64 | `python:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 18 to the 15 allowed. |
| 314 | `python:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 88 to the 15 allowed. |
| 500 | `python:S5713` | MINOR | CODE_SMELL | OPEN | Remove this redundant Exception class; it derives from another which is already caught. |
| 500 | `python:S5713` | MINOR | CODE_SMELL | OPEN | Remove this redundant Exception class; it derives from another which is already caught. |
| 514 | `python:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 55 to the 15 allowed. |
| 845 | `python:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 88 to the 15 allowed. |
| 958 | `python:S3516` | BLOCKER | CODE_SMELL | OPEN | Refactor this method to not always return the same value. |

### `android/app/src/main/java/com/wbeam/hud/RuntimeHudComputation.java` (6 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 85 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 12 parameters, which is greater than 7 authorized. |
| 85 | `java:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this method to reduce its Cognitive Complexity from 27 to the 15 allowed. |
| 133 | `java:S3358` | MAJOR | CODE_SMELL | OPEN | Extract this nested ternary operation into an independent statement. |
| 179 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 8 parameters, which is greater than 7 authorized. |
| 203 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 11 parameters, which is greater than 7 authorized. |
| 226 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 26 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/stream/FramedVideoDecodeLoop.java` (6 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 66 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Constructor has 28 parameters, which is greater than 7 authorized. |
| 126 | `java:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this method to reduce its Cognitive Complexity from 147 to the 15 allowed. |
| 126 | `java:S6541` | INFO | CODE_SMELL | OPEN | A "Brain Method" was detected. Refactor it to reduce at least one of the following metrics: LOC from 370 to 64, Complexity from 72 to 14, Nesting Level from 5 to 2, Number of Variables from 86 to 6. |
| 195 | `java:S3358` | MAJOR | CODE_SMELL | OPEN | Extract this nested ternary operation into an independent statement. |
| 237 | `java:S135` | MINOR | CODE_SMELL | OPEN | Reduce the total number of break and continue statements in this loop to use at most one. |
| 280 | `java:S1854` | MAJOR | CODE_SMELL | OPEN | Remove this useless assignment to local variable "expectedSeq". |

### `android/app/src/main/java/com/wbeam/ui/MainActivitySurfaceSetup.java` (6 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 28 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make preview a static final constant or non-public and provide accessors if needed. |
| 29 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make onSurfaceCreated a static final constant or non-public and provide accessors if needed. |
| 30 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make onSurfaceChanged a static final constant or non-public and provide accessors if needed. |
| 31 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make onSurfaceDestroyed a static final constant or non-public and provide accessors if needed. |
| 32 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make isCursorOverlayEnabled a static final constant or non-public and provide accessors if needed. |
| 33 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make onCursorOverlayMotion a static final constant or non-public and provide accessors if needed. |

### `android/app/src/main/java/com/wbeam/ui/state/MainStatusState.java` (6 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 6 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make uiState a static final constant or non-public and provide accessors if needed. |
| 7 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make uiInfo a static final constant or non-public and provide accessors if needed. |
| 8 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make uiBps a static final constant or non-public and provide accessors if needed. |
| 9 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make criticalErrorInfo a static final constant or non-public and provide accessors if needed. |
| 10 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make criticalErrorLogAtMs a static final constant or non-public and provide accessors if needed. |
| 11 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make statsLine a static final constant or non-public and provide accessors if needed. |

### `host/rust/crates/wbeamd-core/src/lib.rs` (6 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 193 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 22 to the 15 allowed. |
| 265 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 28 to the 15 allowed. |
| 955 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 51 to the 15 allowed. |
| 1278 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 73 to the 15 allowed. |
| 1720 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 24 to the 15 allowed. |
| 1812 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 18 to the 15 allowed. |

### `android/app/src/main/java/com/wbeam/hud/ResourceUsageTracker.java` (5 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 61 | `java:S3358` | MAJOR | CODE_SMELL | OPEN | Extract this nested ternary operation into an independent statement. |
| 63 | `java:S3358` | MAJOR | CODE_SMELL | OPEN | Extract this nested ternary operation into an independent statement. |
| 64 | `java:S3358` | MAJOR | CODE_SMELL | OPEN | Extract this nested ternary operation into an independent statement. |
| 71 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "</span><div class='spark'>" 3 times. |
| 73 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "</div></div>" 3 times. |

### `android/app/src/main/java/com/wbeam/ui/MainActivityUiBinder.java` (5 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 70 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 9 parameters, which is greater than 7 authorized. |
| 98 | `java:S1186` | CRITICAL | CODE_SMELL | OPEN | Add a nested comment explaining why this method is empty, throw an UnsupportedOperationException or complete the implementation. |
| 108 | `java:S1186` | CRITICAL | CODE_SMELL | OPEN | Add a nested comment explaining why this method is empty, throw an UnsupportedOperationException or complete the implementation. |
| 133 | `java:S1186` | CRITICAL | CODE_SMELL | OPEN | Add a nested comment explaining why this method is empty, throw an UnsupportedOperationException or complete the implementation. |
| 137 | `java:S1186` | CRITICAL | CODE_SMELL | OPEN | Add a nested comment explaining why this method is empty, throw an UnsupportedOperationException or complete the implementation. |

### `android/app/src/main/java/com/wbeam/ui/MainInitializationCoordinator.java` (5 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 50 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Constructor has 14 parameters, which is greater than 7 authorized. |
| 86 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 21 parameters, which is greater than 7 authorized. |
| 89 | `java:S1172` | MAJOR | CODE_SMELL | OPEN | Remove these unused method parameters "ignoredStartupBuildVersionText", "ignoredProfileSpinner", "ignoredEncoderSpinner", "ignoredCursorSpinner", "ignoredResolutionSeek", "ignoredFpsSeek", "ignoredBitrateSeek". |
| 189 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Constructor has 10 parameters, which is greater than 7 authorized. |
| 214 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 10 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/ui/MainUiControlsCoordinator.java` (5 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 17 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 19 parameters, which is greater than 7 authorized. |
| 63 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 8 parameters, which is greater than 7 authorized. |
| 93 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 11 parameters, which is greater than 7 authorized. |
| 240 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 9 parameters, which is greater than 7 authorized. |
| 298 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 12 parameters, which is greater than 7 authorized. |

### `host/rust/crates/wbeamd-tuner/src/main.rs` (5 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 649 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 22 to the 15 allowed. |
| 1023 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 46 to the 15 allowed. |
| 1533 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 60 to the 15 allowed. |
| 2090 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 17 to the 15 allowed. |
| 2693 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 18 to the 15 allowed. |

### `android/app/src/main/java/com/wbeam/startup/StartupOverlayCoordinator.java` (4 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 17 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make startupBeganAtMs a static final constant or non-public and provide accessors if needed. |
| 18 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make controlRetryCount a static final constant or non-public and provide accessors if needed. |
| 19 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make startupDismissed a static final constant or non-public and provide accessors if needed. |
| 20 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make preflightComplete a static final constant or non-public and provide accessors if needed. |

### `android/app/src/main/java/com/wbeam/startup/StartupOverlayStateSync.java` (4 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 5 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make startupBeganAtMs a static final constant or non-public and provide accessors if needed. |
| 6 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make controlRetryCount a static final constant or non-public and provide accessors if needed. |
| 7 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make startupDismissed a static final constant or non-public and provide accessors if needed. |
| 8 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make preflightComplete a static final constant or non-public and provide accessors if needed. |

### `android/app/src/main/java/com/wbeam/ui/MainActivitySettingsPresenter.java` (4 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 12 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 8 parameters, which is greater than 7 authorized. |
| 28 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 15 parameters, which is greater than 7 authorized. |
| 75 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 11 parameters, which is greater than 7 authorized. |
| 105 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 13 parameters, which is greater than 7 authorized. |

### `desktop/apps/desktop-tauri/src-tauri/src/main.rs` (4 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 115 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 29 to the 15 allowed. |
| 347 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 32 to the 15 allowed. |
| 539 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 27 to the 15 allowed. |
| 818 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 18 to the 15 allowed. |

### `host/rust/crates/wbeamd-core/src/infra/x11_real_output.rs` (4 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 63 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 18 to the 15 allowed. |
| 355 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 32 to the 15 allowed. |
| 597 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 24 to the 15 allowed. |
| 1391 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 55 to the 15 allowed. |

### `host/scripts/probe_host.py` (4 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 90 | `python:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "name:" 3 times. |
| 175 | `python:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 97 to the 15 allowed. |
| 282 | `python:S1481` | MINOR | CODE_SMELL | OPEN | Replace the unused local variable "disconnected_outputs" with "_". |
| 359 | `python:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "powershell.exe" 4 times. |

### `android/app/src/main/java/com/wbeam/stream/H264TcpPlayer.java` (3 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 194 | `java:S3358` | MAJOR | CODE_SMELL | OPEN | Extract this nested ternary operation into an independent statement. |
| 201 | `java:S3358` | MAJOR | CODE_SMELL | OPEN | Extract this nested ternary operation into an independent statement. |
| 358 | `java:S108` | MAJOR | CODE_SMELL | OPEN | Remove this block of code, fill it in, or add a comment explaining why it is empty. |

### `android/app/src/main/java/com/wbeam/stream/StreamNalUtils.java` (3 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 24 | `java:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this method to reduce its Cognitive Complexity from 19 to the 15 allowed. |
| 27 | `java:S135` | MINOR | CODE_SMELL | OPEN | Reduce the total number of break and continue statements in this loop to use at most one. |
| 48 | `java:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this method to reduce its Cognitive Complexity from 25 to the 15 allowed. |

### `android/app/src/main/java/com/wbeam/ui/MainDaemonRuntimeInputFactory.java` (3 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 14 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 11 parameters, which is greater than 7 authorized. |
| 43 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 10 parameters, which is greater than 7 authorized. |
| 70 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 13 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/hud/HudOverlayDisplay.java` (2 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 9 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make mode a static final constant or non-public and provide accessors if needed. |
| 10 | `java:S1104` | MINOR | CODE_SMELL | OPEN | Make lastWebHtml a static final constant or non-public and provide accessors if needed. |

### `android/app/src/main/java/com/wbeam/hud/RuntimeHudFallbackFormatter.java` (2 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 11 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 19 parameters, which is greater than 7 authorized. |
| 32 | `java:S3457` | MAJOR | CODE_SMELL | OPEN | %n should be used in place of \n to produce the platform-specific line separator. |

### `android/app/src/main/java/com/wbeam/hud/RuntimeHudOverlayPipeline.java` (2 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 11 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 42 parameters, which is greater than 7 authorized. |
| 105 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "runtime" 3 times. |

### `android/app/src/main/java/com/wbeam/hud/RuntimeHudTrendComposer.java` (2 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 7 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 14 parameters, which is greater than 7 authorized. |
| 27 | `java:S2184` | MINOR | BUG | OPEN | Cast one of the operands of this addition operation to a "double". |

### `android/app/src/main/java/com/wbeam/stream/DecoderCapabilityInspector.java` (2 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 38 | `java:S108` | MAJOR | CODE_SMELL | OPEN | Remove this block of code, fill it in, or add a comment explaining why it is empty. |
| 43 | `java:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this method to reduce its Cognitive Complexity from 30 to the 15 allowed. |

### `android/app/src/main/java/com/wbeam/stream/LiveViewPlaybackCoordinator.java` (2 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 43 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 12 parameters, which is greater than 7 authorized. |
| 153 | `java:S108` | MAJOR | CODE_SMELL | OPEN | Remove this block of code, fill it in, or add a comment explaining why it is empty. |

### `android/app/src/main/java/com/wbeam/stream/MediaCodecBridge.java` (2 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 93 | `java:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this method to reduce its Cognitive Complexity from 19 to the 15 allowed. |
| 105 | `java:S135` | MINOR | CODE_SMELL | OPEN | Reduce the total number of break and continue statements in this loop to use at most one. |

### `android/app/src/main/java/com/wbeam/stream/PngSurfaceRenderer.java` (2 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 41 | `java:S108` | MAJOR | CODE_SMELL | OPEN | Remove this block of code, fill it in, or add a comment explaining why it is empty. |
| 55 | `java:S1845` | BLOCKER | CODE_SMELL | OPEN | Rename method "rendered" to prevent any misunderstanding/clash with field "rendered". |

### `android/app/src/main/java/com/wbeam/stream/StreamReconnectLoop.java` (2 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 32 | `java:S112` | MAJOR | CODE_SMELL | OPEN | Replace generic exceptions with specific library exceptions or a custom exception. |
| 47 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Constructor has 10 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/stream/WbtpProtocol.java` (2 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 25 | `java:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this method to reduce its Cognitive Complexity from 16 to the 15 allowed. |
| 56 | `java:S1659` | MINOR | CODE_SMELL | OPEN | Declare "height" and all following declarations on a separate line. |

### `android/app/src/main/java/com/wbeam/ui/MainActivityDebugInfoFormatter.java` (2 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 9 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 8 parameters, which is greater than 7 authorized. |
| 23 | `java:S3457` | MAJOR | CODE_SMELL | OPEN | %n should be used in place of \n to produce the platform-specific line separator. |

### `android/app/src/main/java/com/wbeam/ui/MainActivitySimpleMenuCoordinator.java` (2 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 91 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 9 parameters, which is greater than 7 authorized. |
| 149 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 12 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/ui/MainSessionControlCoordinator.java` (2 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 25 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 8 parameters, which is greater than 7 authorized. |
| 62 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 9 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/ui/MainStatusCoordinator.java` (2 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 18 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 8 parameters, which is greater than 7 authorized. |
| 50 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 10 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/ui/MainViewBehaviorCoordinator.java` (2 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 64 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 22 parameters, which is greater than 7 authorized. |
| 114 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 10 parameters, which is greater than 7 authorized. |

### `desktop/apps/desktop-tauri/src-tauri/src/adb.rs` (2 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 605 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 22 to the 15 allowed. |
| 795 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 35 to the 15 allowed. |

### `host/rust/crates/wbeamd-core/src/infra/x11_extend.rs` (2 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 12 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 19 to the 15 allowed. |
| 166 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 24 to the 15 allowed. |

### `host/rust/crates/wbeamd-server/src/server/session_registry.rs` (2 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 66 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 49 to the 15 allowed. |
| 227 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 37 to the 15 allowed. |

### `host/scripts/x11_virtual_smoke.py` (2 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 65 | `python:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 18 to the 15 allowed. |
| 113 | `python:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 44 to the 15 allowed. |

### `android/app/src/main/java/com/wbeam/ClientMetricsSample.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 29 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Constructor has 16 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/StreamService.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 65 | `java:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this method to reduce its Cognitive Complexity from 21 to the 15 allowed. |

### `android/app/src/main/java/com/wbeam/api/StatusPollerCallbacksFactory.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 7 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 11 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/hud/MainHudInputFactory.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 7 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 39 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/hud/RuntimeHudUpdateState.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 43 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Constructor has 32 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/hud/RuntimeTrendGridRenderer.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 11 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 11 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/startup/MainStartupInputFactory.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 14 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 35 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/startup/StartupOverlayHookBuilder.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 11 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 31 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/startup/StartupOverlayHooksFactory.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 32 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 9 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/stream/FramedPngLoop.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 43 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Constructor has 14 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/stream/MainStreamCoordinator.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 29 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Constructor has 13 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/stream/SessionUiBridge.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 49 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Constructor has 9 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/stream/SessionUiBridgeFactory.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 29 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 9 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/stream/StreamBufferMath.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 27 | `java:S1905` | MINOR | CODE_SMELL | OPEN | Remove this unnecessary cast to "int". |

### `android/app/src/main/java/com/wbeam/stream/StreamSessionController.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 87 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "connecting" 3 times. |

### `android/app/src/main/java/com/wbeam/stream/VideoTestCallbacksFactory.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 64 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 13 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/ui/BuildVariantUiCoordinator.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 21 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 10 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/ui/HostApiFailureNotifier.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 22 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 9 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/ui/HostHintPresenter.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 9 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 11 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/ui/IntraOnlyButtonController.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 23 | `java:S3358` | MAJOR | CODE_SMELL | OPEN | Extract this nested ternary operation into an independent statement. |

### `android/app/src/main/java/com/wbeam/ui/MainActivityButtonsSetup.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 26 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 22 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/ui/MainActivityLifecycleCleaner.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 28 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 12 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/ui/MainActivityRuntimeStateView.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 32 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 11 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/ui/MainActivitySettingsInitializer.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 17 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 18 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/ui/MainActivitySpinnersSetup.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 16 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 9 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/ui/MainActivityStatusPresenter.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 28 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 12 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/ui/SettingsPayloadBuilder.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 32 | `java:S108` | MAJOR | CODE_SMELL | OPEN | Remove this block of code, fill it in, or add a comment explaining why it is empty. |

### `android/app/src/main/java/com/wbeam/ui/SimpleMenuUi.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 12 | `java:S1192` | CRITICAL | CODE_SMELL | OPEN | Define a constant instead of duplicating this literal "raw-png" 5 times. |

### `android/app/src/main/java/com/wbeam/ui/StatusTextFormatter.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 11 | `java:S107` | MAJOR | CODE_SMELL | OPEN | Method has 13 parameters, which is greater than 7 authorized. |

### `android/app/src/main/java/com/wbeam/ui/StreamConfigResolver.java` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 23 | `java:S1172` | MAJOR | CODE_SMELL | OPEN | Remove this unused method parameter "profile". |

### `desktop/apps/desktop-tauri/src-tauri/src/service.rs` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 137 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 17 to the 15 allowed. |

### `desktop/apps/desktop-tauri/src/app-hud.ts` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 44 | `typescript:S5843` | MAJOR | CODE_SMELL | OPEN | Simplify this regular expression to reduce its complexity from 22 to the 20 allowed. |

### `desktop/apps/desktop-tauri/src/managers/hostApiManager.ts` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 14 | `typescript:S6551` | MINOR | CODE_SMELL | OPEN | 'err ?? "unknown error"' will use Object's default stringification format ('[object Object]') when stringified. |

### `host/daemon/wbeamd.py` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 373 | `python:S5890` | MAJOR | CODE_SMELL | OPEN | Replace the type hint "WBeamDaemon" with "Optional[WBeamDaemon]" or don't assign "None" to "daemon_ref" |

### `host/rust/crates/wbeamd-api/src/lib.rs` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 459 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 26 to the 15 allowed. |

### `host/rust/crates/wbeamd-core/src/domain/policy.rs` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 108 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 21 to the 15 allowed. |

### `host/rust/crates/wbeamd-core/src/infra/adb.rs` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 46 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 18 to the 15 allowed. |

### `host/rust/crates/wbeamd-core/src/infra/x11_monitor_object.rs` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 216 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 24 to the 15 allowed. |

### `host/rust/crates/wbeamd-server/src/main.rs` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 240 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 18 to the 15 allowed. |

### `host/rust/crates/wbeamd-streamer/src/capture/backend/evdi.rs` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 89 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 38 to the 15 allowed. |

### `host/rust/crates/wbeamd-streamer/src/pipeline/builder.rs` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 27 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 115 to the 15 allowed. |

### `host/rust/crates/wbeamd-streamer/src/pipeline/profile.rs` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 13 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 19 to the 15 allowed. |

### `host/rust/crates/wbeamd-streamer/src/transport/sender.rs` (1 issues)

| Line | Rule | Severity | Type | Status | Message |
|---:|---|---|---|---|---|
| 58 | `rust:S3776` | CRITICAL | CODE_SMELL | OPEN | Refactor this function to reduce its Cognitive Complexity from 162 to the 15 allowed. |

## Rule summary

| Rule | Count |
|---|---:|
| `java:S1104` | 507 |
| `java:S107` | 66 |
| `rust:S3776` | 35 |
| `java:S1192` | 23 |
| `java:S108` | 12 |
| `java:S3776` | 12 |
| `java:S1068` | 9 |
| `java:S3358` | 9 |
| `java:S135` | 8 |
| `python:S3776` | 7 |
| `typescript:S3358` | 6 |
| `java:S1450` | 5 |
| `java:S1172` | 4 |
| `java:S1186` | 4 |
| `java:S1854` | 4 |
| `typescript:S6772` | 3 |
| `typescript:S6819` | 3 |
| `java:S1168` | 2 |
| `java:S3457` | 2 |
| `java:S6541` | 2 |
| `python:S1192` | 2 |
| `python:S5713` | 2 |
| `typescript:S3863` | 2 |
| `typescript:S7735` | 2 |
| `java:S112` | 1 |
| `java:S115` | 1 |
| `java:S1481` | 1 |
| `java:S1659` | 1 |
| `java:S1845` | 1 |
| `java:S1905` | 1 |
| `java:S2184` | 1 |
| `java:S2676` | 1 |
| `java:S3398` | 1 |
| `java:S4144` | 1 |
| `python:S1481` | 1 |
| `python:S3516` | 1 |
| `python:S5890` | 1 |
| `typescript:S3776` | 1 |
| `typescript:S4325` | 1 |
| `typescript:S5843` | 1 |
| `typescript:S6551` | 1 |
| `typescript:S6582` | 1 |
