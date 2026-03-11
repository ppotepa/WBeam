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

## 18. Global Interaction Contract

Ta sekcja definiuje wspólne zasady dla wszystkich kontrolek i wszystkich ekranów. Dzięki temu opis per-screen może odwoływać się do jednego, spójnego kontraktu.

## 18.1 Control selection rules

Dobór typu kontrolki ma wynikać z natury decyzji, a nie z preferencji implementacyjnej.

### Radio cards

Używamy ich tam, gdzie użytkownik wybiera jeden z 2-5 wariantów o dużym znaczeniu semantycznym.

Przykłady:

- `Quick Tune` vs `Advanced Tune`,
- `single-encoder` vs `multi-encoder`,
- `native` vs `preset` vs `custom` resolution,
- layout density presets w `Settings`.

Zachowanie:

- kliknięcie aktywuje kartę i dezaktywuje pozostałe,
- karta ma pełną powierzchnię kliknięcia,
- aktywny stan ma animowany border glow `160ms`,
- hover ma subtelny lift `120ms`,
- karta nie zmienia wysokości po selekcji.

### Segmented controls

Używamy ich dla małych, częstych przełączeń o niskim opisie tekstowym.

Przykłady:

- filters `All / Active / Failed`,
- chart interval,
- event log severity mode,
- `Table / Cards` view toggle.

Zachowanie:

- suwak aktywnego tła przesuwa się płynnie `160ms`,
- tekst aktywnej opcji wzmacnia kontrast,
- brak bounce i brak skalowania.

### Dropdowns

Używamy ich dla skończonych list opcji, których nie trzeba mieć stale widocznych.

Przykłady:

- `target FPS`,
- `strictness`,
- `retry policy`,
- `diagnostics verbosity`,
- `transport class filter`.

Zachowanie:

- otwarcie listy fade + slide `140ms`,
- klawiatura działa natywnie,
- element aktywny ma badge `current`,
- lista nie może przysłaniać krytycznych CTA jeśli da się tego uniknąć.

### Combobox / searchable dropdown

Używamy go dla dłuższych list z wyszukiwaniem.

Przykłady:

- wybór profilu do porównania,
- wybór datasetu,
- filtrowanie po device model.

Zachowanie:

- typing filtruje listę z debounce `80-120ms`,
- arrow keys działają w pełni,
- brak remote query spinnera, jeśli dane są lokalne,
- przy remote query pokazujemy inline `Searching…`.

### Multi-select chips

Używamy ich dla małych zbiorów opcji, które mogą być włączone równolegle.

Przykłady:

- wybór codec set,
- toggles warstw na wykresach,
- kolumny porównania,
- status filters.

Zachowanie:

- klik aktywuje chip bez zmiany layoutu,
- aktywny chip ma fill + icon tick,
- hover `120ms`,
- aktywacja `140ms`,
- disabled chip zachowuje obrys i ma tooltip z powodem.

### Numeric stepper

To jest podstawowa kontrolka dla wartości technicznych, które muszą być ustawiane precyzyjnie.

Przykłady:

- generations,
- population,
- elite count,
- warmup seconds,
- sample seconds,
- stage budgets,
- width / height.

Zachowanie:

- pole tekstowe + przyciski `- / +`,
- wspiera scroll wheel tylko po focusie,
- walidacja uruchamia się przy blur i podczas wpisywania,
- wartość nie skacze wizualnie przy error state,
- przy nielegalnej wartości pokazujemy inline reason, nie toast.

### Slider

Slider stosujemy tylko tam, gdzie sensowne jest płynne strojenie i szybki gest.

Przykłady:

- mutation rate,
- crossover rate,
- chart smoothing,
- time window,
- UI density override w settings.

Zasada:

- każdy slider ma obok pole numeryczne,
- slider sam nie jest źródłem prawdy,
- użytkownik może wpisać wartość ręcznie.

Zachowanie:

- thumb i fill poruszają się płynnie `120ms`,
- tooltip nad thumb pokazuje dokładną wartość,
- brak opóźnienia przy drag,
- heavy backend recompute nie może blokować samego przesuwania.

### Dual range slider

Używamy go dla przedziałów liczbowych.

Przykłady:

- `bitrate min/max`,
- score filter range,
- date or runtime filter range, jeśli pojawi się sensowna potrzeba.

Zachowanie:

- dwa thumby,
- fill między nimi,
- sprzężenie z dwoma polami liczbowymi,
- jeśli `min > max`, UI pokazuje błąd i blokuje CTA.

### Switch

Używamy go dla boolowskich flag zmieniających zachowanie, ale nie wybierających jednej z wielu strategii.

Przykłady:

- `overlay on device`,
- `auto-open results`,
- `show advanced metrics`,
- `use animations`,
- `compact mode`.

Zachowanie:

- krótki slide `120ms`,
- label zawsze obok,
- jeśli przełączenie ma duży wpływ, switch otwiera helper callout.

### Checkbox

Checkbox używamy tylko dla lokalnych, równoległych flag i list zaznaczeń.

Przykłady:

- zaznacz kolumny w tabeli,
- wybór encoderów jeśli użyty zostanie layout tabelaryczny,
- bulk actions w runs/profiles.

Zachowanie:

- mały check animation `100ms`,
- nie używamy checkboxa tam, gdzie lepsza jest chipowa semantyka.

### Accordion

Accordion służy do chowania złożonych sekcji.

Przykłady:

- advanced options,
- expert encoder params,
- technical diagnostics,
- raw JSON / artifact previews.

Zachowanie:

- expand/collapse `180-220ms`,
- strzałka rotuje `160ms`,
- animacja tylko na wysokości i opacity,
- brak gwałtownego przeskoku reszty layoutu.

### Tabs

Tabs stosujemy w obrębie jednego ekranu, gdy przełączamy blisko powiązane podwidoki.

Przykłady:

- `Overview / Trials / Events / Files` w detail drawer,
- `Charts / Table / Matrix` w compare analytics.

Zachowanie:

- underline lub active pill,
- przejście contentu `fade 140ms`,
- brak pełnego reflow całego ekranu.

### Drawer / side pane

Drawer służy do wejścia w szczegóły bez gubienia kontekstu głównego widoku.

Przykłady:

- run detail,
- profile detail,
- dataset detail,
- current trial detail.

Zachowanie:

- slide-in `220ms`,
- backdrop tylko jeśli overlay modalny,
- Escape zamyka drawer,
- focus wraca do elementu źródłowego.

## 18.2 Validation severity model

Każde ograniczenie formularza lub widoku musi należeć do jednego z trzech poziomów.

### Hard block

Blokuje akcję główną.

Przykłady:

- brak wybranego urządzenia,
- brak żadnego codec,
- `min bitrate > max bitrate`,
- `elite_count >= population`,
- daemon unreachable,
- selected device offline.

Zachowanie:

- CTA disabled,
- powód widoczny inline,
- opcjonalny tooltip przy disabled button,
- brak ukrytego fallbacku.

### Soft warning

Nie blokuje akcji, ale wymaga widocznego ostrzeżenia.

Przykłady:

- bardzo wysoki bitrate względem baseline,
- zbyt krótki sample time,
- mała liczba generacji,
- profil o tej nazwie już istnieje.

Zachowanie:

- CTA enabled,
- warning banner lub helper text,
- czasem secondary confirmation.

### Derived override

System zmienia wartość lub zakres na podstawie capability/preflight.

Przykłady:

- clamp bitrate max,
- ukrycie nieobsługiwanego kodeka,
- zawężenie resolution options,
- obniżenie dostępnych FPS dla danego device path.

Zachowanie:

- użytkownik musi widzieć starą i efektywną wartość,
- powód musi być zapisany i opisany,
- nie może to być cicha zmiana.

## 18.3 Required animation rules

Każda kontrolka musi mieć jasno określony status: animowana lub nieanimowana.

### Always animated

- hover states kart i buttonów,
- segmented controls,
- drawers,
- accordions,
- progress bars,
- skeleton shimmer,
- KPI update flash.

### Animated only subtly

- toasts,
- chart line updates,
- filter chip selection,
- badge status transitions.

### Never animated

- tabele z dużą ilością danych przy sortowaniu masowym,
- krytyczne error banners,
- raw logs,
- numeric cells aktualizowane bardzo często.

Powód:

- zbyt dużo ruchu w narzędziu operatorskim pogarsza czytelność.

## 18.4 Microinteraction timings

Rekomendowane czasy:

- hover in/out: `100-140ms`,
- press: `80-120ms`,
- selection highlight: `140-180ms`,
- drawer enter/exit: `180-220ms`,
- accordion expand/collapse: `180-220ms`,
- panel fade: `160-200ms`,
- toast enter/exit: `180ms`,
- skeleton shimmer cycle: `1200-1600ms`.

Krzywe:

- preferowane `ease-out` dla wejścia,
- `ease-in-out` dla przełączeń,
- brak sprężyn i bounce.

## 18.5 Focus, keyboard and selection model

Wszystkie główne kontrolki muszą wspierać pracę klawiaturą.

Wymagania:

- Tab order logiczny i przewidywalny,
- Enter aktywuje główne CTA tylko w odpowiednim kontekście,
- Escape zamyka drawery, modale i dropdowny,
- arrow keys działają w segmented controls, listach i comboboxach,
- focus ring jest wyraźny i zgodny z akcentem.

## 18.6 Loading ownership model

Nie wszystkie loadery są globalne.

Poziomy loaderów:

- global:
  - bootstrap app,
  - full blocking health gate,
  - initial dataset hydration
- section-level:
  - refresh devices,
  - loading profiles,
  - recompute dataset
- control-level:
  - start preflight button,
  - save profile button,
  - run validation button

Zasada:

- preferować lokalne loadery zamiast blokować cały ekran.

## 19. Screen-by-Screen Interaction Spec

## 19.1 Service Unavailable / Bootstrap Screen

### Global behavior

- ekran jest full-page state,
- blokuje nawigację do `Train`,
- pozwala przejść do `Diagnostics`,
- nie wyświetla pustych chart placeholders.

### Controls

#### `Start Service`

- typ: primary CTA button,
- wymagane dane: żadne,
- blokuje się tylko, gdy trwa już próba startu,
- animacja:
  - hover `120ms`,
  - loading spinner inline,
  - success state `180ms` z przejściem na zielony badge.

#### `Retry`

- typ: secondary button,
- aktywuje health refresh,
- jeśli request trwa, button pokazuje `Checking…`,
- nie blokuje `Open Diagnostics`.

#### `Open Diagnostics`

- typ: tertiary button / link button,
- zawsze dostępny,
- przejście ekranowe `fade 160ms`,
- nie powinno resetować kontekstu bootstrapa.

#### `Open Logs`

- typ: secondary quiet button,
- jeśli brak znanej ścieżki logu, jest disabled z tooltipem.

### Blocking rules

- `Train` odblokowuje się dopiero przy `daemon ok`,
- jeśli `daemon ok` ale `adb no devices`, `Train` działa w degraded state.

## 19.2 Train Screen

### Screen behavior

- ekran jest głównym miejscem konfiguracji,
- wspiera `Quick Tune` i `Advanced Tune`,
- pola advanced nie mogą przebijać quick path,
- sticky footer pokazuje status gotowości runu.

### Device selector

- typ kontrolki: selectable card list z radio behavior,
- źródło danych: lista urządzeń ADB,
- wymagane dla:
  - `Run Preflight`,
  - `Start Training`
- blokuje:
  - preflight i start jeśli nie wybrano żadnego `device`,
  - start jeśli selected device ma stan inny niż `device`
- animacja:
  - hover glow `120ms`,
  - selected border/fill `160ms`,
  - brak resize.

### Tune mode selector

- typ kontrolki: radio cards,
- opcje:
  - `Quick Tune`
  - `Advanced Tune`
- wymagane: tak, ale ma domyślną wartość,
- blokady: brak,
- wpływ:
  - `Quick Tune` ukrywa expert parameters,
  - `Advanced Tune` odsłania advanced accordion i detailed controls.

### Profile name

- typ kontrolki: text input,
- wymagane dla:
  - `Start Training`
- niewymagane dla:
  - `Run Preflight`
- walidacja:
  - non-empty,
  - trimmed,
  - tylko bezpieczne znaki,
  - długość sensowna,
  - kolizja nazwy oznaczana jako soft warning lub overwrite flow.

Zachowanie:

- debounced validation `200ms`,
- przy kolizji pokazuje badge `existing`,
- może oferować auto-suffix.

### Description / tags

- typ kontrolki: text input / token input,
- opcjonalne,
- nie blokują niczego,
- brak ciężkich animacji,
- helper text wyjaśnia, że to metadata dla późniejszego browse/filter.

### Goal mode

- typ kontrolki: segmented control lub radio cards,
- opcje:
  - `max_quality`
  - `balanced`
  - `low_latency`
- wymagane: tak,
- ma domyślną wartość,
- wpływa na scoring preset i recommendation text,
- zmiana powinna płynnie aktualizować helper panel `What this mode optimizes`.

### Encoder mode

- typ kontrolki: segmented control,
- opcje:
  - `single-encoder`
  - `multi-encoder`
- wymagane: tak,
- ma domyślną wartość,
- wpływ:
  - w `single-encoder` tylko jeden codec może być aktywny,
  - w `multi-encoder` możliwe jest wiele codec chips.

### Codec selector

- typ kontrolki: multi-select chips,
- wymagane:
  - minimum jeden codec aktywny,
  - dokładnie jeden codec aktywny gdy `single-encoder`
- blokuje:
  - preflight/start jeśli zero codec,
  - start jeśli `single-encoder` i zaznaczono więcej niż jeden.

Zachowanie:

- unsupported codecs są disabled z tooltipem,
- select/deselect `140ms`,
- chips nie zmieniają rozmiaru w zależności od stanu.

### Target FPS

- typ kontrolki: dropdown,
- opcje:
  - domyślne bezpieczne presets,
  - opcjonalny `Custom` w advanced mode.
- wymagane: tak,
- blokuje preflight/start jeśli FPS wypada poza dozwolone capability.

Dlaczego dropdown:

- FPS to skończona lista sensownych wartości,
- slider byłby mniej precyzyjny i mniej czytelny.

### Resolution mode

- typ kontrolki: radio cards,
- opcje:
  - `Native`
  - `Preset`
  - `Custom`
- wymagane: tak,
- ma domyślną wartość.

### Resolution preset

- typ kontrolki: dropdown,
- widoczne tylko przy `Preset`,
- wymagane jeśli `Preset` aktywne,
- lista zależy od capability device i policy runu.

### Custom width / height

- typ kontrolki: numeric stepper + input,
- widoczne tylko przy `Custom`,
- wymagane gdy `Custom` aktywne,
- blokuje preflight/start jeśli:
  - zero,
  - negative,
  - out of allowed bounds,
  - nieparzyste rozmiary przy encoderach, które tego nie tolerują.

### Bitrate min / max

- typ kontrolki: dwa numeric steppers + dual range slider,
- wymagane: tak,
- blokuje:
  - jeśli `min > max`,
  - jeśli wartości są poza capability hard limits,
  - jeśli wartości są nielogicznie małe / duże dla wybranego preset path.

Zachowanie:

- wpis w polu aktualizuje slider,
- drag slidera aktualizuje pola,
- jeśli clamp z preflightu zmienia zakres, UI pokazuje before/after.

Dlaczego dual control:

- inżynier potrzebuje zarówno szybkości slidera, jak i precyzji liczbowej.

### Generations

- typ kontrolki: numeric stepper,
- wymagane: tak,
- blokuje start jeśli `<= 0`,
- nie używać slidera, bo wymagana jest precyzja i małe znaczenie gestu.

### Population

- typ kontrolki: numeric stepper,
- wymagane: tak,
- blokuje start jeśli `<= 0`.

### Elite count

- typ kontrolki: numeric stepper,
- wymagane: tak,
- blokuje start jeśli `elite_count >= population`,
- helper text musi wyjaśniać zależność.

### Mutation rate

- typ kontrolki: slider + numeric input,
- wymagane: tak,
- blokuje start jeśli poza dozwolonym zakresem,
- helper text pokazuje recommended range.

Animacja:

- slider smooth `120ms`,
- numeric field bez animacji.

### Crossover rate

- typ kontrolki: slider + numeric input,
- zasady analogiczne do `Mutation rate`.

### Stage budgets

- typ kontrolki: grupa numeric steppers,
- pola:
  - floor,
  - ceiling,
  - fine,
  - sustained.
- wymagane: tak,
- start blokowany jeśli suma budżetów jest zerowa lub wartości są nielegalne.

### Advanced options

- typ kontrolki: accordion,
- default: collapsed,
- rozwinięcie nie może resetować wpisanych wartości,
- animacja: `180-220ms`.

### `Run Preflight`

- typ kontrolki: primary button,
- wymaga:
  - service healthy,
  - selected device,
  - valid technical config,
  - przynajmniej jeden codec
- nie wymaga `profile_name`.

Busy behavior:

- button label zmienia się na `Running preflight…`,
- sekcje formularza pozostają czytelne,
- tylko pola zależne od preflight mogą się czasowo lockować.

### `Start Training`

- typ kontrolki: primary emphasis CTA,
- wymaga:
  - service healthy,
  - selected device,
  - valid config,
  - valid `profile_name`,
  - pozytywnego preflightu lub jawnego bypass confirmation.

Busy behavior:

- button label `Starting run…`,
- sticky footer pokazuje krok po kroku:
  - validating,
  - creating run,
  - switching to Live Run.

### Sticky footer

- pokazuje readiness summary,
- zawiera:
  - hard blocks,
  - soft warnings,
  - estimated duration,
  - main CTA area.

Animacja:

- subtelne highlighty przy zmianie statusu,
- brak layout shift.

## 19.3 Preflight Review

### Screen behavior

- ekran przejściowy, ale ważny,
- pokazuje różnicę między requested config a effective config,
- wymaga decyzji użytkownika.

### Warning list

- typ kontrolki: stacked warning cards,
- brak edycji inline,
- każda karta zawiera:
  - severity,
  - reason,
  - impacted field,
  - recommendation.

### Effective config preview

- typ kontrolki: read-only key-value table,
- pola zmienione przez system mają badge `clamped` lub `adjusted`.

### `Accept & Start`

- typ kontrolki: primary CTA,
- aktywna tylko gdy nie ma unresolved hard blocks.

### `Back To Edit`

- typ kontrolki: secondary button,
- wraca do `Train` bez utraty wpisanych danych,
- przejście `fade 160ms`.

### `Auto-fix and Continue`

- typ kontrolki: secondary emphasis,
- aktywna tylko gdy system ma gotowy zestaw bezpiecznych poprawek,
- po kliknięciu update'uje formularz i startuje run.

## 19.4 Live Run Screen

### Screen behavior

- ekran ma najwyższy priorytet czytelności,
- nic ważnego nie może wymagać głębokiego scrolla,
- auto-updating sections nie mogą skakać wysokością.

### Left pane controls

#### Frozen config panel

- typ: read-only panel,
- brak animacji poza początkowym wejściem,
- pola można kopiować,
- wartości nie zmieniają się w czasie runu.

#### `Pause Run`

- typ: secondary button,
- jeśli backend nie wspiera pause, button jest hidden albo disabled z jasnym tooltipem,
- jeśli wspierane:
  - klik wprowadza stan `Pausing…`,
  - button przechodzi w `Resume`.

#### `Stop Run`

- typ: danger button,
- zawsze widoczny,
- wymaga lekkiego confirmation tylko gdy run już wykonał znaczną pracę,
- confirmation może być inline popover, nie pełny modal jeśli to niepotrzebne.

#### `Mark Note`

- typ: small action button,
- otwiera compact inline composer,
- nie blokuje runu,
- note trafia do event timeline.

### Run header

- wartości w headerze aktualizują się live,
- zmiany stanu mają krótką animację koloru i opacity,
- same liczby nie mają pulsować bez potrzeby.

### KPI cards

- typ: read-only metric cards,
- interakcje:
  - hover pokazuje breakdown tooltip,
  - click może pinować kartę do larger chart focus.

Animacja:

- number update flash `120ms`,
- mini sparkline live draw,
- bez przeskalowania karty.

### Charts

- hover: crosshair + tooltip,
- click legendy: show/hide series,
- optional brush zoom,
- `Reset zoom` jako small button,
- live stream nie może gubić scroll/zoom contextu bez zgody użytkownika.

### Event log

- typ: virtualized log list,
- auto-scroll domyślnie włączony,
- jeśli user scrolluje w górę, auto-scroll się wstrzymuje,
- przycisk `Jump to latest` przywraca flow.

### Trial leaderboard

- typ: dense table,
- sortowanie lokalne,
- selected row otwiera detail pane,
- brak animowanego resortowania przy każdej próbce; tylko przy snapshot updates.

### Gate matrix

- typ: matrix/table,
- każda komórka:
  - badge status,
  - actual value,
  - threshold,
  - hover explanation.

### Busy and failure handling

- jeśli trial restartuje się lub czeka na dane, centralny status banner mówi to jawnie,
- nie pokazujemy pustego wykresu bez opisu,
- jeżeli metryki są stale, pojawia się badge `stale`.

## 19.5 Run Results Drawer

### Behavior

- otwiera się automatycznie po runie,
- nie wyrywa użytkownika z kontekstu, jeśli jest w detail mode i wyłączył auto-open,
- może być zamknięty i przywrócony.

### Winner cards

- typ: card stack,
- global winner ma większą wagę wizualną,
- per-encoder winners mają mniejsze, symetryczne karty.

### Action buttons

- `Open Profile`: primary,
- `Open Dataset`: secondary,
- `Find Optimal Best`: secondary emphasis,
- `Compare With Baseline`: tertiary.

### Blocking rules

- `Find Optimal Best` disabled, jeśli dataset incomplete,
- `Open Profile` disabled, jeśli run nie wyeksportował profilu.

## 19.6 Runs Screen

### Search and filters

- search box: debounced text input `160ms`,
- filters: dropdowns + chips,
- active filters widoczne jako removable pills.

### Runs table

- typ: dense data table,
- selected row ma trwały highlight,
- sort icons animują się `120ms`,
- pagination lub virtualized scrolling przy dużej liczbie wpisów.

### Row actions

- quick actions w ostatniej kolumnie:
  - open,
  - compare,
  - dataset,
  - validate.
- action icons pokazują tooltip.

### Detail drawer

- zakładki:
  - `Overview`
  - `Stages`
  - `Trials`
  - `Events`
  - `Files`
- transitions `fade 140ms`.

## 19.7 Profiles Screen

### View mode toggle

- typ: segmented control,
- opcje:
  - `Cards`
  - `Table`
- pamięta ostatni wybór użytkownika.

### Search / filter

- identyczny kontrakt jak w `Runs`,
- stale / validated / compatibility jako chips.

### Profile cards

- hover pokazuje quick summary,
- selected card otwiera detail drawer,
- karty nie mogą mieć nierównych wysokości wynikających z długiego tekstu.

### Actions

- `Validate`: primary jeśli profil wybrany,
- `Export`: secondary,
- `Apply`: secondary emphasis,
- `Compare`: enabled po zaznaczeniu min. dwóch profilów.

### Blocking rules

- `Apply` disabled jeśli compatibility fail,
- `Validate` disabled jeśli brak ready device.

## 19.8 Datasets Screen

### Dataset table

- typ: dense table z badge-rich columns,
- integrality / schema / scoring version zawsze widoczne bez otwierania rekordu.

### Filters

- device,
- profile,
- date,
- integrity,
- scoring version,
- has recompute result.

### `Find Optimal Best`

- typ: primary row action w detail pane lub table action,
- wymaga:
  - complete dataset,
  - supported scoring model,
  - brak aktywnego recompute dla tego datasetu.

Busy behavior:

- button label `Recomputing…`,
- detail pane pokazuje progress phases,
- po zakończeniu diff card highlightuje zmiany.

### Trial explorer

- typ: tabs + chart/table,
- wybór triala nie może przerywać głównej listy.

## 19.9 Compare Screen

### Selection controls

- typ: combobox / searchable dropdown per comparison slot,
- minimalnie 2 wybrane obiekty,
- maksymalnie 3.

Blocking rules:

- `Compare` analysis render nie startuje przy mniej niż 2 obiektach.

### Overlay toggles

- typ: chips,
- serie:
  - FPS,
  - latency,
  - drop,
  - bitrate,
  - confidence,
  - score.

### Recommendation panel

- typ: summary card,
- aktualizuje się po zmianie selection/filter,
- brak animacji większej niż fade `160ms`.

## 19.10 Devices Screen

### Device cards/table

- użytkownik może przełączać `Cards / Table`,
- selected device dostaje persistent outline.

### Actions

- `Probe`: secondary button,
- `Quick Preflight`: secondary emphasis,
- `Use For New Training`: primary,
- `Open Recent Runs`: tertiary.

### Blocking rules

- akcje runtime disabled gdy device nie jest `device`,
- tooltip mówi:
  - `offline`,
  - `unauthorized`,
  - `missing reverse support`,
  - inne przyczyny.

## 19.11 Validation Screen

### Required inputs

- selected profile,
- selected device.

### Controls

- profile chooser: searchable combobox,
- device chooser: dropdown lub selectable list,
- validation mode: segmented control,
- duration: numeric stepper,
- `Run Validation`: primary CTA.

### Blocking rules

- bez profilu lub device CTA disabled,
- jeśli profil jest incompatible z device, pokazujemy hard block.

## 19.12 Diagnostics Screen

### Behavior

- mostly read-only,
- dużo danych technicznych,
- brak dekoracyjnych animacji,
- tylko sekcje expandable.

### Controls

- `Refresh diagnostics`: secondary button,
- `Copy path`: quiet icon button,
- `Run quick bench`: secondary emphasis,
- `Open log`: tertiary button.

### Motion

- minimum motion,
- diagnostyka ma być stabilna jak terminal z lepszą oprawą.

## 19.13 Settings Screen

### Controls

- density: radio cards,
- theme accents: dropdown,
- chart refresh rate: dropdown,
- default live chart window: dropdown,
- animations enabled: switch,
- advanced feature flags: accordion + switches.

### Rules

- settings nie mogą resetować aktywnego runu,
- zmiany wyłącznie prezentacyjne wchodzą live,
- zmiany mogące wpływać na backend wymagają explicit apply.

## 20. Screen Transition Rules

### Allowed screen transitions

- `Train -> Preflight Review`
- `Preflight Review -> Live Run`
- `Live Run -> Run Results Drawer`
- `Runs -> Run Detail`
- `Profiles -> Profile Detail`
- `Datasets -> Dataset Detail`

### Transition style

- full screen change: `fade 160-200ms`,
- side pane open: `slide 220ms`,
- modal confirmation: `fade + scale 140ms`,
- no parallax,
- no spring.

## 21. Field Dependency Matrix

Najważniejsze zależności między polami:

- `device` blokuje wszystko operacyjne,
- `profile_name` blokuje tylko start runu, nie preflight,
- `encoder_mode=single` wymusza dokładnie jeden codec,
- `resolution_mode=custom` aktywuje `width` i `height`,
- `bitrate range` wpływa na bitrate slider i tested ladder,
- `population` ogranicza `elite_count`,
- `advanced mode` odblokowuje expert parameters,
- `service down` blokuje preflight i start niezależnie od reszty,
- `dataset incomplete` blokuje recompute,
- `profile incompatible` blokuje apply/validate.

## 22. Rationale

Powód tak szczegółowego kontraktu jest prosty:

- trener ma być narzędziem do pracy precyzyjnej,
- użytkownik nie może zgadywać stanu UI,
- każda kontrolka ma mieć uzasadniony typ,
- każda blokada ma mieć jawny powód,
- każda animacja ma wspierać orientację, a nie ozdabiać chaos,
- każda zmiana stanu ma być wizualnie czytelna i poznawczo tania.
