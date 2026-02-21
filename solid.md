# WBeam SOLID Refactor Blueprint (SRP-First)

Status: draft for execution  
Scope: pełny refaktor architektury (wariant "3-5 tygodni"), ze szczególnym naciskiem na SRP + pipeline stage  
Audience: maintainerzy WBeam (host Rust + Android + tooling)

---

## 1. Cel dokumentu

Ten dokument jest planem pełnego refaktoru WBeam pod SOLID, z naciskiem na:

1. SRP jako zasada nr 1 (dużo małych klas/modułów o jednej odpowiedzialności).
2. Jawny, kompozycyjny pipeline (`Stage`) zamiast logiki „wszystko w jednym pliku/klasie”.
3. Rozdzielenie Control Plane od Media Plane.
4. Dodanie `Demo Screen` / benchmark matrix (9 presetów) jako repeatable test jakości.
5. Migrację bez big-bang: zachowanie działającego produktu przez cały czas.

Dokument zawiera:

1. Stan obecny projektu (techniczny snapshot).
2. Problemy architektoniczne i długi techniczne.
3. Docelową architekturę i kontrakty.
4. Plan wdrożenia etapami (3-5 tygodni).
5. Kryteria akceptacji i testy.

---

## 2. Snapshot stanu obecnego (repo + runtime)

## 2.1 Główne komponenty

1. Host control API (Rust, Axum): `host/rust/crates/wbeamd-server/src/main.rs`.
2. Host core orchestration (Rust): `host/rust/crates/wbeamd-core/src/lib.rs`.
3. Host streamer (Rust): `host/rust/crates/wbeamd-streamer/src/main.rs`.
4. Fallback streamer (Python): `host/scripts/stream_wayland_portal_h264.py`.
5. Legacy daemon (Python): `host/daemon/wbeamd.py`.
6. Android app: `android/app/src/main/java/com/wbeam/MainActivity.java`.
7. Android service/receiver: `android/app/src/main/java/com/wbeam/StreamService.java`, `android/app/src/main/java/com/wbeam/UsbAttachReceiver.java`.
8. Protocol crates (Rust): `protocol/rust/crates/wbtp-core`, `wbtp-sender`, `wbtp-receiver-null`.
9. CLI wrapper: `wbeam`.

## 2.2 Skala kodu (orientacyjnie)

1. `MainActivity.java`: ~3083 LOC (bardzo duża klasa).
2. `wbeamd-core/src/lib.rs`: ~1408 LOC (monolit domena+infra+proces).
3. `wbeamd-streamer/src/main.rs`: ~608 LOC (CLI+portal+gst+sender).
4. `wbeamd-server/src/main.rs`: ~262 LOC (HTTP + bootstrap).
5. `wbeamd-api/src/lib.rs`: ~404 LOC (DTO + walidacja + presety).
6. Python streamer: ~743 LOC.
7. Python daemon: ~547 LOC.

## 2.3 Obecny flow runtime (uproszczenie)

1. Android wysyła `/apply` + `/start` na host API przez ADB reverse.
2. `DaemonCore` startuje proces streamera.
3. Streamer pobiera portal screencast (Wayland/PipeWire), enkoduje H264, wysyła WBTP po TCP.
4. Android `H264TcpPlayer` czyta framed stream, dekoduje MediaCodec, renderuje SurfaceView.
5. Android publikuje client-metrics do hosta.
6. Host adaptuje parametry (poziomy degradacji/recovery).

## 2.4 Co już działa dobrze

1. API `/v1/*` i status/metrics są dostępne.
2. Framed transport WBTP działa end-to-end.
3. Jest watchdog/adaptation/no-present recovery.
4. Są skrypty debug (`./wbeam debug up`, `./wbeam logs live`).
5. Są podstawowe testy w Rust (core + protocol).

## 2.5 Obecne anti-patterny (najważniejsze)

1. Gigantyczna `MainActivity` łącząca UI, HTTP, decode loop, telemetry, preflight, test mode.
2. `DaemonCore` łączy zbyt wiele warstw: domena, procesy, ADB reverse, parsing logów, watchdog, persistence.
3. `wbeamd-streamer` ma sklejone odpowiedzialności: CLI parse, portal session, gst graph build, TCP sender loop.
4. Duplikacja źródeł prawdy konfiguracji (presets/cursor defaults) w kilku miejscach.
5. Dwie implementacje daemon/streamer (Rust+Python) utrudniają spójność.
6. Brak formalnej abstrakcji pipeline (etapy są implicit w kodzie).

---

## 3. Kluczowe cele architektoniczne (target)

1. Każdy komponent ma jedną odpowiedzialność i jasno zdefiniowany kontrakt.
2. Pipeline medialny jest jawny i składany z etapów (`Stage`) przez builder.
3. Możliwość przełączania źródła (`Portal` vs `DemoFile`) bez ruszania reszty pipeline.
4. Android UI i dekoder rozdzielone na osobne moduły.
5. Host control policy (state machine/adaptation) odseparowane od I/O.
6. Jedno źródło prawdy dla presetów i defaults.
7. Obserwowalność: wspólny `run_id/session_id/trace_id`, metryki per etap.

---

## 4. SOLID mapowane na WBeam

## 4.1 SRP (najważniejsze)

Każda klasa/moduł ma pojedynczy „powód do zmiany”.

Przykład docelowy:

1. `DaemonApiClient` zmienia się tylko, gdy zmienia się API hosta.
2. `DecoderWorker` zmienia się tylko, gdy zmienia się logika MediaCodec.
3. `PortalCaptureStage` zmienia się tylko, gdy zmienia się portal/pipewire capture.
4. `AdaptivePolicy` zmienia się tylko, gdy zmieniają się reguły adaptacji.

## 4.2 OCP

Dodajemy nowe stage/source/encoder bez modyfikacji istniejących klas core:

1. `SourceStage` -> `PortalSourceStage`, `DemoFileSourceStage`.
2. `EncodeStage` -> `NvencEncodeStage`, `OpenH264EncodeStage`, `X264EncodeStage`.

## 4.3 LSP

Każdy konkretny stage musi być zamienialny i spełniać kontrakt:

1. Każdy stage honoruje `start/process/stop`.
2. Błędy raportowane jednolicie przez `StageError`.

## 4.4 ISP

Interfejsy małe, tematyczne:

1. `MetricsSink` osobno od `LogSink`.
2. `ConfigStore` osobno od `PresetProvider`.
3. `FrameSource` osobno od `FrameTransport`.

## 4.5 DIP

Warstwa aplikacyjna zależy od abstrakcji, nie implementacji:

1. `StartStreamUseCase` zależy od `StreamProcessGateway`, nie od `tokio::process::Command`.
2. Android `StreamSessionController` zależy od `HostApi`, nie od OkHttp konkretów.

---

## 5. Docelowy podział bounded contexts

## 5.1 Host Control Plane (Rust)

Odpowiedzialność:

1. API HTTP.
2. lifecycle stream session.
3. policy/adaptation.
4. persistence config/state.

Brak odpowiedzialności:

1. decode/render.
2. szczegóły GStreamer graph w handlerach API.

## 5.2 Host Media Plane (Rust)

Odpowiedzialność:

1. capture -> process -> encode -> frame -> transport.
2. stage pipeline.
3. media telemetry.

Brak odpowiedzialności:

1. decyzje UX/auto-start z Android.

## 5.3 Android Client

Odpowiedzialność:

1. UI.
2. host control API interactions.
3. decode/render.
4. local telemetry.

Brak odpowiedzialności:

1. policy host-side adaptation.

## 5.4 Protocol/Soak Tools

Odpowiedzialność:

1. wire protocol contract.
2. sender/receiver tools do walidacji.

---

## 6. Główne problemy SRP i refaktory docelowe

## 6.1 Android (`MainActivity.java`)

### Obecny problem

1. `MainActivity` łączy: UI wiring, settings persistence, HTTP API, status polling, preflight FSM, live tests, bandwidth tests, decode pipeline, parser, queue policy, telemetry reporting.

### Docelowy podział

1. `ui/MainActivity`:
   1. tylko binding view + delegowanie eventów.
2. `ui/MainViewStateStore`:
   1. stan paneli, fullscreen, overlay, log visibility.
3. `settings/SettingsRepository`:
   1. SharedPreferences IO.
4. `settings/ProfileMapper`:
   1. mapowanie mode/quality -> config payload.
5. `api/HostApiClient`:
   1. `/status,/health,/metrics,/start,/stop,/apply,/client-metrics`.
6. `api/StatusPoller`:
   1. harmonogram poll + backoff + in-flight guard.
7. `stream/StreamSessionController`:
   1. start/stop/reconnect loop.
8. `stream/TcpFramedReader`:
   1. WBTP frame read + resync + payload management.
9. `stream/DecoderWorker`:
   1. queueNal/drain + no-present ladder.
10. `stream/RenderMetricsCollector`:
   1. fps/decode p50/p95/e2e + queue depth.
11. `telemetry/ClientMetricsReporter`:
   1. push co N ms do hosta.
12. `preflight/PreflightOrchestrator`:
   1. check matrix + result reasoning.
13. `demo/DemoBenchmarkController`:
   1. 9 presetów, przebieg testu, ranking.

## 6.2 Host Core (`wbeamd-core/src/lib.rs`)

### Obecny problem

1. Jeden plik obsługuje wszystko: domain state, start/stop/apply use-cases, process spawn, output parse, watchdog, adb reverse, persistence, adaptation policy, telemetry serialization.

### Docelowy podział

1. `domain/`:
   1. `DaemonStateMachine`
   2. `AdaptivePolicy`
   3. `NoPresentRecoveryPolicy`
   4. `ConfigModel` + validation wrappers
2. `application/`:
   1. `StartStreamUseCase`
   2. `StopStreamUseCase`
   3. `ApplyConfigUseCase`
   4. `IngestClientMetricsUseCase`
3. `infra/process/`:
   1. `StreamerProcessManager` (spawn/kill/supervise)
4. `infra/usb/`:
   1. `AdbReverseManager`
5. `infra/store/`:
   1. `RuntimeConfigStore` (json read/write)
6. `infra/telemetry/`:
   1. `TelemetryJsonlWriter`
7. `transport/http/`:
   1. endpoints i DTO mapping, bez logiki domenowej.

## 6.3 Host Streamer (`wbeamd-streamer/src/main.rs`)

### Obecny problem

1. `main.rs` łączy argument parser, portal session, gst builder, sender transport i kontrolę shutdown.

### Docelowy podział

1. `streamer_cli`:
   1. parse CLI -> `StreamRunConfig`.
2. `capture/portal`:
   1. portal session lifecycle.
3. `pipeline/stage`:
   1. stage graph + context.
4. `pipeline/builders`:
   1. `LiveDesktopPipelineBuilder`, `DemoFilePipelineBuilder`.
5. `transport/wbtp_sender`:
   1. sender loop + hello/session + pacing.
6. `metrics/`:
   1. counters + per-stage timing.

## 6.4 Config source of truth

### Obecny problem

1. Presety i defaulty występują w:
   1. `wbeamd-api/src/lib.rs`
   2. `host/rust/config/presets.toml`
   3. `host/daemon/wbeamd.py`
   4. Android defaults

### Target

1. Jedno źródło prawdy presetów:
   1. canonical `presets.json` (lub TOML) ładowany przez host.
2. Android pobiera presety z `/v1/presets` i nie hardcoduje wartości semantycznych.

## 6.5 Legacy Python path

### Obecny problem

1. Dwie ścieżki runtime (Rust i Python) zwiększają koszt utrzymania i ryzyko niespójności.

### Target

1. Python jako `legacy fallback` z wyraźną flagą i datą deprecacji.
2. Wszystkie nowe feature’y tylko w Rust path.

---

## 7. Pipeline Stage Architecture (docelowa)

## 7.1 Kontrakt etapu (Rust)

Proponowany kierunek:

```rust
pub trait Stage {
    fn name(&self) -> &'static str;
    fn start(&mut self, ctx: &mut PipelineContext) -> Result<(), StageError>;
    fn tick(&mut self, ctx: &mut PipelineContext) -> Result<StageSignal, StageError>;
    fn stop(&mut self, ctx: &mut PipelineContext) -> Result<(), StageError>;
}
```

`PipelineContext` zawiera:

1. aktywną konfigurację.
2. uchwyty do bus/events.
3. metryki etapowe.
4. cancellation token.
5. wspólne zasoby (`FrameBufferPool`, `SessionInfo`).

`StageSignal`:

1. `Continue`
2. `NeedRestart(reason)`
3. `EndOfStream`

## 7.2 Etapy dla trybu LIVE (Portal)

1. `PortalSessionStage`
2. `PipewireCaptureStage`
3. `VideoNormalizeStage` (convert/scale/rate)
4. `EncoderStage`
5. `ParserStage` (AU alignment)
6. `FramingStage` (WBTP header + hello/session)
7. `TransportStage` (TCP send latest-frame-wins)

## 7.3 Etapy dla trybu DEMO

1. `FileSourceStage` (lokalny plik, loop)
2. `DemuxDecodeStage` (jeśli trzeba)
3. `VideoNormalizeStage`
4. `EncoderStage`
5. `ParserStage`
6. `FramingStage`
7. `TransportStage`

## 7.4 Pipeline composer

`PipelineFactory` wybiera konfigurację:

1. `SourceKind::PortalLive`
2. `SourceKind::DemoFile`

Bez zmian w downstream stage.

## 7.5 Stage-level telemetry

Per stage:

1. `in_frames`, `out_frames`, `drops`, `p50_ms`, `p95_ms`, `max_ms`.
2. emit co 1s do host metrics.

---

## 8. Demo Screen i benchmark matrix 3x3 (9 presetów)

## 8.1 Cel

Powtarzalny benchmark jakości streamu niezależny od aktywności desktopu i kursora.

## 8.2 UX flow

1. Na głównym ekranie przycisk: `Demo Screen`.
2. Użytkownik uruchamia test 9 presetów.
3. Każdy preset trwa np. 12-15 sekund.
4. Po każdym teście snapshot metryk.
5. Na końcu ranking + rekomendacja.

## 8.3 Preset matrix (przykład)

Wariant A:

1. Wiersze: rozdzielczość `720p / 900p / 1080p`.
2. Kolumny: bitrate `10 / 20 / 30 Mbps`.
3. FPS stałe 60.

Wariant B:

1. Wiersze: `30 / 60 / 90 fps`.
2. Kolumny: bitrate `8 / 16 / 24 Mbps`.
3. Rozdzielczość stała 900p/1080p.

## 8.4 Score function (propozycja)

`score = w1*present_fps_norm + w2*(1-drops_norm) + w3*(1-e2e_norm) + w4*stability_norm`

Przykładowe wagi:

1. `w1 = 0.40` (płynność)
2. `w2 = 0.25` (dropy)
3. `w3 = 0.20` (latencja)
4. `w4 = 0.15` (stabilność fps)

## 8.5 Wymagania techniczne demo source

1. Lokalny plik testowy (w app assets lub host file path), bez sieci.
2. Stały materiał testowy (krótki loop 10-20s).
3. Ten sam materiał dla wszystkich presetów.
4. Reset counters między runami.

---

## 9. Proponowana docelowa struktura repo (incremental)

## 9.1 Host Rust

1. `host/rust/crates/wbeamd-server/`
   1. tylko bootstrap + routes wiring.
2. `host/rust/crates/wbeamd-transport-http/`
   1. handlers + DTO mapping.
3. `host/rust/crates/wbeamd-application/`
   1. use-cases.
4. `host/rust/crates/wbeamd-domain/`
   1. state machine + policies.
5. `host/rust/crates/wbeamd-infra/`
   1. process/adb/store/telemetry adapters.
6. `host/rust/crates/wbeamd-stream-pipeline/`
   1. stage contracts + common context.
7. `host/rust/crates/wbeamd-streamer/`
   1. CLI + composition stage pipelines.

## 9.2 Android

1. `android/app/src/main/java/com/wbeam/ui/...`
2. `android/app/src/main/java/com/wbeam/api/...`
3. `android/app/src/main/java/com/wbeam/stream/...`
4. `android/app/src/main/java/com/wbeam/telemetry/...`
5. `android/app/src/main/java/com/wbeam/preflight/...`
6. `android/app/src/main/java/com/wbeam/demo/...`

## 9.3 Protocol

1. `protocol/rust/crates/wbtp-core` zostaje canonical.
2. Host/Android muszą mieć jedną spec wire-format (bez driftu).

---

## 10. Szczegółowy plan refaktoru (3-5 tygodni)

## Etap 0 (1-2 dni): przygotowanie i guardrails

1. Zamrożenie API contract + response schema.
2. Dodanie CI jobs:
   1. `cargo test --workspace`
   2. `cargo clippy -- -D warnings` (host crates po kolei)
   3. Android compileDebug.
3. Baseline perf snapshot (10-min soak).

Deliverables:

1. `docs/baseline-metrics.md`
2. `docs/api-contract.md`

## Etap 1 (4-6 dni): SRP Host Control Plane

1. Wydzielenie domain policy z `wbeamd-core/src/lib.rs`.
2. Wydzielenie process manager + adb reverse + runtime store.
3. `DaemonCore` redukcja do koordynatora use-case.

Deliverables:

1. nowe moduły `domain/application/infra`.
2. testy policy na nowych modułach.

## Etap 2 (5-7 dni): Stage Pipeline w streamerze

1. Wprowadzenie kontraktu `Stage` + `PipelineContext`.
2. Przeniesienie live pipeline do stage builder.
3. Utrzymanie backward compatibility flagami.

Deliverables:

1. `LiveDesktopPipelineBuilder`.
2. stage-level telemetry.

## Etap 3 (5-7 dni): Android SRP decomposition

1. `HostApiClient`, `StatusPoller`, `SettingsRepository`.
2. `StreamSessionController`, `TcpFramedReader`, `DecoderWorker`.
3. `MainActivity` tylko UI orchestrator.

Deliverables:

1. `MainActivity` redukcja do <800 LOC (target orientacyjny).
2. zachowana funkcjonalność HUD/preflight/start/stop.

## Etap 4 (4-6 dni): Demo Screen 9 presetów

1. Dodanie source `DemoFile` w host streamer pipeline.
2. Android `DemoBenchmarkController` + ekran wyników.
3. Scoring + rekomendacja.

Deliverables:

1. benchmark matrix 3x3.
2. export wyników JSON/CSV.

## Etap 5 (2-4 dni): cleanup i deprecacje

1. Oznaczenie Python daemon jako legacy.
2. Usunięcie duplikacji preset defaults.
3. Dokumentacja architektury + runbook.

Deliverables:

1. `docs/architecture-v2.md`
2. `docs/demo-benchmark.md`

---

## 11. Work Breakdown (taski implementacyjne)

## 11.1 Host Domain/Application

1. `T-H-001` wydzielić `AdaptivePolicy`.
2. `T-H-002` wydzielić `NoPresentRecoveryPolicy`.
3. `T-H-003` wydzielić `DaemonStateMachine`.
4. `T-H-004` use-case `StartStreamUseCase`.
5. `T-H-005` use-case `ApplyConfigUseCase`.
6. `T-H-006` use-case `IngestClientMetricsUseCase`.

## 11.2 Host Infra

1. `T-H-101` `StreamerProcessManager`.
2. `T-H-102` `AdbReverseManager`.
3. `T-H-103` `RuntimeConfigStore`.
4. `T-H-104` `TelemetryWriter`.

## 11.3 Host Stream Pipeline

1. `T-S-001` kontrakt `Stage`.
2. `T-S-002` `PortalSessionStage`.
3. `T-S-003` `VideoNormalizeStage`.
4. `T-S-004` `EncoderStage`.
5. `T-S-005` `FramingTransportStage`.
6. `T-S-006` `DemoFileSourceStage`.

## 11.4 Android SRP

1. `T-A-001` `HostApiClient`.
2. `T-A-002` `StatusPoller`.
3. `T-A-003` `SettingsRepository`.
4. `T-A-004` `StreamSessionController`.
5. `T-A-005` `TcpFramedReader`.
6. `T-A-006` `DecoderWorker`.
7. `T-A-007` `MetricsAggregator`.
8. `T-A-008` `DemoBenchmarkController`.

## 11.5 Protocol

1. `T-P-001` doprecyzować spec handshake/hello/session.
2. `T-P-002` walidacja spójności header offsets host<->android.
3. `T-P-003` golden test vectors.

---

## 12. Kryteria akceptacji (Definition of Done)

## 12.1 Architektura

1. `MainActivity` nie zawiera decode loop ani HTTP request implementation.
2. `wbeamd-core` nie zawiera bezpośredniego spawn logic i adb reverse detali.
3. `wbeamd-streamer` buduje pipeline z kompozycji stage, nie z jednej funkcji-all-in-one.

## 12.2 Funkcjonalność

1. Start/stop/apply/reconnect działają jak wcześniej.
2. Kursor domyślnie widoczny (`embedded`) i nie regresuje.
3. Demo Screen wykonuje 9 preset run i pokazuje ranking.

## 12.3 Jakość

1. 30 min soak bez restart storm.
2. `fps_present` stabilniejszy vs baseline.
3. Spójne metryki host i android (trace/run/session correlation).

---

## 13. Strategia testów po refaktorze

## 13.1 Unit tests

1. Domain policies (adaptation/no-present transitions).
2. Stage contracts (start/tick/stop).
3. Android parser + queue policy (JVM tests where possible).

## 13.2 Integration tests

1. Host API lifecycle (`/apply,/start,/stop,/metrics`).
2. Streamer pipeline smoke (portal mock lub dry-run stage).
3. Demo pipeline smoke.

## 13.3 Soak tests

1. USB 30 min baseline profile.
2. 9 preset demo benchmark overnight.
3. reconnect stress (adb reverse reset).

---

## 14. Dodatkowe rejony aplikacji, gdzie refaktor jest wskazany

## 14.1 `StreamService.java`

1. Obecnie to równoległa, uproszczona ścieżka stream worker.
2. Ryzyko driftu logiki względem `MainActivity`.
3. Decyzja:
   1. albo usunąć jako legacy,
   2. albo przepisać jako wrapper używający tych samych klas `stream/*`.

## 14.2 `UsbAttachReceiver.java`

1. Teraz bez policy/throttling.
2. Dodać `LaunchPolicy`:
   1. debounce USB events,
   2. warunki launch (czy app już foreground).

## 14.3 `wbeam` shell CLI

1. Dobry kierunek jako single entrypoint.
2. Dodać komendy:
   1. `./wbeam doctor` (health checks)
   2. `./wbeam benchmark demo` (9 preset run trigger)
   3. `./wbeam collect bundle` (logs + metrics snapshot)

## 14.4 Legacy Python daemon

1. Oznaczyć jako deprecated.
2. Brak nowych feature’ów.
3. Usunąć po stabilizacji Rust path.

## 14.5 Config files

1. `host/rust/config/presets.toml` i `runtime_state.json` obecnie niespójne z nowym default cursor.
2. Po refaktorze:
   1. jedno canonical source + migracja runtime_state.

---

## 15. Ryzyka i mitigacje

## 15.1 Ryzyko: regresja stream stability

Mitigacja:

1. feature flags per stage path.
2. parallel run old/new pipeline (shadow metrics) na dev.

## 15.2 Ryzyko: zbyt długi freeze development

Mitigacja:

1. małe PRy pionowe (vertical slices).
2. każde PR kończy się działającym runtime.

## 15.3 Ryzyko: drift Android/Host protocol

Mitigacja:

1. golden vectors z `wbtp-core`.
2. contract tests per release.

## 15.4 Ryzyko: over-engineering

Mitigacja:

1. SRP first, minimal abstractions first pass.
2. zero framework rewrite.

---

## 16. Plan PR-ów (proponowany)

1. PR1: Host domain split (`AdaptivePolicy`, `StateMachine`).
2. PR2: Host infra split (`ProcessManager`, `ConfigStore`, `AdbReverseManager`).
3. PR3: Streamer stage core + live builder.
4. PR4: Android API layer extraction (`HostApiClient`, `StatusPoller`).
5. PR5: Android stream extraction (`TcpFramedReader`, `DecoderWorker`).
6. PR6: Demo source stage + Android DemoScreen benchmark matrix.
7. PR7: config unification + legacy cleanup + docs final.

---

## 17. Coding conventions na czas refaktoru

1. Każda nowa klasa musi mieć jednozdaniowy „single responsibility statement”.
2. Maksymalna długość klasy:
   1. Android: preferencyjnie <300 LOC.
   2. Rust module: preferencyjnie <400 LOC.
3. Każda warstwa ma swój namespace/package.
4. Brak wywołań infra z UI/domain bez interfejsu.
5. Każda zmiana krytyczna stream path:
   1. test,
   2. metryka,
   3. log reason code.

---

## 18. Non-goals (żeby nie rozmyć scope)

1. Brak migracji na Kotlin/Compose w tym projekcie refaktoru.
2. Brak zmiany technologii transportu (np. WebRTC) w tym etapie.
3. Brak redesignu UI pod estetykę; fokus na architekturę i stabilność.

---

## 19. Executive summary

1. Aktualny kod działa, ale ma duży dług SRP (szczególnie `MainActivity` i `wbeamd-core`).
2. Pipeline stage abstraction jest właściwym kierunkiem i dobrze pasuje do Twojej intuicji `processing pipeline <t1,t2,t3>`.
3. Refaktor 3-5 tygodni jest realistyczny przy iteracyjnym wdrożeniu.
4. Największa wartość biznesowa po refaktorze:
   1. szybsze iteracje,
   2. mniej regresji,
   3. repeatable benchmark (`Demo Screen`) do wyboru najlepszych presetów przez użytkownika.

