# WBeam Trainer v2 - Update (pełny opis)

Data: 2026-03-10  
Zakres: konsolidacja ustaleń funkcjonalnych i technicznych dla pierwszej kompletnej wersji aplikacji `Trainer` (desktop Tauri + backend + datasets + autotune).

---

## 1. Kontekst i problem

Dotychczasowy trening był częściowo uruchamiany przez skrypty/TUI i nie dawał pełnego, spójnego modelu danych do:

1. analizy po runie,
2. deterministycznego przeliczania wyniku końcowego,
3. porównań między encoderami i profilami,
4. odtworzenia pełnego przebiegu optymalizacji.

W praktyce oznaczało to, że:

1. część decyzji treningu była słabo wyjaśnialna,
2. brakowało kompletnej telemetrii per trial,
3. nie było osobnego widoku datasetów,
4. nie było pełnego sterowania algorytmem z GUI,
5. parametry encodera nie były traktowane jako pełna przestrzeń genetyczna.

---

## 2. Cel biznesowo-techniczny

Zbudować `Trainer v2` jako natywną aplikację Tauri, która:

1. uruchamia trening end-to-end,
2. pokazuje pełne dane na żywo (HUD),
3. zapisuje kompletny dataset każdego runu,
4. pozwala po treningu wykonać deterministyczne `Find Optimal Best`,
5. wspiera trening per encoder i multi-encoder,
6. utrzymuje pełną wyjaśnialność wyniku.

---

## 3. Run jako dataset (kluczowa zmiana)

Każdy run staje się trwałym datasetem, a nie tylko jednorazowym wynikiem.

Dataset runu musi zawierać:

1. konfigurację wejściową,
2. capability + preflight,
3. przestrzeń przeszukiwania,
4. wszystkie triale (także odrzucone),
5. telemetry timeseries,
6. event log optymalizacji,
7. wynik końcowy (global + per-encoder),
8. artefakty porównawcze i walidacyjne.

To umożliwia:

1. replay,
2. audyt decyzji,
3. porównania historyczne,
4. deterministyczny recompute rankingu.

---

## 4. Deterministyczny `Find Optimal Best`

Po zakończeniu treningu użytkownik ma kliknąć `Find Optimal Best` i:

1. użyć wyłącznie zapisanego datasetu,
2. nie uruchamiać nowego streamu/capture,
3. przeliczyć ranking tym samym modelem score,
4. otrzymać identyczny wynik dla identycznych danych i tej samej wersji algorytmu.

Wymagania:

1. wersjonowanie `scoring model`,
2. wersjonowanie `schema`,
3. zapis wszystkich wejść i clampów,
4. jawne reguły tie-break.

---

## 5. Encoder jako pełna przestrzeń genów

Potwierdzone założenie: trening ma przechodzić przez generacje także po opcjach encodera, a nie tylko po FPS/bitrate.

### 5.1 Tryby encoderowe

1. `single-encoder` - trening tylko dla jednego wybranego kodeka,
2. `multi-encoder` - trening dla wielu kodeków w jednym runie.

Obsługiwane kodeki:

1. `h264`,
2. `h265`,
3. `mjpeg/jpeg`,
4. `rawpng`.

### 5.2 Parametry (geny) per encoder

`h264`:

1. `preset`,
2. `tune`,
3. `profile`,
4. `level`,
5. `gop/keyint`,
6. `bframes`,
7. `ref`,
8. `rc-lookahead`,
9. `aq-mode`,
10. `qp/crf` lub `bitrate/vbv`,
11. `threads/slices`.

`h265`:

1. wszystko zbliżone do h264,
2. dodatkowo parametry typu `ctu`, `sao`, `deblock`, `rd` (zależnie od encodera).

`mjpeg/jpeg`:

1. `quality`,
2. `subsampling`,
3. `restart interval`,
4. bufory/kolejki.

`rawpng`:

1. `compression_level`,
2. `filter`,
3. `strategy`,
4. bufory/kolejki.

### 5.3 Geny globalne

1. `size`,
2. `fps`,
3. `bitrate_min_kbps`,
4. `bitrate_max_kbps`,
5. opcjonalnie `bitrate_step_kbps` / `bitrate_values`,
6. `cursor_mode`,
7. polityki kolejek/timeoutów.

---

## 6. Min/max bitrate jako obowiązkowe wejście

Nowy obowiązkowy kontrakt runu:

1. użytkownik ustawia `min bitrate` i `max bitrate`,
2. silnik generuje drabinę bitrate tylko w tym przedziale,
3. preflight może zawęzić zakres efektywny (clamp), ale z jawnym zapisem.

W dataset zapisujemy:

1. `requested_bitrate_min_kbps`,
2. `requested_bitrate_max_kbps`,
3. `effective_bitrate_min_kbps`,
4. `effective_bitrate_max_kbps`,
5. `tested_bitrate_values`.

---

## 7. Wynik końcowy runu

Każdy run ma produkować:

1. `global winner`,
2. `winner per encoder`,
3. `pareto frontier`,
4. `top alternatives`,
5. `reject reasons` per trial.

Nie może być sytuacji, że zostaje tylko pojedynczy wynik bez kontekstu.

---

## 8. UI Trainer - docelowe moduły

1. `Train` - pełna konfiguracja runu, w tym GA i bitrate range.
2. `Live HUD` - metryki realtime + progress etapów + event log.
3. `Profiles` - profile docelowe i metadata.
4. `Runs` - historia runów + szczegóły artefaktów.
5. `Compare` - porównanie profili/kandydatów.
6. `Devices` - ADB devices + capabilities + quick preflight.
7. `Validation` - rewalidacja profilu bez pełnego retrainu.
8. `Diagnostics` - health, ADB bench, integralność artefaktów.
9. `Datasets` - przegląd run-datasets + `Find Optimal Best`.

---

## 9. Wykresy (rozszerzone)

Wykresy muszą obsłużyć filtrowanie per encoder i zakres bitrate.

### 9.1 Live

1. FPS timeline: `sender/recv/decode`,
2. latency timeline: `p50/p95/p99`,
3. drops/timeouts timeline,
4. stage progress timeline.

### 9.2 Post-run / dataset analytics

1. scatter `bitrate vs score`,
2. scatter `bitrate vs latency`,
3. scatter `bitrate vs drop_rate`,
4. pareto `latency vs quality`,
5. heatmap `fps x bitrate` (per encoder),
6. boxplot stabilności top-kandydatów,
7. sustained degradation curve (np. 10-30 min),
8. trial funnel z przyczynami odrzucenia.

### 9.3 Specjalnie dla min/max bitrate

1. pionowe linie `min` i `max`,
2. marker optimum,
3. obszar realnie przebadany,
4. adnotacje clampów preflight.

---

## 10. Backend/API - wymagany zakres

Endpointy bazowe:

1. `POST /v1/trainer/preflight`,
2. `POST /v1/trainer/start`,
3. `POST /v1/trainer/stop`,
4. `GET /v1/trainer/runs`,
5. `GET /v1/trainer/runs/{run_id}`,
6. `GET /v1/trainer/runs/{run_id}/tail`,
7. `GET /v1/trainer/profiles`,
8. `GET /v1/trainer/profiles/{profile_name}`,
9. `GET /v1/trainer/devices`,
10. `GET /v1/trainer/diagnostics`.

Rozszerzenia docelowe:

1. `GET /v1/trainer/events/{run_id}` (live stream),
2. `GET /v1/trainer/datasets`,
3. `GET /v1/trainer/datasets/{run_id}`,
4. `POST /v1/trainer/datasets/{run_id}/find-optimal`,
5. `POST /v1/trainer/validate`.

---

## 11. Artefakty i struktura danych

Profil:

1. `config/training/profiles/<profile>/<profile>.json`,
2. `config/training/profiles/<profile>/parameters.json`,
3. `config/training/profiles/<profile>/preflight.json`.

Run:

1. `config/training/profiles/<profile>/runs/<run_id>/run.json`,
2. `.../parameters.json`,
3. `.../results.json`,
4. `.../trials.jsonl`,
5. `.../events.jsonl`,
6. `.../hud-timeseries.jsonl`,
7. `.../top-candidates.json`,
8. `.../logs.txt`.

Każdy plik ma mieć jawny `schema_version`.

---

## 12. Algorytm optymalizacji - kontrola z GUI

GUI musi pozwalać ustawić:

1. `generations`,
2. `population`,
3. `elite_count`,
4. `mutation_rate`,
5. `crossover_rate`,
6. budżety etapów (`floor/ceiling/fine/sustained`),
7. gate thresholds,
8. search space per encoder.

Walidacje:

1. `elite_count < population`,
2. `min_bitrate <= max_bitrate`,
3. sensowne zakresy mutacji/crossover,
4. odrzucanie unsupported codec combos.

---

## 13. Definition of Done (pierwsza kompletna apka Trainer)

Za „100% pierwszej apki” uznajemy sytuację, gdy:

1. `trainer.sh` uruchamia natywne okno Tauri,
2. Live HUD pokazuje realne metryki (nie tylko statyczne),
3. użytkownik ustawia pełne parametry runu (GA + min/max bitrate + encoder mode),
4. każdy run zapisuje pełny dataset,
5. istnieje widok `Datasets`,
6. działa deterministyczne `Find Optimal Best`,
7. wynik końcowy zawiera global winner + winnerów per encoder + alternatywy,
8. wykresy pokazują zależności jakości/latencji/strat od bitrate i encodera,
9. profile i runy są w pełni przeglądalne i porównywalne z GUI.

---

## 14. Podsumowanie update

Najważniejsza zmiana koncepcyjna:

`Trainer` przestaje być „launcherem tuningu”, a staje się **platformą danych treningowych**:

1. training-first + dataset-first,
2. encoder-aware optimization,
3. deterministic decision replay,
4. pełna wyjaśnialność i porównywalność wyników.

To jest baza pod dalszy etap: lepsze profile produkcyjne i obiektywne porównywanie jakości pod konkretne urządzenia i przepustowość.

---

## 15. Dodatek techniczny (format Jira / Engineering Spec)

### 15.1 Kontekst techniczny

Zmiana dotyczy czterech głównych obszarów istniejącego systemu:

1. `src/apps/trainer-tauri` - aplikacja desktopowa (UI + orchestracja treningu),
2. `src/host/rust/crates/wbeamd-server` - API trenera i strumień eventów,
3. `src/domains/training/wizard.py` + `legacy_engine.py` - silnik i logika optymalizacji,
4. `config/training/...` - kontrakt artefaktów runu/profilu/datasetu.

Miejsca integracji z istniejącym systemem:

1. `trainer.sh` (launcher) -> przejście na natywny Tauri runtime,
2. endpointy `/v1/trainer/*` -> rozszerzenie o datasety i recompute,
3. profile runtime w desktop app -> aplikowanie wybranego profilu po treningu.

### 15.2 Task (Jira style)

Tytuł:

`TRN-001 - TrainerV2: dataset-first training, encoder-param genetic tuning, deterministic find-optimal`

Cel biznesowy/techniczny:

1. zwiększyć jakość i stabilność profili przez pełniejszą optymalizację,
2. zapewnić pełny audyt i replay wyników treningu,
3. umożliwić porównywanie encoderów i deterministyczny wybór najlepszego profilu.

Zakres zmian:

1. Tauri launcher + natywne okno trenera,
2. pełny model danych run/dataset,
3. pełny zestaw parametrów optimizera w GUI,
4. parametry encodera jako geny,
5. min/max bitrate jako obowiązkowy kontrakt i wymiar analityczny,
6. endpoint `find-optimal` na zapisanym datasetcie,
7. live HUD przez event stream.

Kryteria akceptacji:

1. `trainer.sh` uruchamia app Tauri bez ręcznego odpalania przeglądarki,
2. GUI pokazuje realtime dane treningu z API event stream,
3. GUI umożliwia ustawienie `generations`, `population`, `elite_count`, `mutation_rate`, `crossover_rate`,
4. GUI wymusza `bitrate_min_kbps` i `bitrate_max_kbps`,
5. każdy run tworzy kompletny dataset artefaktów,
6. endpoint `find-optimal` działa deterministycznie,
7. wynik końcowy zwraca `global winner` i `winner per encoder`,
8. wykresy pokazują wpływ bitrate i encodera.

### 15.3 Architektura rozwiązania

Nowe elementy:

1. `TrainerDatasetService` (backend) - odczyt/indeksacja datasetów runów,
2. `TrainerOptimizeService` (backend) - deterministyczny recompute rankingu,
3. `TrainerEventBus` (backend) - stream eventów runu (`SSE`),
4. `EncoderSearchSpaceBuilder` (engine) - budowa przestrzeni per encoder,
5. `BitrateRangePolicy` (engine) - clamp i walidacja min/max bitrate,
6. `DatasetsViewModel` (frontend) - widok datasetów i akcja `Find Optimal Best`.

Integracja z obecnym systemem:

1. GUI wywołuje API `/v1/trainer/start` z rozszerzonym payload,
2. engine publikuje eventy triali i metryki do `events.jsonl` + stream API,
3. backend zapisuje i indeksuje run artifacts pod profilem,
4. GUI czyta run/profiles/datasets i rysuje wykresy.

### 15.4 Przepływ danych i logika

1. Użytkownik uruchamia run w `Train`.
2. API waliduje payload (w tym bitrate min/max i constraints GA).
3. Silnik wykonuje preflight i buduje effective search space.
4. Triale uruchamiane są generacyjnie; eventy idą na żywo do HUD.
5. Po zakończeniu runu zapisywany jest dataset + ranking końcowy.
6. Użytkownik otwiera `Datasets` i klika `Find Optimal Best`.
7. Backend ładuje dataset i przelicza ranking bez nowego streamu.
8. Wynik recompute zapisywany jest jako nowy rekord decyzji.

### 15.5 Struktura modułów / plików (proponowana)

1. `src/apps/trainer-tauri/src/features/train/*`
2. `src/apps/trainer-tauri/src/features/hud/*`
3. `src/apps/trainer-tauri/src/features/datasets/*`
4. `src/apps/trainer-tauri/src/features/compare/*`
5. `src/host/rust/crates/wbeamd-server/src/trainer/datasets.rs`
6. `src/host/rust/crates/wbeamd-server/src/trainer/optimize.rs`
7. `src/host/rust/crates/wbeamd-server/src/trainer/events.rs`
8. `src/domains/training/encoder_space.py`
9. `src/domains/training/bitrate_policy.py`
10. `src/domains/training/scoring.py`

### 15.6 Interfejsy i kontrakty

Kontrakt start runu (request):

```json
{
  "serial": "HVA6PKNT",
  "profile_name": "baseline",
  "mode": "quality",
  "encoder_mode": "multi",
  "encoders": ["h264", "h265", "mjpeg", "rawpng"],
  "generations": 6,
  "population": 24,
  "elite_count": 6,
  "mutation_rate": 0.34,
  "crossover_rate": 0.6,
  "bitrate_min_kbps": 20000,
  "bitrate_max_kbps": 200000,
  "fps_values": [60, 72, 90, 120],
  "size_values": ["1280x800", "2000x1200"]
}
```

Kontrakt `find-optimal`:

```json
{
  "run_id": "run-20260310-0001",
  "scoring_mode": "quality",
  "scoring_version": "v2.1",
  "tie_break": "latency_then_stability"
}
```

Kontrakt odpowiedzi:

```json
{
  "ok": true,
  "run_id": "run-20260310-0001",
  "global_winner": {"trial_id": "t42", "encoder": "h265", "score": 89.21},
  "per_encoder_winner": [
    {"encoder": "h264", "trial_id": "t31", "score": 86.11},
    {"encoder": "h265", "trial_id": "t42", "score": 89.21}
  ],
  "scoring_version": "v2.1",
  "deterministic_hash": "sha256:..."
}
```

### 15.7 Struktury danych (minimalne)

```text
RunMeta
- run_id
- profile_name
- serial
- started_at
- finished_at
- config_hash

TrialRecord
- trial_id
- generation
- encoder
- encoder_params
- global_params
- score
- reject_reason

TimeseriesPoint
- ts_ms
- sender_fps
- recv_fps
- decode_fps
- latency_p95
- drop_rate
```

### 15.8 API i komunikacja komponentów

Wymagane endpointy dodatkowe:

1. `GET /v1/trainer/events/{run_id}` - `text/event-stream`,
2. `GET /v1/trainer/datasets`,
3. `GET /v1/trainer/datasets/{run_id}`,
4. `POST /v1/trainer/datasets/{run_id}/find-optimal`,
5. `GET /v1/trainer/datasets/{run_id}/charts`.

### 15.9 Przykładowe fragmenty implementacji

Inicjalizacja backend service (pseudo-Rust):

```rust
pub struct TrainerDatasetService {
    root: PathBuf,
}

impl TrainerDatasetService {
    pub fn new(root: PathBuf) -> Self { Self { root } }
    pub fn load_run(&self, run_id: &str) -> Result<RunDataset, LoadError> { ... }
}
```

Główna logika `find-optimal` (pseudo-Rust):

```rust
fn find_optimal(dataset: &RunDataset, cfg: &ScoringConfig) -> OptimalResult {
    let mut scored = dataset.trials
        .iter()
        .filter(|t| t.reject_reason.is_none())
        .map(|t| (t, score_trial(t, cfg)))
        .collect::<Vec<_>>();
    scored.sort_by(score_desc_then_tiebreak(cfg.tie_break));
    build_result(scored, cfg)
}
```

Integracja z istniejącym start flow (pseudo-Python):

```python
def start_training(req):
    validate_request(req)
    space = build_encoder_space(req.encoders, req.bitrate_min_kbps, req.bitrate_max_kbps)
    for generation in range(req.generations):
        candidates = evolve_population(space, generation, req.population, req.elite_count)
        run_generation(candidates)
    persist_dataset_and_summary()
```

### 15.10 Pseudo-kod kluczowego flow

```text
TRAIN_RUN():
  validate(input)
  preflight = run_preflight(device)
  space = derive_space(input, preflight)
  for gen in 1..N:
    pop = make_population(space, gen)
    for candidate in pop:
      apply(candidate)
      sample_metrics()
      score()
      emit_event()
      persist_trial()
  finalize_run()
  publish_summary()

FIND_OPTIMAL(run_id):
  data = load_dataset(run_id)
  validate_integrity(data)
  ranked = deterministic_rank(data, scoring_config)
  persist_recompute_result(ranked)
  return ranked
```

### 15.11 Edge cases i obsługa błędów

1. Brak urządzenia ADB -> błąd walidacji + komunikat UI + brak startu runu.
2. `min_bitrate > max_bitrate` -> hard validation error.
3. Unsupported encoder na danym API/modelu -> auto-disable + warning + zapis powodu.
4. Brak metryk podczas triala -> trial oznaczony `invalid_sample`.
5. Przerwanie runu -> status `cancelled`, częściowy dataset nadal zapisany.
6. Brak pliku artefaktu przy recompute -> `dataset_corrupt` + blokada operacji.
7. Brak stream eventów -> fallback polling snapshot co 1-2s.

### 15.12 Walidacja danych i fallbacki

1. JSON schema validation przy odczycie artefaktów.
2. Hash datasetu + wersja scoringu.
3. Fallback dla wykresów: jeśli brak timeseries, pokazujemy tabelę agregatów.
4. Fallback dla `find-optimal`: jeśli brak części metryk, wyklucz trial i loguj reason.

### 15.13 Ryzyka, wydajność, testy

Ryzyka:

1. zbyt duża przestrzeń encoder params -> eksplozja czasu runu,
2. niespójne capability probing między urządzeniami,
3. regresja wydajności przy dużych datasetach.

Wydajność:

1. indeksowanie datasetów cachem,
2. lazy-load timeseries/charts,
3. limit event stream payload.

Testy wymagane:

1. walidacja payload startu,
2. constraints GA + bitrate range,
3. deterministyczność `find-optimal` (powtarzalność wyniku),
4. integracja event stream + HUD,
5. integralność artefaktów runu,
6. per-encoder ranking correctness.

### 15.14 Notatki implementacyjne dla zespołu

1. Zachować kompatybilność ze starymi endpointami, ale nową logikę budować na datasetach.
2. Każdy nowy artefakt ma mieć `schema_version`.
3. Każdy trial musi zapisywać pełne `encoder_params` i `global_params`.
4. UI ma blokować start runu, jeśli krytyczne pola są nieustawione.
5. `trainer.sh` ma być jedynym oficjalnym entrypointem dla trenera.
