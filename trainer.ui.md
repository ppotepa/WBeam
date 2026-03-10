# WBeam Trainer UI/UX Design Specification

Last updated: 2026-03-10  
Status: Canonical UI/UX blueprint for `trainer` / `trainer.sh`

## 1. Purpose

Ten dokument opisuje docelowy interfejs aplikacji `trainer` jako osobnego produktu desktopowego dla WBeam. Ma on scalić wszystkie ustalenia dotyczące:

- flow użytkownika,
- architektury ekranów,
- zachowania aplikacji w czasie treningu,
- stylu wizualnego,
- gęstości informacji,
- stanów asynchronicznych,
- HUD-u live,
- ergonomii pracy z profilami, runami i datasetami.

To nie jest opis jednego widoku. To jest pełna specyfikacja odczucia produktu, który ma wyglądać jak narzędzie klasy performance lab / control room, a nie jak prosty wizard.

## 2. Product Vision

`trainer` ma być aplikacją, która daje operatorowi pełną kontrolę nad treningiem profili streamingowych oraz pełną widoczność tego, co dzieje się w systemie.

Użytkownik ma w każdej chwili rozumieć:

- jaki device jest aktywny,
- jaki run jest konfigurowany lub wykonywany,
- na jakim etapie jest trening,
- jakie parametry są aktualnie testowane,
- czy stan jest zdrowy, ostrzegawczy czy błędny,
- dlaczego dany trial został odrzucony albo wygrał,
- jak wynik ma się do wcześniejszych runów i profili.

Produkt ma czytać się jako:

- precyzyjny,
- nowoczesny,
- futurystyczny, ale nie krzykliwy,
- kompaktowy,
- szybki do skanowania wzrokiem,
- jednoznaczny stanowo.

## 3. North Star UX

Najważniejsze zasady doświadczenia użytkownika:

1. `3-second clarity`
   Użytkownik w mniej niż 3 sekundy ma rozumieć, co dzieje się teraz.

2. `No blank uncertainty`
   Żaden duży obszar nie może wyglądać jak pusty lub martwy bez wytłumaczenia, co trwa.

3. `Every action has feedback`
   Każde kliknięcie musi mieć potwierdzenie: loading, busy state, toast, progress, success albo error.

4. `Operator-first layout`
   To ma być narzędzie do pracy z danymi i eksperymentami, nie marketingowy landing page.

5. `Compact, not cramped`
   Interfejs może być gęsty, ale nie może sprawiać wrażenia ścisku i chaosu.

6. `Explainability first`
   Każda ważna liczba powinna mieć kontekst, tooltip albo powód.

7. `Consistent semantics`
   Ten sam kolor, ten sam badge i ten sam wording muszą znaczyć to samo w całej aplikacji.

## 4. Core App Flow

## 4.1 Launch flow

Po uruchomieniu `./trainer.sh` użytkownik nie trafia od razu na formularz treningu. Najpierw aplikacja przechodzi przez warstwę gotowości środowiska.

Kolejność:

1. bootstrap okna,
2. health check daemonu,
3. ADB readiness check,
4. device discovery,
5. odblokowanie nawigacji produktowej.

Jeżeli daemon nie działa:

- pokazujemy blocking gate screen,
- ekran nie jest pusty,
- użytkownik widzi: stan, przyczynę, quick actions,
- dostępne akcje:
  - `Start Service`
  - `Retry`
  - `Open Diagnostics`
  - `Open Logs`

Jeżeli ADB nie wykrywa urządzeń:

- aplikacja działa dalej,
- `Train` jest w stanie ograniczonym,
- użytkownik widzi sekcję onboardingową zamiast martwej listy.

## 4.2 New training flow

Flow nowego runu ma być liniowy, ale nie wizardowy w złym sensie. Użytkownik może pracować szybko, ale ma jasne checkpointy.

Docelowy przebieg:

1. wybór urządzenia,
2. wybór trybu pracy:
   - `Quick Tune`
   - `Advanced Tune`
3. konfiguracja runu,
4. preflight review,
5. start treningu,
6. automatyczne przejście do `Live Run`,
7. po zakończeniu otwarcie panelu wyników,
8. możliwość przejścia do:
   - `Profiles`
   - `Runs`
   - `Datasets`
   - `Compare`

## 4.3 Live training flow

Podczas aktywnego treningu aplikacja ma zachowywać się jak stanowisko operatorskie:

- użytkownik widzi stan runu w top barze,
- `Live Run` staje się centralnym ekranem,
- progres jest pokazany jednocześnie na poziomie:
  - całego runu,
  - etapu,
  - generacji,
  - triala,
- event log płynie w czasie rzeczywistym,
- na każdej sekundzie da się zidentyfikować:
  - bieżący trial,
  - testowane parametry,
  - aktualny score,
  - ostatnie ostrzeżenia lub gate failures.

## 4.4 End of run flow

Po zakończeniu runu aplikacja nie może zostawiać użytkownika na surowym logu.

Natychmiast po finale:

- pokazujemy `Run Results Drawer` lub modal summary,
- eksponujemy:
  - `global winner`,
  - `winner per encoder`,
  - `top alternatives`,
  - `rejected count`,
  - `confidence`,
  - `quick actions`.

Quick actions:

- `Save / Update Profile`
- `Open Dataset`
- `Find Optimal Best`
- `Compare With Baseline`
- `Run Validation`

## 4.5 Post-run analysis flow

Po runie użytkownik może wejść głębiej w dane. To jest druga połowa produktu.

Kolejność analizy:

1. `Runs` pokazuje historię wykonań,
2. `Datasets` pokazuje pełne zbiory danych i recompute,
3. `Profiles` pokazuje wynikowe profile do użycia,
4. `Compare` pokazuje różnice i trade-offy,
5. `Validation` potwierdza, czy profil nadal jest mocny.

## 5. Information Architecture

Docelowa nawigacja boczna:

1. `Train`
2. `Live Run`
3. `Runs`
4. `Profiles`
5. `Datasets`
6. `Compare`
7. `Devices`
8. `Validation`
9. `Diagnostics`
10. `Settings`

Decyzja nazewnicza:

- w warstwie produktowej preferowana etykieta to `Live Run`,
- w dokumentacji technicznej i backendowej może nadal istnieć termin `Live HUD`,
- oba określenia odnoszą się do tej samej domeny, ale `Live Run` lepiej komunikuje, że chodzi o cały żywy przebieg, a nie tylko overlay liczb.

## 6. Global Shell

## 6.1 Main shell layout

Cała aplikacja ma układ typu workstation:

- top bar,
- lewy rail nawigacyjny,
- główny content,
- opcjonalny prawy detail pane,
- sticky action areas tam, gdzie to ma sens.

## 6.2 Top bar

Top bar jest globalnym paskiem stanu systemu, a nie tylko dekoracją.

Musi zawierać:

- aktywne urządzenie,
- aktywny profil lub nazwę runu,
- goal mode,
- daemon health,
- ADB health,
- run state,
- quick actions:
  - `Refresh`
  - `Start Service`
  - `Open Diagnostics`
  - `Stop Run` jeśli aktywny

Top bar powinien być:

- sticky,
- niski,
- bardzo czytelny,
- zawsze widoczny podczas treningu.

## 6.3 Left nav rail

Lewy rail:

- stały,
- kompaktowy,
- z ikoną i etykietą,
- z badge'ami liczbowymi lub statusowymi,
- z mocnym zaznaczeniem aktywnej sekcji.

Badge examples:

- `Live Run` może mieć niebieski pulse podczas aktywnego treningu,
- `Diagnostics` może mieć żółty badge przy warningach,
- `Validation` może mieć licznik stale profiles.

## 6.4 Right detail pane

Prawy pane jest opcjonalny i kontekstowy.

Może pokazywać:

- trial detail,
- profile detail,
- run detail,
- warning summary,
- tooltip-expanded explanation.

Na małych szerokościach okna prawy pane ma być collapsible albo zamieniany na drawer.

## 7. Primary Screen Flows

## 7.1 Service Unavailable / Bootstrap Screen

To jest pierwszy ekran, jeśli backend nie jest gotowy.

Ma zawierać:

- jasny komunikat co jest niedostępne,
- health matrix:
  - daemon,
  - ADB,
  - devices,
  - API compatibility,
- ostatni znany build revision,
- ostatni znany log path,
- quick actions.

Wizualnie:

- pełnoekranowy panel centralny,
- mocny status headline,
- dwa poziomy treści:
  - krótki komunikat,
  - technical detail expandable.

## 7.2 Train Screen

`Train` jest ekranem konfiguracji nowego runu. Musi wspierać zarówno szybki start, jak i wejście głębiej.

### Layout

Układ 2-kolumnowy:

- lewa kolumna:
  - Device selector
  - Mode selector
  - Profile identity
  - Quick summary
- prawa kolumna:
  - Encoder setup
  - Bitrate range
  - Resolution / FPS
  - GA parameters
  - Advanced options

Na dole sticky action bar:

- `Run Preflight`
- `Start Training`
- estimate czasu
- summary walidacji

### Sections

1. Device selector
   Pokazuje urządzenia jako kompaktowe karty z:
   - serial,
   - model,
   - API,
   - Android version,
   - ready/offline state,
   - stream port,
   - capability hint.

2. Training mode
   Dwa tryby UX:
   - `Quick Tune`
   - `Advanced Tune`

3. Profile identity
   Zawiera:
   - profile name,
   - optional description,
   - tags / labels,
   - overwrite policy.

4. Encoder block
   Zawiera:
   - `single-encoder` / `multi-encoder`,
   - codec toggles,
   - per-encoder advanced drawer.

5. Quality block
   Zawiera:
   - target FPS,
   - resolution mode,
   - bitrate min,
   - bitrate max,
   - optional bitrate ladder strategy.

6. GA block
   Zawiera:
   - generations,
   - population,
   - elite count,
   - mutation rate,
   - crossover rate,
   - stage budgets.

7. Advanced block
   Domyślnie zwinięty.
   Zawiera:
   - warmup time,
   - sample time,
   - strictness preset,
   - retry policy,
   - diagnostics verbosity,
   - artifact policy,
   - sustained validation duration,
   - per-encoder overrides.

### UX rules

- niedozwolone kombinacje mają być blokowane inline, nie dopiero po submit,
- każda bardziej techniczna kontrolka ma tooltip `Why this matters`,
- pola powiązane ze sobą mają mieć immediate validation,
- `Start Training` jest disabled, ale z widocznym powodem,
- estimate czasu ma aktualizować się live.

## 7.3 Preflight Review

`Preflight` to jawny krok jakościowy pomiędzy formularzem a właściwym runem.

Ma pokazywać:

- device capability summary,
- ADB throughput summary,
- baseline stream benchmark,
- derived bounds,
- clampi i ostrzeżenia,
- unsupported options,
- recommended changes.

Layout:

- lewa część:
  - wyniki preflight,
  - bound derivation,
  - warnings
- prawa część:
  - final effective config preview,
  - changed-by-system markers,
  - CTA.

CTA:

- `Accept & Start`
- `Back To Edit`
- `Auto-fix and Continue`

## 7.4 Live Run Screen

To ma być ekran flagowy całej aplikacji.

### Layout

Układ 3-pane:

- lewy fixed pane:
  - frozen run config,
  - active device,
  - control actions,
  - notes / markers
- center fluid pane:
  - run header,
  - progress strips,
  - live KPI cards,
  - main charts,
  - score breakdown,
  - gate matrix
- prawy fixed pane:
  - event log,
  - warnings,
  - best-so-far panel,
  - trial leaderboard snapshot

### Run header

Musi pokazywać:

- run id,
- profile name,
- selected device,
- current stage,
- current generation,
- current trial,
- current encoder,
- elapsed time,
- ETA,
- overall state badge.

### Progress model

Na ekranie jednocześnie muszą istnieć:

- progress runu,
- progress etapu,
- progress generacji,
- progress triala.

Forma:

- główny progress bar,
- mniejsze bars / chips,
- liczby absolutne i procent.

### KPI cards

Top strip KPI ma być szybki do skanowania.

Minimalny zestaw:

- `Present FPS`
- `Recv / Pipe FPS`
- `Latency p95`
- `Drop %`
- `Timeout Rate`
- `Bitrate`
- `Current Score`
- `Confidence`

Karty muszą mieć:

- dużą liczbę,
- mały label,
- mini sparkline,
- delta vs previous trial lub baseline.

### Charts

Live charts w centrum:

- FPS timeline,
- latency timeline,
- drops / timeouts timeline,
- bitrate / throughput timeline,
- score timeline.

Zasady:

- stabilna skala osi, bez nerwowego przeskakiwania,
- legendy zawsze widoczne,
- current value badge przy tytule,
- overlay porównawczy opcjonalny.

### Score breakdown

Panel `Why this score`:

- stability contribution,
- quality contribution,
- latency contribution,
- efficiency contribution,
- penalties,
- gates pass/fail.

To ma tłumaczyć decyzję, a nie tylko pokazywać liczbę końcową.

### Gate matrix

Macierz progów:

- fps floor,
- timeout threshold,
- drop threshold,
- sample count,
- thermal / sustained health jeśli w danym etapie ma zastosowanie.

Każdy gate:

- status,
- threshold,
- observed value,
- short reason.

### Event log

Prawy panel logów:

- strumień zdarzeń live,
- severity color coding,
- filtry:
  - all
  - warnings
  - errors
  - scoring
  - lifecycle
- możliwość pinowania najważniejszych wpisów.

### UX rule

Jeżeli użytkownik wejdzie na ten ekran w trakcie runu, ma w mniej niż 3 sekundy wiedzieć:

- co się dzieje,
- czy jest zdrowo,
- który trial trwa,
- czy run ma sens iść dalej.

## 7.5 Run Results Drawer

Po zakończeniu runu pojawia się panel podsumowania.

Musi zawierać:

- global winner,
- winner per encoder,
- final confidence,
- total trials,
- rejected trials,
- pareto frontier summary,
- top alternatives.

Sekcja `Next actions`:

- `Open Profile`
- `Open Dataset`
- `Find Optimal Best`
- `Compare With Baseline`
- `Run Validation`

## 7.6 Runs Screen

`Runs` pokazuje historyczne wykonania.

### Layout

- top filters bar,
- główna tabela,
- detail drawer po wyborze runu.

### Columns

- run id,
- profile name,
- device,
- goal mode,
- status,
- duration,
- trials completed,
- final score,
- winner codec,
- created date,
- schema/scoring version badge.

### Detail drawer

- run overview,
- event timeline,
- stage summary,
- trial table,
- rejection reasons summary,
- artifact list,
- validation links,
- recompute status.

## 7.7 Profiles Screen

`Profiles` ma reprezentować gotowe lub prawie gotowe rezultaty, a nie surowe runy.

### Primary tasks

- browse,
- filter,
- inspect,
- validate,
- export,
- compare,
- apply.

### Layout

Widok przełączalny:

- card view,
- dense table view.

### Filters

- profile name,
- device model,
- codec,
- goal mode,
- score range,
- stale status,
- validated status,
- compatibility status.

### Profile details

- winner config,
- per-encoder variants,
- preflight summary,
- score explanation,
- run history,
- validation history,
- drift notes,
- artifact links.

## 7.8 Datasets Screen

`Datasets` to ekran do pracy z pełnymi run datasets, a nie tylko z wynikowym profilem.

### Purpose

- przegląd datasetów,
- kontrola integralności,
- replay i analytics,
- deterministyczne `Find Optimal Best`.

### Layout

- tabela datasetów,
- filters,
- integrity badges,
- analytics preview,
- right detail pane.

### Detail view

- input config,
- capability,
- preflight,
- trial count,
- rejected count,
- telemetry presence,
- scoring model version,
- schema version,
- recompute history.

### Key action

`Find Optimal Best`:

- działa bez uruchamiania nowego streamu,
- pokazuje diff względem oryginalnego winnera,
- zapisuje recompute result,
- komunikuje, czy wynik się zmienił.

## 7.9 Compare Screen

`Compare` ma wspierać 2-3 way comparison.

### Compared dimensions

- codec,
- bitrate,
- resolution,
- FPS stability,
- latency tails,
- drop rate,
- timeout rate,
- confidence,
- sustained score,
- transport class,
- device metadata,
- compatibility.

### Visuals

- aligned metric table,
- timeline overlays,
- bitrate vs score scatter,
- pareto plot,
- bottleneck summary,
- recommendation badge.

### UX rules

- porównanie ma działać zarówno dla profili, jak i dla run winners,
- użytkownik ma dostać recommendation, a nie tylko suche liczby.

## 7.10 Devices Screen

`Devices` pokazuje wszystkie urządzenia widoczne przez ADB.

### Per-device card/table fields

- serial,
- model,
- API level,
- Android version,
- authorization state,
- transport class,
- codec support,
- last preflight summary,
- throughput tier,
- health warnings,
- recommended mode.

### Device actions

- `Probe`
- `Quick Preflight`
- `Open Recent Runs`
- `Validate Profile`
- `Use For New Training`

## 7.11 Validation Screen

`Validation` ma potwierdzać, że profil nadal działa dobrze bez pełnego retrainu.

Widoki:

- selected profile,
- selected device,
- baseline vs current metrics,
- regression verdict,
- drift summary,
- recommendation:
  - keep,
  - revalidate later,
  - retrain.

## 7.12 Diagnostics Screen

`Diagnostics` to ekran dla stanu technicznego i troubleshootingu.

Powinien zawierać:

- daemon health,
- API version,
- build revision,
- ADB version,
- ADB raw output,
- environment summary,
- recent log paths,
- throughput quick tests,
- codec support diagnostics,
- artifact integrity warnings.

To ma być ekran dla operatora i developera, nie dla codziennego klikacza.

## 7.13 Settings Screen

`Settings` powinien być ograniczony i pragmatyczny.

Zakres:

- theme density,
- default run presets,
- chart refresh policy,
- log retention policy,
- advanced feature flags,
- UI debug toggles.

Nie należy tam przerzucać ustawień, które są istotą runu. Parametry treningowe powinny pozostać w `Train`.

## 8. Visual Style System

## 8.1 Visual direction

Stylistyka: `engineering control room`.

Ma być:

- nowoczesna,
- techniczna,
- wyrazista,
- chłodna,
- uporządkowana,
- oparta o warstwy i dane,
- bez cukierkowego futurismu.

Nie ma być:

- neonowym cyberpunkiem,
- casual dashboardem SaaS,
- białą tabelką enterprise,
- dark mode dla samego dark mode.

## 8.2 Color palette

Bazowy kierunek kolorystyczny:

- głębokie grafitowo-granatowe tło,
- chłodny niebiesko-cyjanowy akcent aktywny,
- zielony tylko jako semantyka pass/success,
- bursztyn dla warning,
- czerwony dla fail/error,
- szarości do stanów nieaktywnych.

Suggested tokens:

- `--bg-0`: `#08111b`
- `--bg-1`: `#0d1826`
- `--bg-2`: `#132235`
- `--panel`: `rgba(11, 22, 35, 0.82)`
- `--panel-strong`: `#101c2b`
- `--line-soft`: `rgba(145, 180, 210, 0.18)`
- `--line-strong`: `rgba(145, 180, 210, 0.34)`
- `--text-0`: `#f4f8fb`
- `--text-1`: `#b7c4d3`
- `--accent`: `#49c2ff`
- `--accent-2`: `#7ce7ff`
- `--ok`: `#38d39f`
- `--warn`: `#f6ba4a`
- `--risk`: `#ff6c5c`
- `--muted`: `#7f91a6`

Semantyka kolorów jest stała:

- niebieski = active / current / selected,
- zielony = stable / pass / healthy,
- żółty = caution / degraded / requires attention,
- czerwony = fail / blocked / regression,
- szary = unknown / unavailable / disabled.

## 8.3 Typography

Typografia ma wspierać szybkie skanowanie i techniczny charakter.

Rekomendacja:

- UI / headings: `Space Grotesk`
- metrics / logs / numerics: `IBM Plex Mono`

Zasady:

- liczby telemetryczne zawsze fontem tabular/mono,
- sekcje i tytuły wyraźniejsze i ciaśniejsze,
- body copy czytelny, ale oszczędny,
- unikać ogromnych bloków tekstu.

Suggested scale:

- `12px` meta / badges / helper,
- `14px` body / form labels,
- `16px` section titles,
- `20px` page titles,
- `28-36px` KPI numerics.

## 8.4 Background and surfaces

Tło nie powinno być płaskie.

Rekomendacja:

- ciemny gradient tła,
- delikatne radialne światła w rogach,
- subtelny grid / noise texture o bardzo małym kontraście,
- szklane lub półmatowe panele, ale bez agresywnego glassmorphism.

Pojedynczy panel:

- lekko uniesiony,
- zaokrąglenie `10-14px`,
- cienka ramka,
- minimalny shadow,
- stabilna wysokość.

## 8.5 Spacing and density

System spacing:

- `4 / 8 / 12 / 16 / 24 / 32`

Gęstość:

- desktop default: medium-compact,
- operator mode: dużo informacji na ekranie,
- bez wrażenia klaustrofobii.

Zasada:

- więcej rytmu i alignmentu, mniej pustych dekoracyjnych przestrzeni.

## 8.6 Buttons and interactive elements

Każdy button ma komplet stanów:

- idle,
- hover,
- active,
- loading,
- success,
- error,
- disabled.

Busy state:

- spinner lub progress micro-indicator,
- tekst typu:
  - `Starting preflight…`
  - `Running trial…`
  - `Saving profile…`

`disabled` nie może być martwy. Ma mieć powód w inline hint albo tooltipie.

## 8.7 Inputs and forms

Formy mają być techniczne, ale przyjazne.

Każde pole powinno mieć:

- label,
- value control,
- inline helper,
- optional tooltip,
- validation state.

Tooltip pattern:

- tytuł,
- krótki opis,
- `Why it matters`,
- czasem `Typical range`.

To jest ważne zwłaszcza dla:

- mutation rate,
- crossover rate,
- elite count,
- bitrate min/max,
- GOP / preset / tune,
- sample duration,
- strictness.

## 8.8 Tables

Tabele są ważną częścią produktu.

Powinny mieć:

- sticky header,
- row hover state,
- selected row state,
- right-aligned numerics,
- badge-rich columns,
- quick filters.

Tabele nie mogą wyglądać jak surowy HTML dump.

## 8.9 Charts

Wykresy mają być integralną częścią produktu, nie dodatkiem.

Zasady:

- czytelne legendy,
- spójne kolory semantyczne,
- hover tooltips,
- replay lub scrub tam, gdzie sensowne,
- możliwość overlay dla compare.

Chart mapping:

- FPS = cyan / blue,
- latency = amber,
- drops = red,
- bitrate = electric blue,
- confidence = green-cyan,
- score = light blue/white accent.

## 8.10 Motion

Ruch ma być oszczędny i funkcyjny.

Allowed motion:

- panel enter fade/slide `180-220ms`,
- button press `120ms`,
- row highlight `120ms`,
- progress pulse przy aktywnym trialu,
- small KPI flash przy istotnej zmianie wartości.

Unwanted motion:

- dekoracyjne ciągłe animacje,
- skakanie layoutu,
- przerysowane glow effects,
- stale pulsujące wszystko.

## 9. Async States and Feedback

## 9.1 Universal rule

Każda akcja wywołująca backend, pliki lub benchmark musi mieć jawny stan pośredni.

## 9.2 Required feedback patterns

1. loading
   - skeleton albo spinner
   - krótki komunikat

2. busy
   - button state
   - blocked inputs, ale tylko tam gdzie trzeba

3. success
   - toast + trwały ślad, jeśli akcja była istotna

4. error
   - komunikat z powodem,
   - retry action,
   - link do diagnostics/logs jeśli to ma sens

## 9.3 Empty states

Puste stany mają mówić, co dalej:

- `No devices detected`
- `No runs yet`
- `No datasets for this filter`
- `No validation history`

Każdy empty state:

- jedno krótkie zdanie,
- jeden CTA,
- opcjonalnie technical hint.

## 9.4 Toasts and notifications

Toasty są krótkie i operacyjne.

Examples:

- `Preflight completed`
- `Run started on HVA6PKNT`
- `Profile baseline updated`
- `Dataset recompute finished`

Dłuższe lub krytyczne problemy trafiają też do notification center albo event logu.

## 10. Copy and Human Guidance

Tone of voice:

- techniczny,
- krótki,
- konkretny,
- bez marketingu,
- bez żargonu tam, gdzie nie daje wartości.

Mikrocopy ma być pomocne:

- zamiast `Invalid input` lepiej `Elite count must stay below population`,
- zamiast `Error` lepiej `Daemon health endpoint is unreachable`,
- zamiast `Unknown state` lepiej `No samples received in the last 3s`.

## 11. Tooltip System

Tooltipy są obowiązkowe dla zaawansowanych ustawień.

Struktura tooltipa:

- `label`
- `short meaning`
- `effect on run`
- `recommended range` jeśli istnieje

Przykłady:

- `Mutation rate`
  Controls how aggressively new generations diverge from elites. Higher values explore more but destabilize search.

- `Min bitrate`
  Lower bound for tested bitrate ladder. Preflight may clamp it upward if baseline is too weak.

- `Strictness`
  Controls how fast unstable trials are rejected. Higher strictness shortens bad trials but may reduce exploration.

## 12. Device-Side HUD Overlay

Ponieważ trening ma być odczuwalny także na samym tablecie, overlay urządzenia musi być spójny z desktopem.

Wymagania dla overlay na urządzeniu:

- techniczny, nerdy wygląd,
- czytelny nawet przy ruchu,
- 4-corner layout lub kompaktowy stacked layout,
- spójne nazewnictwo z desktopem,
- bez dekoracyjnego śmiecia.

Ma pokazywać:

- run id / trial id,
- encoder,
- bitrate,
- FPS,
- latency,
- drop rate,
- score,
- stage / generation / progress,
- krótkie alerty.

Kolory i semantyka mają odpowiadać desktopowi.

## 13. Responsive Behavior

To jest aplikacja desktopowa, ale okno może mieć różne rozmiary.

Breakpoints:

- `>= 1440px`: pełny 3-pane layout,
- `1100px - 1439px`: prawa kolumna collapsible,
- `< 1100px`: zakładki lub stacked layout w obrębie ekranu,
- `< 900px`: only-if-needed compact fallback, nadal bez utraty krytycznych kontrolek.

W szczególności:

- `Stop Run` nigdy nie może znikać,
- event log może się zwężać, ale nie znikać całkowicie,
- KPI cards mogą się zawijać, ale nie mogą tracić stabilności układu.

## 14. Accessibility and Readability

Mimo technicznego charakteru produkt musi być czytelny.

Wymagania:

- wysoki kontrast tekstów i badge'y,
- focus states dla klawiatury,
- minimum pole kliknięcia dla ważnych kontrolek,
- brak polegania tylko na kolorze,
- ikona + label + tekst statusu,
- liczby w mono dla stabilności percepcyjnej.

## 15. UX Acceptance Criteria

Projekt można uznać za trafiony dopiero, gdy:

1. użytkownik rozumie stan runu w mniej niż 3 sekundy,
2. żadna akcja nie jest pozbawiona feedbacku,
3. formularz treningu prowadzi użytkownika zamiast karać go błędami po submit,
4. `Live Run` daje pełny obraz sytuacji bez przełączania 5 ekranów,
5. wynik końcowy jest natychmiast zrozumiały i prowadzi do sensownych następnych akcji,
6. profile, runs i datasets są rozdzielone poznawczo,
7. advanced options nie przytłaczają quick path,
8. app wygląda jak dopracowane narzędzie inżynierskie, a nie szkic webowego CRUD-a.

## 16. Implementation Priority For UI

Najpierw należy dopracować:

1. global shell,
2. `Train`,
3. `Live Run`,
4. `Run Results Drawer`,
5. `Runs`,
6. `Profiles`,
7. `Datasets`,
8. `Compare`,
9. `Devices`,
10. `Validation`,
11. `Diagnostics`.

Zasada wdrożeniowa:

- najpierw przepływ i czytelność stanów,
- potem wizualne szlify,
- potem analityczne rozszerzenia.

## 17. Final Positioning

`trainer` nie ma być tylko miejscem do kliknięcia `start autotune`.

Ma być:

- cockpitem treningu,
- przeglądarką wiedzy o profilach,
- operacyjnym dashboardem live,
- narzędziem do walidacji jakości,
- interfejsem do analizy datasetów,
- produktem, który wygląda i zachowuje się jak top-tier narzędzie engineerskie.
