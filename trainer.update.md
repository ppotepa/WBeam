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

