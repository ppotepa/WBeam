Poniżej masz scalone podsumowanie: Twoje high-level obserwacje + moje bardziej konkretne code review z wskaźnikami do plików, linii i metod.

# Pełne podsumowanie code review — porządkowanie struktury, spójności i reliktów

## 0. Werdykt ogólny

To **jest wykonalne** bez ingerencji w core’ową logikę.

Ten projekt nie wygląda jak „chaos do przepisania”, tylko jak system, który:

* ma **sensowny kierunek domenowy**,
* ale urósł przez kilka faz migracji,
* przez co ma dziś:

  * relikty kompatybilności,
  * rozjechane source-of-truth,
  * duże pliki-orchestratory,
  * i sporo logiki operacyjnej rozlanej między daemon / UI / wrappery.

Najważniejsze: największy dług siedzi dziś nie w samym streamingu, tylko w:

* **legacy support burden**,
* **duplikacji kontraktów/configów**,
* **operacyjnej złożoności lane’ów**,
* **monolitycznych plikach sterujących**.

---

## 1. Co potwierdzam z Twojego przeglądu high-level

### 1.1. Wieloplatformowość realnie podbija koszt projektu

Potwierdzam.

W kodzie i wrapperach jednocześnie żyją osie:

* Wayland vs X11,
* X11 real-output vs monitor-object fallback,
* Android legacy vs modern,
* adb reverse vs LAN/tether fallback,
* różne polityki zależne od host/session.

Widać to np. w:

* `host/rust/crates/wbeamd-core/src/infra/host_probe.rs:65-79`
* `host/rust/crates/wbeamd-core/src/infra/x11_backend/mod.rs:33-118`
* `wbeam:172-188`
* `wbeam:684-742`
* `wbgui:124-155`

To dokładnie wspiera Twoją tezę o „matrycy kombinacji”, która grozi cichymi regresjami poza główną ścieżką.

### 1.2. Runtime authority jest sensownie pomyślany, ale nie wszędzie domknięty

Potwierdzam.

Po stronie daemonu masz dobre centrum decyzji:

* `HostProbe::detect()` i resolver capture policy:

  * `host/rust/crates/wbeamd-core/src/infra/host_probe.rs:93-120`
* `WbeamdCore::host_probe()`, `virtual_probe()`, `virtual_doctor()`:

  * `host/rust/crates/wbeamd-core/src/lib.rs:766-823`

Ale część polityki żyje też poza daemonem:

* UI desktopowe blokuje/przepuszcza akcje na podstawie host probe:

  * `desktop/apps/desktop-tauri/src/App.tsx:248-318`
  * metody:

    * `connectDisabledReason()`
    * `isWaylandPortalHost()`
    * `openConnectDialog()`
* backend Tauri pokazuje X11 startup notice na podstawie heurystyk env/session:

  * `desktop/apps/desktop-tauri/src-tauri/src/main.rs:2196-2236`
  * metody:

    * `detect_session_type_for_notice()`
    * `should_show_x11_startup_notice()`
* serwer robi dodatkowe Wayland-specific gating i auto-layout:

  * `host/rust/crates/wbeamd-server/src/main.rs:853-901`
  * metoda: `post_start()`

Czyli: **architektura chce jednego authority, ale praktyka już lekko dryfuje**.

### 1.3. Legacy API17 i X11 są realnym kosztem, nie detalem

Potwierdzam wprost z wrapperów i dokumentacji:

* `README.md:9-18`, `27-38`, `50-57`
* `wbeam:172-188`, `211-214`, `707-742`
* `wbgui:131-133`, `198`
* `AGENTS.md:215-220`, `293-301`

To nie jest „poboczna kompatybilność”, tylko pełnoprawna oś komplikacji.

---

## 2. Najmocniejsze znaleziska strukturalne i spójnościowe

## 2.1. `archive/legacy` nie jest naprawdę archiwum

To jest najważniejszy punkt całego review.

### Co widać

Mimo że repo/dokumentacja mówi o archiwizacji legacy lane’ów:

* `docs/repo-structure.md:43-48`
* `update.md:53-54`

to aktywny kod nadal sięga do archiwum.

### Konkretne miejsca

* `host/rust/crates/wbeamd-core/src/lib.rs:349-421`

  * metoda: `load_presets_from_training_files()`
  * najpierw czyta:

    * `config/training/profiles.json`
  * a potem fallbackuje do:

    * `archive/legacy/proto/config/profiles.json`
* `wbeam:2044-2046`

  * metoda/wrapper: `x11proto_run()`
  * odpala:

    * `archive/legacy/proto_x11/run.sh`
* `scripts/ci/validate-e2e-matrix.sh:39-46`

  * CI traktuje pliki z `archive/legacy/...` jako wymagane
* `scripts/ci/check-repo-layout.sh:22-23`

  * layout check też wymaga:

    * `archive/legacy/proto/README.md`
    * `archive/legacy/proto_x11/README.md`

### Wniosek

Dziś `archive/legacy` jest nie tyle archiwum, co:

* **supported legacy lane**,
* albo przynajmniej **operacyjnie aktywną częścią repo**.

### Co to psuje

* myli semantykę katalogów,
* utrudnia cleanup,
* utrudnia decyzję „co wolno usuwać”,
* daje złudzenie, że legacy jest odseparowane, choć nie jest.

### Rekomendacja

Trzeba wybrać jedno:

1. albo `archive/legacy` staje się **prawdziwym archive** i runtime/CI przestaje tam zaglądać,
2. albo zmieniasz nazwę semantycznie na coś w stylu `legacy-supported/` albo `lanes/legacy/`.

Obecny stan „archiwum, ale aktywne” jest niespójny.

---

## 2.2. Masz rozjechane source-of-truth dla profili i runtime config

To jest drugi największy problem.

### Co deklaruje dokumentacja

* `host/training/README.md:12-13`

  * mówi, że:

    * `config/training/profiles.json` = source of truth
    * `config/training/autotune-best.json` = latest best config

### Co robi kod

* `host/rust/crates/wbeamd-core/src/lib.rs:349-421`

  * ładuje `config/training/profiles.json`, ale ma fallback do archiwum
* `host/rust/crates/wbeamd-api/src/lib.rs:434-454`

  * `presets()` ma **hardcoded baseline**
* `host/rust/crates/wbeamd-api/src/lib.rs:8-23`

  * canonical profile to `baseline`, ale istnieją aliasy legacy:

    * `lowlatency`
    * `balanced`
    * `ultra`
    * `fast60_*`
    * itd.
* `host/rust/config/presets.toml`

  * istnieje osobny preset store
  * ale z mojego grepowania wygląda na **nieużywany przez aktywny Rust path**
* `host/rust/crates/wbeamd-streamer/src/cli.rs:27-33`

  * CLI streamera nadal przyjmuje stare profile
* `host/rust/crates/wbeamd-streamer/src/cli.rs:278-338`

  * nadal ma profile defaults dla:

    * `lowlatency`
    * `balanced`
    * `ultra`
  * a potem kanonizuje je do `baseline`

### Dodatkowo

W repo są runtime state snapshoty z old profile names:

* `host/rust/config/runtime_state.serial-DM7S55KRBQEQU4VO.json:2`

  * `fast60_3`
* `host/rust/config/runtime_state.serial-HVA6PKNT.json:2`

  * `lowlatency`

### Wniosek

Masz jednocześnie:

* canonical profile model = `baseline`
* legacy alias map
* stary preset vocabulary w streamerze
* training profiles JSON
* archiwalny fallback
* runtime state z historycznymi nazwami
* osobny `presets.toml`

To jest książkowy przypadek „**więcej niż jedno miejsce wygląda jak prawda**”.

### Rekomendacja

Zamknąć to w jeden model:

* **kanoniczne źródło**: `config/training/profiles.json`
* **warstwa kompatybilności**: aliasy tylko przy wejściu
* **usunąć lub urealnić**:

  * `host/rust/config/presets.toml`
  * archiwalny fallback do `archive/legacy/proto/config/profiles.json`
* **przemigrować runtime state** do `baseline`

---

## 2.3. Kontrakt publiczny i dokumentacja są częściowo przestarzałe względem runtime

To jest bardzo ważne, bo wpływa na spójność całego repo.

### Najmocniejszy przykład: OpenAPI

* `docs/openapi.yaml:188-227`

  * `ActiveConfig.profile` ma enum:

    * `lowlatency`, `balanced`, `ultra`
  * `encoder` ma enum:

    * `auto`, `nvenc`, `openh264`
* Tymczasem runtime/API mówi:

  * `host/rust/crates/wbeamd-api/src/lib.rs:6-7`

    * valid encoders:

      * `h264`, `h265`, `rawpng`
    * valid cursor modes:

      * `hidden`, `embedded`, `metadata`
  * `host/rust/crates/wbeamd-api/src/lib.rs:8-23`

    * profile canonical = `baseline`

### Kolejne przykłady

* `docs/perf_targets.md:34-38`

  * nadal opisuje profile:

    * `lowlatency`, `balanced`, `ultra`
* `docs/wizard.md:128-132`

  * nadal opisuje rulesy o `balanced profile`, `balanced 60 profile`
* `README.md` i repo-structure mówią o archiwizacji legacy lane’ów, ale runtime nadal ich używa

### Wniosek

Masz drift między:

* aktualnym zachowaniem runtime,
* formalnym kontraktem API,
* dokumentacją operacyjną,
* i historycznymi nazwami.

### Rekomendacja

Tu nie trzeba ruszać logiki. Trzeba:

* przepisać kontrakty/docs pod obecny model,
* albo świadomie przywrócić stary model jako wspierany.

Na dziś dokumentacja wygląda jak po refaktorze, którego nie domknięto.

---

## 2.4. `PROTO_*` dalej przecieka do aktywnego modelu runtime

To nie jest awaria, ale to jest silny dług nazewniczy.

### Gdzie to widać

* `config/training/profiles.json:21-47`
* `config/training/autotune-best.json:4-59`
* `host/training/wizard.py:639-664`
* `host/training/wizard.py:1370-1396`

### Co to znaczy

Projekt domenowo jest już WBeam, ale runtime values nadal mają nomenklaturę z wcześniejszego etapu (`PROTO_*`).

### Wniosek

To jest relikt migracji, który:

* utrudnia zrozumienie modelu,
* zaciera granicę między „historycznym formatem” a „obecnym API”.

### Rekomendacja

Najlepszy wariant:

* zostawić kompatybilny parser starych kluczy,
* ale ustalić nowy kanoniczny naming dla aktywnego runtime modelu.

---

## 3. Artefakty, śmieci runtime i rzeczy bardzo podejrzane

## 3.1. W repo siedzą lokalne/runtime artefakty

### Konkretne pliki

* `host/training/__pycache__/wizard.cpython-313.pyc`
* `host/rust/config/runtime_state.serial-DM7S55KRBQEQU4VO.json`
* `host/rust/config/runtime_state.serial-HVA6PKNT.json`

### Dlaczego to zły sygnał

To nie wygląda jak source code ani fixture testowy, tylko jak:

* lokalny stan sesji,
* przypadkowy commit,
* środowiskowy artefakt.

### Rekomendacja

Bezpiecznie do usunięcia albo przeniesienia do:

* sample fixtures,
* test fixtures,
* albo generated state poza repo.

---

## 3.2. `config/training/autotune-best.json` wygląda jak environment dump, nie stabilny asset

### Konkretne symptomy

* `config/training/autotune-best.json:4-5`

  * `PROTO_PROFILE`
  * `PROTO_PROFILE_FILE = config/profiles.json` — ścieżka wygląda już niekanonicznie
* `:7`

  * `/tmp/proto-android.log`
* `:43`

  * `/tmp/proto-portal-restore-token-HVA6PKNT`
* `:55`

  * konkretne `SERIAL`
* dużo `PROTO_*`

### Wniosek

To wygląda bardziej jak:

* ostatni runtime snapshot z konkretnej maszyny/urządzenia,
  niż jak trwały, repo-worthy config.

### Rekomendacja

Albo:

* przenieść to do generated artifacts,
* albo zostawić tylko sanitized sample,
* albo trzymać poza repo.

---

## 3.3. `scripts/diagnostics/audodaignose` wygląda na relikt / sierotę / uszkodzony helper

To jest jeden z najbardziej namacalnych kandydatów do cleanupu.

### Fakty

* plik:

  * `scripts/diagnostics/audodaignose`
* nie jest executable:

  * `-rw-r--r--`
* grep nie pokazał odwołań do niego poza nim samym
* sama nazwa wygląda na literówkę

### Co gorsza: ma błąd ścieżki

* `scripts/diagnostics/audodaignose:4-5`

  * `ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"`
  * a potem:

    * `DAEMON_BIN="$ROOT_DIR/host/rust/target/release/wbeamd-server"`

Jeśli `ROOT_DIR` jest katalogiem `scripts/diagnostics`, to ścieżka do `host/rust/...` jest błędna.

### Wniosek

To nie tylko wygląda na relikt — to wygląda na coś, co w obecnym położeniu może być po prostu zepsute.

### Rekomendacja

Jeden z trzech ruchów:

1. usunąć,
2. naprawić i przemianować,
3. włączyć do oficjalnego `doctor` flow.

---

## 3.4. Duży historyczny data blob w archiwum

* `archive/legacy/proto/autotune-history.json` — ~22k linii

Nie jest to krytyczny problem, bo siedzi w `archive`, ale jeśli repo ma być lżejsze i bardziej czytelne, to to jest dobry kandydat do:

* eksportu poza repo,
* kompresji,
* albo zostawienia tylko sample.

---

## 4. Overcomplexity i duże hotspoty

## 4.1. Za dużo logiki w kilku gigantycznych plikach

To nie jest jeszcze „błąd”, ale jest bardzo czytelny sygnał przeciążenia struktury.

### Największe pliki

* `wbeam` — ~2195 linii, ~116 funkcji
* `host/rust/crates/wbeamd-server/src/main.rs` — ~2890 linii, ~77 funkcji
* `host/rust/crates/wbeamd-core/src/lib.rs` — ~2254 linii, ~67 funkcji
* `host/training/wizard.py` — ~2115 linii, ~63 funkcje
* `desktop/apps/trainer-tauri/src/App.tsx` — ~2598 linii
* `desktop/apps/desktop-tauri/src-tauri/src/main.rs` — ~2286 linii, ~86 funkcji

### Wniosek

Projekt nie jest overengineered przez liczbę folderów, tylko przez to, że za dużo odpowiedzialności wraca do kilku „super-plików”.

---

## 4.2. `desktop/apps/trainer-tauri/src/App.tsx` to klasyczny god-component

### Gdzie widać problem

* start komponentu:

  * `desktop/apps/trainer-tauri/src/App.tsx:502`
* ogromny blok state/signals:

  * `:520-567`
* duży blok derived state:

  * `:605-684`
* dużo logiki requestów i refreshy:

  * `refreshHealth()` — `:858-861`
  * `refreshRuns()` — `:863-870`
  * `refreshProfiles()` — `:872-888`
  * `refreshDevices()` — `:890-897`
  * `refreshDiagnostics()` — `:899-902`
  * `refreshDatasets()` — `:904-912`
  * `refreshDatasetDetail()` — `:914-921`
  * `runDatasetFindOptimal()` — `:923-941`
  * `refreshTail()` — `:943-953`
  * `refreshLiveSession()` — `:955-1015`
  * `startLiveRun()` — `:1017-1032`
  * `applyLiveConfig()` — `:1034-1054`

### Dodatkowo

Masz też dużo lokalnej logiki transformacyjnej:

* `parseHud()`
* `parseHudSeries()`
* `parseLiveStages()`
* `inferLiveHealth()`
* `buildLivePatchPayload()`
* `livePatchPlan`

### Wniosek

Tu jest bardzo dobry, niski-risk cleanup:

* wydzielić per-tab/per-feature:

  * `live/`
  * `profiles/`
  * `datasets/`
  * `diagnostics/`
  * `compare/`
* wydzielić:

  * `api.ts`
  * `parsers.ts`
  * `live-model.ts`
  * `dataset-actions.ts`

Bez zmiany logiki zyskasz bardzo dużo.

---

## 4.3. `desktop/apps/desktop-tauri/src-tauri/src/main.rs` też ma za szeroki scope

### Wskaźniki

* duża liczba `#[tauri::command]`
* wspólny handler:

  * `desktop/apps/desktop-tauri/src-tauri/src/main.rs:2267-2282`
* startup heuristics i UI notices w tym samym pliku:

  * `:2196-2250`

### Wniosek

Ten plik łączy:

* command bridge,
* startup behavior,
* service/control orchestration,
* heurystyki sesji.

### Rekomendacja

Rozbić na moduły:

* `commands/`
* `service/`
* `session/`
* `ui_notices/`
* `adb/`
* `virtual/`

---

## 4.4. `wbeam` stał się bardzo dużym shell orchestrator’em

### Konkretne bloki

* pipeline selection:

  * `wbeam:140-195`
* transport detection:

  * `wbeam:197-240`
* legacy/modern host resolution:

  * `wbeam:684-742`
* legacy x11 wrapper:

  * `wbeam:2044-2065`

### Wniosek

Ten plik pełni dziś naraz rolę:

* CLI,
* deploy orchestratora,
* transport resolvera,
* compatibility shim,
* launch wrappera.

To jest naturalne na etapie wzrostu projektu, ale długofalowo bardzo słabo się skaluje.

### Rekomendacja

Nie przepisywać logiki, tylko wydzielić z `wbeam`:

* `android_pipeline.sh`
* `transport_resolver.sh`
* `x11proto_bridge.sh`
* `service_ops.sh`

---

## 5. Wrappery i skrypty: dużo duplikacji, dużo ukrytej polityki

## 5.1. `trainer.sh` i `desktop.sh` mają bardzo podobny flow re-exec do GUI session

### Konkretne miejsca

* `trainer.sh:27-130`

  * `has_graphical_env()`
  * `trainer_detect_runas_remote_filter()`
  * `ensure_graphical_context()`
  * `apply_tauri_stability_env()`
* `desktop.sh:54-130`

  * `desktop_has_graphical_env()`
  * `desktop_detect_runas_remote_filter()`
  * `desktop_ensure_graphical_context()`

### Wniosek

To jest mocna duplikacja zachowania, tylko pod inną nazwą.

### Rekomendacja

Wydzielić wspólne utility do jednego shell-lib:

* graphical session detection
* runas-remote reexec
* Tauri stability env setup

---

## 5.2. Konfiguracja/env override jest rozproszona po wielu entrypointach

### Gdzie to widać

Prawie wszystkie wrappery source’ują ten sam helper:

* `wbeam:6-9`
* `devtool:6-9`
* `trainer.sh:5-8`
* `desktop.sh:6-9`
* `start-remote:6-9`
* `runas-remote:6-9`
* `host/scripts/run_wbeamd.sh:12-17`

Do tego dochodzi sporo jawnego czyszczenia/stabilizowania env:

* `start-remote:184-186`
* `runas-remote:409-452`

### Plus

Sam config mówi wprost:

* `config/wbeam.conf:1-6`

  * override order:

    * ENV
    * user config

### Wniosek

Model jest praktyczny, ale bardzo łatwo tu o:

* niejawne override,
* sesyjne efekty uboczne,
* różnice między uruchomieniem lokalnym, remote i debug.

To dokładnie wspiera Twoją tezę o ryzyku „ukrytego env-a z sesji użytkownika”.

---

## 6. Support matrix i polityki lane’ów nie są jeszcze mechanicznie domknięte

## 6.1. Support tiers są komunikowane, ale nie egzekwowane jako twardy kontrakt

### Z jednej strony

`README.md` mówi:

* Wayland recommended
* X11 experimental
* archived X11 prototype to R&D

  * `README.md:9-18`, `27-38`

### Z drugiej strony

* `wbeam` ma aktywny `x11proto_run()`

  * `wbeam:2044-2046`
* CI wymaga `archive/legacy/proto_x11`

  * `scripts/ci/validate-e2e-matrix.sh:39`
* layout checks też wymagają archive paths

  * `scripts/ci/check-repo-layout.sh:22-23`

### Wniosek

Semantycznie mówisz „best effort / experimental”, ale technicznie nadal jest to wspierana ścieżka build/repo/CI.

To jest dokładnie to miejsce, gdzie support burden rośnie szybciej niż throughput.

---

## 6.2. `check-boundaries.sh` pilnuje starych aliasów, ale nie odcina aktywnych zależności na archive

* `scripts/ci/check-boundaries.sh:25-27`
* `:42-48`

Guard blokuje stare wrapper refs typu `./proto_x11/`, ale nie blokuje odwołań do `archive/legacy/...`.

To znaczy:

* strukturalnie usunąłeś stare aliasy,
* ale funkcjonalnie nadal możesz żyć na archive lane.

To jest ważna subtelność.

---

## 7. Dodatkowe konkretne punkty spójnościowe

## 7.1. `host/training/wizard.py` ma powielony export profilu

### Konkret

Masz bardzo podobne bloki budujące strukturę profilu w dwóch miejscach:

* `host/training/wizard.py:631-665`
* `host/training/wizard.py:1370-1396`

Powiązane metody:

* `write_profile_artifacts()` — od `:670`
* `write_profile_baseline()` — od `:1340`

### Wniosek

To wygląda na duplikację eksportu profilu/best trial config.

### Rekomendacja

Wydzielić jedną funkcję budującą wspólny payload profilu.

---

## 7.2. `host/rust/config/presets.toml` wygląda na martwy lub półmartwy plik

Plik istnieje:

* `host/rust/config/presets.toml:1-26`

Ale przy przeszukaniu repo nie znalazłem sensownych aktywnych odwołań do niego poza nim samym.

### Wniosek

To wygląda na:

* relikt po starym modelu presetów,
* albo feature, który nie został domknięty.

### Rekomendacja

Sprawdzić decyzję:

* albo usunąć,
* albo naprawdę wpiąć jako source-of-truth.

---

## 7.3. Top-level repo structure doc nie opisuje realnego roota do końca

### Co mówi structure doc

* `docs/repo-structure.md:14-30`

  * canonical root layout jest bardzo krótki

### Co realnie siedzi w root

Poza tym masz jeszcze m.in.:

* `wbgui`
* `devtool`
* `start-remote`
* `runas-remote`
* `progress.md`
* `update.md`

### Co mówi README

* `README.md:70-75`

  * wymienia `wbeam`, `wbgui`, `devtool`, `start-remote`

### Wniosek

Dokument „source of truth” dla struktury nie opisuje całego realnego obrazu repo.

To nie jest błąd runtime, ale psuje spójność.

---

## 8. Priorytety cleanupu — co zrobić najpierw

## P0 — bezpieczne od razu, bez ruszania core logic

1. Usunąć runtime/local artefakty z repo:

   * `__pycache__`
   * `runtime_state.serial-*.json`
2. Zdecydować los `config/training/autotune-best.json`

   * sample vs generated artifact
3. Naprawić/usunąć:

   * `scripts/diagnostics/audodaignose`
4. Ujednolicić source-of-truth dla profili:

   * zatrzymać się na `config/training/profiles.json`
5. Zaktualizować kontrakty/docs:

   * `docs/openapi.yaml`
   * `docs/perf_targets.md`
   * `docs/wizard.md`

## P1 — duży zysk porządkowy, nadal bez zmiany zachowania

6. Rozbić:

   * `desktop/apps/trainer-tauri/src/App.tsx`
   * `desktop/apps/desktop-tauri/src-tauri/src/main.rs`
   * `wbeam`
7. Wydzielić wspólne shell utility z:

   * `trainer.sh`
   * `desktop.sh`
8. Domknąć semantykę `archive/legacy`

   * archive albo supported lane, nie coś pośrodku

## P2 — wymaga świadomej decyzji produktowo-architektonicznej

9. Czy API17 zostaje pełnym support tier?
10. Czy X11 zostaje aktywnym torem, czy best-effort/R&D?
11. Czy python daemon fallback ma dalej żyć jako supported path?

* `host/scripts/run_wbeamd.sh:234-255`
* `host/daemon/wbeamd.py`

---

## 9. Najkrótsze podsumowanie

### Co jest dobre

* domenowy podział repo jest sensowny,
* daemon jako centrum systemu ma dobry kierunek,
* widać świadome myślenie o kompatybilności i lane’ach.

### Co jest dziś największym problemem

* `archive/legacy` nie jest naprawdę archiwum,
* profile/config mają kilka „prawd naraz”,
* publiczny kontrakt/docs są częściowo stare,
* część polityki runtime przecieka do UI i wrapperów,
* kilka dużych plików stało się monolitami sterującymi.

### Co bym zrobił jako pierwszy realny cleanup

* usunąć artefakty i sieroty,
* ustalić jedno source-of-truth dla profili,
* odciąć aktywny runtime od archive fallbacków albo formalnie uznać legacy lane,
* rozbić największe pliki bez zmiany logiki,
* wyrównać dokumentację do stanu faktycznego.

Kolejny krok, który ma największy sens, to przygotowanie Ci z tego **konkretnej checklisty refactor-cleanup** w formie:
`plik -> problem -> ryzyko -> rekomendacja -> bezpieczny ruch teraz / później`.
