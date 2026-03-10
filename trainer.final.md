# WBeam Trainer Final Blueprint

Last updated: 2026-03-10  
Status: Canonical merged blueprint for implementation (derived from `trainer.md` and `trainer.sup.md`)

---

## 1. Purpose

`trainer` is a dedicated operational product surface for WBeam focused on one domain: **training, optimisation, validation, persistence, comparison, and lifecycle management of Android secondary-screen streaming profiles**.

Its role is to turn profile tuning from an opaque script-driven process into a repeatable, observable, data-rich workflow that:

- benchmarks the full host → transport → Android decode/render path,
- automatically discovers strong configurations,
- explains why a configuration won or failed,
- persists reproducible profile artifacts with full metadata,
- exposes live tuning telemetry in a proper dashboard,
- supports reuse, comparison, validation, regression detection, and future re-training.

Trainer is not just “autotune”. It is a **profile intelligence system**.

---

## 2. Objectives

Trainer should produce the best practical streaming profile for a selected device and target mode while ensuring:

- **repeatability** — same device + same conditions can reproduce comparable results,
- **observability** — live HUD, charts, metrics, score breakdowns, failure reasons,
- **auditability** — every decision and artifact is persisted,
- **safety** — unstable configs are rejected early and explicitly,
- **portability** — profiles can be browsed, compared, exported, and revalidated,
- **adaptivity** — search responds to observed link/device behaviour,
- **production realism** — chosen profiles are not merely peak performers for 20 seconds; they must remain stable under sustained operation.

### 2.1 Scope summary

In scope:

- dedicated GUI app called `trainer`,
- launcher entrypoint `./trainer.sh`,
- dependency on running daemon service,
- full live HUD with score and charts,
- persistent profile artifacts and per-run artifacts,
- multi-codec support for `h264`, `h265`, `mjpeg`, and optional `rawpng` diagnostics,
- comparison, validation, replay, and explainability flows.

Out of scope for initial delivery:

- replacing `./wbeam train wizard` immediately,
- cloud orchestration,
- distributed training,
- external profile registry publishing,
- fleet management or account systems.

---

## 3. Non-Goals

The following are explicitly out of scope for the initial implementation:

- replacing `./wbeam train wizard` immediately,
- cloud/orchestrated training farms,
- training across multiple hosts at once,
- remote profile registry publishing,
- user account concepts,
- fully autonomous background retraining,
- ML-based perceptual quality scoring using video content analysis,
- external fleet management.

These may be added later, but the initial product should stay focused on **single-host, local, deterministic profile training**.

---

## 4. Current State and Existing Foundations

### 4.1 Domain reorganisation already completed

Training logic has already been moved to the main domain lane:

- `src/domains/training/wizard.py`
- `src/domains/training/legacy_engine.py`
- `src/domains/training/train_max_quality.sh`

Compatibility wrappers remain under `proto/`:

- `proto/autotune.py`
- `proto/train-autotune-max-quality.sh`

### 4.2 Canonical profile file path

The canonical profile registry file is:

- `config/training/profiles.json`

### 4.3 Existing fixes already in place

- Wizard now executes a realistic training flow.
- Legacy engine sanitizes inherited environment before invoking `proto/run.sh`.
- This prevents blocked runtime env override errors.

These serve as the base layer for the new Trainer system.

---

## 5. Product Vision

Trainer becomes a **first-class operational app**.

### 5.1 High-level user flow

1. User selects a device and training intent.
2. Trainer probes device capabilities and transport conditions.
3. Trainer performs mandatory preflight benchmarks.
4. Trainer derives safe search bounds from measured behaviour.
5. Trainer executes staged optimisation with live telemetry and scoring.
6. Trainer performs validation and sustained-stability verification.
7. Trainer persists full artifacts into deterministic profile/run directories.
8. User reviews, compares, validates, exports, or applies resulting profiles.

### 5.2 Product philosophy

Trainer must always answer these questions clearly:

- What is happening right now?
- Why is this config being tried?
- Why was this config rejected?
- Why did this profile win?
- How confident are we in the result?
- How does this result compare to previous runs?
- Is this result still valid after software or device changes?

### 5.3 Concrete repo mapping

The initial implementation should map cleanly to the current repository structure:

- GUI app: `src/apps/trainer-tauri`
- launcher: `./trainer.sh`
- trainer domain/orchestration: `src/domains/training/`
- daemon-facing API/backend integration: `src/host/rust/`
- compatibility bridge during migration: `proto/`

The intent is to keep the Trainer app as the primary product surface while reusing existing domain and proto execution code where that reduces risk during the first implementation passes.

---

## 6. Core Design Principles

### 6.1 Deterministic artifacts
Every run must generate a complete, traceable artifact set.

### 6.2 Fail loudly
Silent fallback, silent clobber, silent downgrade, and hidden rejection are not acceptable.

### 6.3 Production realism over benchmark vanity
A profile that peaks briefly but degrades thermally or under slight jitter must not be treated as best.

### 6.4 Search-space discipline
Search must be bounded by real measured device and transport limits.

### 6.5 Explainability
Scores, gates, penalties, and selected winners must be understandable.

### 6.6 Layer separation
GUI, orchestration, scoring, telemetry, and execution must remain separable for future migration.

---

## 7. Runtime Dependency Model

Trainer depends on host daemon availability.

### 7.1 Hard requirement

Control API must be reachable:

- `http://127.0.0.1:5001/v1/health`

Expected health response includes at minimum:

- service status,
- version,
- build revision,
- API compatibility version.

### 7.2 Behaviour when daemon is unavailable

Trainer opens in a **blocking service-unavailable state**.

Allowed actions:

- `Start Service`
- `Retry`
- `Open Logs`
- `Diagnostics`

Training cannot start until health is `ok`.

### 7.3 Compatibility checks before unlock

Trainer should additionally verify:

- daemon API version compatibility,
- matching trainer/backend schema version,
- availability of required subcomponents,
- optional warnings for outdated host or app build revisions.

---

## 8. System Architecture

Trainer should be organised into the following layers.

### 8.1 GUI layer
Responsible for:

- form entry,
- live dashboard rendering,
- artifact browsing,
- profile/runs comparison,
- devices overview,
- validation and export actions.

Suggested app scaffold:

- `src/apps/trainer-tauri`

### 8.2 Trainer API layer
Responsible for:

- preflight orchestration,
- run start/stop,
- status queries,
- artifact indexing,
- live event streaming,
- device capability reporting,
- validation requests,
- export/import endpoints.

### 8.3 Domain orchestration layer
Responsible for:

- search strategy,
- trial scheduling,
- score calculation,
- rejection logic,
- stage transitions,
- checkpointing,
- result selection,
- persistence.

### 8.4 Execution layer
Responsible for:

- invoking stream pipeline,
- controlling codec/runtime params,
- collecting metrics,
- handling subprocess lifecycle,
- environment sanitization.

### 8.5 Artifact layer
Responsible for:

- deterministic directory layout,
- schema versioning,
- integrity checks,
- appending run history,
- export/import packaging,
- migration helpers.

---

## 9. Trainer Information Architecture

Recommended app sections:

1. `Train`
2. `Live HUD`
3. `Profiles`
4. `Runs`
5. `Compare`
6. `Devices`
7. `Validation`
8. `Diagnostics`
9. `Settings`

### 9.1 Train
Configure and launch a new training run.

### 9.2 Live HUD
Primary live optimisation dashboard with metrics, charts, score, rank, gates, and trial state.

### 9.3 Profiles
Persistent profile browser grouped by profile name, device, codec, goal mode, or status.

### 9.4 Runs
Historical run browser with timeline, status, duration, and artifact access.

### 9.5 Compare
Side-by-side profile and run comparison.

### 9.6 Devices
ADB device inventory and capability probes.

### 9.7 Validation
Re-run profile verification without full retraining.

### 9.8 Diagnostics
Low-level health, environment, logs, throughput tests, codec support, and troubleshooting.

### 9.9 Settings
Global defaults, retention policy, scoring presets, export location, and advanced toggles.

---

## 10. Training Input Contract

Each run must include explicit user or system-resolved input.

Required:

- `serial` — ADB target device serial
- `profile_name` — user-provided profile identifier
- `goal_mode` — one of:
  - `max_quality`
  - `balanced`
  - `low_latency`
- `codec_set` — subset of:
  - `h264`
  - `h265`
  - `mjpeg`
  - optional `rawpng`
- `target_fps`
- `resolution_mode`
  - `native`
  - `custom`
- `trial_budget`
  - max trials
  - max runtime
  - or both

Optional expert overrides:

- warmup seconds
- sample seconds
- mutation rate
- exploration ratio
- elite count
- stage time allocation
- codec-specific parameter range overrides
- scoring preset
- strictness preset
- thermal verification duration
- validation pass count
- profile overwrite policy

---

## 11. Device Capability Model

Before full search begins, Trainer should derive a per-device capability profile.

### 11.1 Capability dimensions

The capability model should include, where available:

- ADB serial
- manufacturer
- model
- Android API level
- Android release
- SoC family
- GPU renderer string
- decoder capabilities
- hardware decode availability by codec
- max tested stable decode size
- max tested stable decode fps
- transport class
  - USB 2
  - USB 3
  - TCP over ADB
  - Wi-Fi
- thermal class estimate
- display refresh rate
- measured baseline latency class
- measured throughput class

### 11.2 Purpose of the capability model

The capability model is used to:

- block impossible codec combinations,
- shrink wasteful search space,
- apply initial search priors,
- label profiles by device class,
- aid future recommendation and cross-run comparison.

### 11.3 Capability confidence

Capability fields should carry confidence/source tags where useful:

- `declared`
- `probed`
- `measured`
- `inferred`

---

## 12. Preflight Diagnostics (Mandatory)

No optimisation run should begin without successful preflight.

---

## 12.1 Goals of preflight

Preflight must:

- verify the device is reachable and authorised,
- measure transport behaviour,
- establish baseline stream health,
- derive initial search bounds,
- identify obvious blockers,
- classify the current environment quality,
- persist the results.

---

## 12.2 ADB Link Benchmark

### Required tests

#### Push throughput
Write test payloads to `/data/local/tmp`.

Metrics:

- mean throughput MB/s
- p50 throughput
- p95/p05 variation if sampling allows
- retry count
- error count

#### Optional pull throughput
Useful for asymmetry diagnostics.

#### Shell RTT benchmark
Repeated `adb shell true`.

Metrics:

- p50 RTT
- p95 RTT
- max RTT
- jitter estimate

#### Session stability checks
Track:

- disconnects
- command failures
- authorisation state changes
- device offline transitions

---

## 12.3 Stream Baseline Benchmark

Run a baseline stream for 60–90 seconds before optimisation.

### Baseline metrics

- sender fps
- pipeline fps
- recv fps
- decode fps
- present fps
- p50/p10 fps values
- drop count
- late frame count
- timeout count
- e2e latency p50/p95
- decode latency p95
- render latency p95
- queue depth behaviour
- bitrate utilisation
- backpressure indicators
- frame delivery smoothness
- thermal drift during baseline if observable

### Baseline classification

Baseline result should be classified:

- `stable`
- `warning`
- `unstable`
- `failed`

If unstable, Trainer should suggest or automatically apply search-space reductions before Stage A.

---

## 12.4 Bound Derivation

Preflight must compute search-space constraints from measured behaviour.

Examples:

### Bitrate ceiling seed

```text
initial_bitrate_ceiling_kbps = safe_fraction * measured_transport_throughput_kbps
````

### Stability floor seed

If present/decode fps is already below target:

* reduce initial bitrate,
* reduce search resolution,
* reduce search fps ceiling,
* deprioritize heavy codecs.

### Transport risk factor

High shell RTT or unstable throughput should reduce aggressiveness of bitrate search.

### Decoder risk factor

Poor baseline decode or render latency should constrain resolution/fps combinations.

---

## 12.5 Link Quality Tier

Preflight should classify link quality:

* `poor`
* `ok`
* `good`
* `excellent`

This tier should influence:

* initial search space,
* recommended codec priority,
* suggested bitrate headroom,
* warning badges in UI.

---

## 12.6 Preflight Output

Preflight results must be visible in:

* console/log stream,
* Live HUD preflight phase,
* persisted JSON artifact,
* device detail view.

---

## 13. Search Space Design

Search quality depends heavily on a disciplined search space.

### 13.1 Search space dimensions

Candidate dimensions may include:

* codec
* width
* height
* fps
* bitrate
* keyframe interval
* GOP structure
* B-frames / reorder
* queue sizes
* pull intervals
* keepalive behaviour
* appsink buffering
* duplicate/drop policy
* encoder speed/quality preset
* rate control mode
* lookahead
* packet/chunk sizing
* recovery/retry parameters

### 13.2 Codec-specific search spaces

#### H264

Typical tunables:

* bitrate
* keyint
* B-frames
* profile
* level
* zerolatency tune
* lookahead
* reorder settings

#### H265

Typical tunables:

* bitrate
* keyint
* B-frames
* CTU-related constraints
* lookahead
* low-delay options
* reorder controls

#### MJPEG

Typical tunables:

* JPEG quality
* frame pacing
* packetisation behaviour
* queue buffering

#### RAWPNG

Used as diagnostics rather than production.
Its search space should stay narrow and intentionally explicit.

### 13.3 Conditional dimensions

Some parameters should only be legal when specific codec/runtime conditions apply.

### 13.4 Search-space provenance

Persist exactly how the search space was derived:

* defaults,
* user overrides,
* device clamps,
* preflight clamps,
* backend-enforced clamps.

---

## 14. Optimisation Pipeline

The pipeline should remain staged but more adaptive than a simple fixed sequence.

---

## 14.1 Stage 0 — Capability + Preflight

* capability probe
* ADB benchmark
* baseline stream
* bounds derivation
* initial score model instantiation

---

## 14.2 Stage A — Stability Floor Discovery

Goal: find the smallest stable operating region.

Method:

* explore conservative candidates,
* reject obviously unstable configurations quickly,
* identify a viable stability region.

This stage should strongly prefer pass/fail confidence over high bitrate.

---

## 14.3 Stage B — Bitrate Ceiling Discovery

Goal: estimate highest stable bitrate for candidate codec/fps/size combinations.

Method:

* coarse ramp-up,
* local probing near instability,
* binary or ternary search near edge,
* repeated confirmation around suspected ceiling.

This should produce:

* `max_stable_kbps`
* `failure_boundary_kbps`
* `confidence_interval`
* `instability_signature`

---

## 14.4 Stage C — Local Fine Tuning

Goal: optimise around strong stable candidates.

Method:

* mutate around elites,
* tune codec-specific knobs,
* refine queue/reorder/GOP settings,
* assess latency/quality/stability tradeoffs.

---

## 14.5 Stage D — Sustained Verification

Goal: reject short-lived “hero configs”.

Run winner candidates for longer validation windows, e.g.:

* 180 seconds
* or mode/configurable

Track:

* fps drift
* latency drift
* timeout growth
* drop growth
* queue saturation growth
* thermal degradation

---

## 14.6 Stage E — Final Ranking and Selection

Produce:

* winner,
* Pareto frontier,
* top alternatives,
* reject reason summary,
* confidence score,
* production bitrate with safety headroom.

---

## 14.7 Reference optimisation strategy

To avoid a weak “mutate everything forever” trainer, the reference search strategy should be explicitly hybrid:

1. Capability-clamped candidate generation.
2. Conservative floor discovery to establish viable operating region.
3. Bitrate ceiling discovery using ramp-up plus binary search near the failure edge.
4. Local exploitation around viable elites.
5. Sustained validation for top candidates.
6. Final mode-specific ranking plus Pareto preservation.

The most important implementation rule is that bitrate should not be guessed from a static list alone. Trainer should actively discover the highest stable region for the exact device, codec, resolution, FPS target, and current transport conditions.

### 14.7.1 Bitrate ceiling discovery reference

For each promising `(codec, size, fps)` branch:

- start from a safe seed bitrate derived from preflight throughput,
- ramp upward quickly until instability is observed,
- record first-failure boundary,
- binary-search between last-pass and first-fail values,
- confirm the resulting ceiling with repeated runs,
- persist:
  - `last_pass_kbps`
  - `first_fail_kbps`
  - `max_stable_kbps`
  - `recommended_prod_kbps`
  - `ceiling_confidence`

Recommended production bitrate rule:

```text
recommended_prod_kbps = floor(max_stable_kbps * safety_factor)
```

Where `safety_factor` is initially mode-specific and later may become device-specific:

- `max_quality`: `0.90` to `0.94`
- `balanced`: `0.86` to `0.92`
- `low_latency`: `0.80` to `0.88`

### 14.7.2 Search budget allocation reference

The runtime budget should be explicitly partitioned:

- preflight: `10-20%`
- stability floor and ceiling discovery: `30-40%`
- local refinement: `25-35%`
- sustained validation: `15-30%`

This prevents the common failure mode where almost all time is spent on noisy early candidates and not enough time remains to validate winners.

---

## 15. Adaptive Search Improvements

To improve results beyond a simple staged heuristic, Trainer should use adaptive search.

### 15.1 Hybrid strategy

Recommended search blend:

* early exploration: random / Latin-hypercube style sampling,
* mid-phase exploitation: model-guided search,
* late-phase refinement: local hill-climb or neighbourhood mutation.

### 15.2 Exploration vs exploitation control

If the search is stagnating, Trainer should deliberately explore.

If the frontier is improving steadily, Trainer should exploit.

### 15.3 Stagnation handling

If no meaningful improvement occurs over N trials:

* expand around secondary candidates,
* perturb search dimensions,
* switch codec priority,
* lower aggressiveness,
* rerun a short baseline check if environment drift is suspected.

### 15.4 Multi-armed treatment of codec families

Treat codec families as competing branches.
Do not overinvest in one codec branch too early unless it clearly dominates.

### 15.5 Warm restart capability

If a search is interrupted or resumed, reuse prior stage knowledge and priors where valid.

---

## 16. Trial Lifecycle

Each trial should have a formal lifecycle:

1. candidate created
2. candidate validated against search constraints
3. stream launched
4. warmup phase
5. sample phase
6. live scoring phase
7. early terminate or complete
8. persist metrics
9. persist decision
10. rank update

Each trial must persist:

* config,
* start/end timestamps,
* warmup/sample durations,
* metrics summary,
* raw timeline reference,
* gate outcomes,
* reject reasons,
* total score,
* rank snapshot,
* termination reason.

---

## 17. Early Trial Termination

Poor candidates must be killed quickly to increase search efficiency.

### 17.1 Early kill conditions

Examples:

* present fps below `0.6 * target_fps` for > 5s
* timeout rate beyond hard limit
* no usable samples collected
* decode starvation
* stream start failure
* queue runaway
* repeated transport failure
* catastrophic latency blowout

### 17.2 Benefits

Early termination can dramatically reduce wasted runtime and increase useful trials per budget.

### 17.3 Persisted termination reason

Each early-killed trial must persist a clear code and explanation.

---

## 18. Scoring Model

Trainer should use a two-layer evaluation system.

---

## 18.1 Hard Gates

Hard gates determine whether a trial is viable.

Example gate categories:

* minimum sample count,
* minimum present fps floor,
* maximum timeout threshold,
* maximum drop ratio threshold,
* maximum latency ceiling,
* queue saturation ceiling.

Example thresholds may vary by mode and device class.

Failure should cause:

* explicit rejection,
* severe negative rank,
* reason code assignment.

---

## 18.2 Weighted Objective

For trials that pass gates, compute a weighted score.

Score axes:

* Stability
* Quality
* Latency
* Efficiency
* Delivery smoothness
* Confidence

---

## 18.3 Suggested scoring components

### Stability

Measures:

* fps p10/p50 consistency,
* variance penalties,
* timeout trends,
* drop trends,
* long-run drift.

### Quality

Measures:

* bitrate utilisation within safe bounds,
* sustained frame delivery,
* resolution/fps achievement,
* mode-aware preference for higher bitrate.

### Latency

Measures:

* e2e p95,
* decode p95,
* render p95,
* latency variance,
* tail behaviour.

### Efficiency

Measures:

* queue pressure,
* backpressure,
* transport utilisation quality,
* timeout creep.

### Confidence

Measures:

* sample sufficiency,
* repeatability,
* drift behaviour,
* validation consistency.

---

## 18.4 Stability Confidence

A dedicated confidence modifier should exist.

A candidate with high score but poor confidence should not outrank a slightly weaker but highly repeatable one.

Confidence should consider:

* duration,
* variance,
* repeated validation agreement,
* drift,
* sample completeness.

---

## 18.5 Mode Profiles

### `max_quality`

Weights:

* quality high
* stability strong
* latency medium
* efficiency medium

### `balanced`

Weights:

* all axes balanced

### `low_latency`

Weights:

* latency very high
* stability high
* quality secondary
* efficiency medium

### Future mode extensions

Potential later modes:

* `battery_saver`
* `network_resilient`
* `presentation_mode`
* `diagnostic_mode`

---

## 18.6 Reference score formulation

The score model should be concrete enough that engineering can implement it consistently across GUI, backend, artifacts, and replay.

A reference formulation:

```text
if hard_gate_failed:
    final_score = reject_score
else:
    final_score =
        w_stability  * stability_score  +
        w_quality    * quality_score    +
        w_latency    * latency_score    +
        w_efficiency * efficiency_score +
        w_smoothness * smoothness_score

    final_score =
        final_score * confidence_modifier
        - timeout_penalty
        - drop_penalty
        - latency_tail_penalty
        - drift_penalty
```

Where:

- all base component scores normalize into a stable range such as `0..100`,
- `confidence_modifier` scales down otherwise-strong but weakly validated candidates,
- penalties are explicit persisted values, not hidden heuristics.

### 18.6.1 Suggested component definitions

Reference component directions:

```text
stability_score:
  high when present/decode/recv stay near target
  high when p10 is close to p50
  low when drift or frequent stalls appear

quality_score:
  high when delivered bitrate approaches stable ceiling
  high when selected resolution/fps objective is sustained
  low when bitrate is high but delivery becomes uneven or unstable

latency_score:
  high when e2e/decode/render p95 stay low
  low when tail latency blows out even if medians look good

efficiency_score:
  high when queue pressure and backpressure remain controlled
  low when the system only works by living at the edge of congestion

smoothness_score:
  high when fps timeline is even and low-jitter
  low when visible cadence disruptions or burstiness appear
```

### 18.6.2 Suggested penalty families

Persist penalties independently so the UI can explain score collapse:

- `penalty_timeout_mean`
- `penalty_timeout_burst`
- `penalty_drop_ratio`
- `penalty_present_gap`
- `penalty_decode_gap`
- `penalty_queue_saturation`
- `penalty_latency_tail`
- `penalty_thermal_drift`
- `penalty_retry_or_recovery_events`

### 18.6.3 Suggested confidence modifier

Reference form:

```text
confidence_modifier =
    sample_completeness_factor *
    repeatability_factor *
    sustained_validation_factor *
    environment_stability_factor
```

This keeps peak-only hero configs from outranking slower but validated production winners.

### 18.6.4 Mode-specific score intent

Mode presets should primarily change weights and penalty sensitivity, not reinvent the entire scoring engine:

- `max_quality` should aggressively reward bitrate/resolution success, but only inside strong stability gates.
- `balanced` should aim for robust production profiles and likely become the default recommendation mode.
- `low_latency` should strongly punish latency tails and queue buildup even if bitrate could still increase.

### 18.6.5 Winner selection rule

The final selected profile should not be chosen by raw scalar score alone. The selection rule should combine:

- top scalar score,
- Pareto membership,
- sustained validation result,
- confidence threshold,
- absence of severe bottleneck or drift warnings.

This is how Trainer avoids producing numerically “best” but operationally bad profiles.

---

## 18.7 Profile set output contract

Trainer should not only emit one winner. The final output set should normally include:

- one `qmax` or `max_quality` profile,
- one `balanced` production profile,
- one `safe` fallback profile,
- optionally one `low_latency` variant if materially different.

This yields a better real operational system because runtime can choose among validated alternatives instead of forcing one universal profile.

---

## 19. Pareto Frontier Ranking

A single scalar score is not enough for final profile intelligence.

Trainer should maintain a Pareto frontier over key dimensions such as:

* stability
* latency
* quality

A candidate is Pareto-worthy if it is not dominated by another across all tracked axes.

### 19.1 Why this matters

This preserves meaningful alternatives such as:

* slightly lower bitrate but much lower latency,
* slightly lower quality but much higher sustained stability,
* safer fallback config for weaker links.

### 19.2 UI impact

The Profiles and Compare tabs should expose:

* winner,
* Pareto alternatives,
* mode-specific recommendation badges.

---

## 20. Thermal and Long-Run Verification

One of the biggest opportunities for better results is **thermal realism**.

### 20.1 Problem

Some configs appear excellent for short runs, then degrade after prolonged use.

### 20.2 Required solution

Trainer should include sustained validation for top candidates.

Metrics:

* fps drift over time,
* latency drift over time,
* timeout growth,
* queue pressure growth,
* decode slowdown,
* thermal warning signals if obtainable.

### 20.3 Rank penalty

Candidates showing significant drift should receive heavy penalties or be rejected as production winners.

### 20.4 UI visibility

The user should clearly see:

* “peak performance”
* vs
* “validated sustained performance”

---

## 21. Bottleneck Detection

Trainer should attempt to localise where instability comes from.

### 21.1 Candidate bottleneck classes

* encoder bottleneck
* host pipeline bottleneck
* transport bottleneck
* decoder bottleneck
* render bottleneck
* queueing/backpressure bottleneck

### 21.2 Inputs for bottleneck inference

* queue depths,
* transport RTT,
* throughput collapse,
* decode fps deficits,
* present vs decode deltas,
* timeout locality,
* sender/pipe/present divergence.

### 21.3 Persisted explanation

Each run should attempt to label dominant bottlenecks.
This becomes valuable in compare/explainability views.

---

## 22. Full HUD Requirements

HUD is mandatory and must exist both:

* over the streamed output where feasible,
* and in the Trainer GUI.

Refresh target:

* at least once per second

---

## 22.1 HUD Content Blocks

### A. Run Context

* profile name
* run id
* goal mode
* serial
* target fps
* target size
* stage
* trial index
* elapsed
* estimated remaining time
* confidence / progress indicator

### B. Active Config

* codec
* bitrate
* resolution
* fps
* key transport params
* queue settings
* encoder knobs
* special codec params

### C. Live Metrics

* sender fps
* pipeline fps
* recv fps
* decode fps
* present fps
* drop count/rate
* timeout count/rate
* late count/rate
* e2e p95
* decode p95
* render p95
* queue depths
* bitrate utilisation

### D. Score Breakdown

* total score
* stability score
* quality score
* latency score
* efficiency score
* confidence score
* penalties

### E. Gate Matrix

* pass/fail status per gate
* failing threshold explanation

### F. Optimisation State

* current stage
* best-so-far score
* best-so-far config
* current estimated rank
* rejection reason if current config fails

### G. Environment State

* device health warnings
* ADB state
* transport quality badge
* daemon health
* warning banners

---

## 22.2 HUD Charts

Required charts:

* fps timeline (sender / recv / decode / present)
* latency timeline
* drop/timeout timeline
* bitrate utilisation timeline
* queue depth timeline

Recommended extra charts:

* score timeline
* confidence timeline
* drift timeline
* trial frontier timeline

If overlay space is limited, page rotation is acceptable, but critical status must never disappear.

---

## 22.3 HUD Overlay Design Principles

Use:

* large readable numerics,
* monospace or tabular numeric font,
* semi-transparent backing,
* compact but stable layout,
* color-coded pass/warn/fail cues,
* minimal flicker.

Avoid:

* decorative motion,
* chart clutter,
* excessive text wrapping,
* unstable panel resizing during live updates.

---

## 23. GUI Design Specification

Trainer should look like a **performance engineering workstation**, not a casual wizard.

---

## 23.1 Main Shell Layout

Suggested layout:

* top bar with global run state and controls,
* left navigation rail,
* main content area,
* optional right-side detail pane for selected trial/profile/run.

Top bar should show:

* active device
* profile
* goal mode
* daemon health
* training state
* quick actions

---

## 23.2 Train Tab

The Train tab should support both quick-start and advanced mode.

### Quick-start section

* device selector
* goal mode selector
* codec multi-select
* fps selector
* resolution selector
* profile name
* budget preset
* start button

### Advanced section

Collapsed by default but rich when expanded:

* warmup duration
* sample duration
* stage budgets
* strictness preset
* thermal validation duration
* codec-specific range overrides
* scoring preset
* artifact overwrite policy
* diagnostics verbosity
* retry policy

### UX notes

* invalid options should be blocked before run start,
* unsupported codec/device combinations should be explained inline,
* estimated run duration should be shown,
* profile naming rules should be visible.

---

## 23.3 Live HUD Tab

This should be the flagship screen.

Sections:

* run header
* stage progress
* large KPI cards
* live charts
* active config panel
* score breakdown
* gate matrix
* best-so-far panel
* trial event log

Important UX rule:
the user should understand system state in under 3 seconds.

---

## 23.4 Profiles Tab

Primary functions:

* search
* filter
* sort
* group
* inspect
* validate
* export
* apply
* compare

Suggested filters:

* profile name
* device model
* codec
* goal mode
* date
* score range
* validated status
* stale status
* host/app version compatibility

Profile details should show:

* final winner config
* Pareto alternatives
* preflight summary
* score explanation
* run history
* validation history
* drift notes
* artifact links

---

## 23.5 Runs Tab

Should display individual training executions.

Columns:

* run id
* profile name
* device
* goal mode
* status
* duration
* trials completed
* final score
* winner codec
* created date

Run detail should include:

* run overview
* event timeline
* stage summaries
* trial table
* rejection reasons summary
* artifact file list
* validation result linkage

---

## 23.6 Compare Tab

Should support 2–3-way comparison.

Compare:

* codec
* bitrate
* resolution
* fps stability
* latency tails
* drop rate
* timeout rate
* confidence
* sustained-validation score
* transport class
* device metadata
* version compatibility

Recommended visuals:

* aligned metric table
* radar/spider chart
* timeline overlays
* bottleneck summaries
* mode-specific recommendation badge

---

## 23.7 Devices Tab

Should show all ADB-visible devices and their current state.

Per device:

* serial
* model
* API level
* Android version
* transport class
* authorisation state
* codec capability support
* last preflight summary
* throughput tier
* health warnings
* recommended default mode

Useful actions:

* probe device
* run quick preflight
* open recent runs for device
* validate selected profile on device

---

## 23.8 Validation Tab

Purpose:

* verify an existing profile without full retraining,
* detect regressions,
* refresh confidence,
* revalidate after app/host/device changes.

Validation views should include:

* selected profile
* target device
* previous reference result
* current result
* pass/fail
* drift summary
* regression badges

---

## 23.9 Diagnostics Tab

This should expose lower-level troubleshooting tools:

* daemon health
* ADB environment
* path/runtime sanity
* codec probe tools
* baseline stream tester
* throughput bench launcher
* recent errors
* logs viewer
* artifact integrity checker

This reduces support/debug friction substantially.

---

## 24. Artifact Contract

Every training run for profile `<profile_name>` writes to deterministic directories.

### 24.1 Primary profile path

```text
config/training/profiles/<profile_name>/<profile_name>.json
config/training/profiles/<profile_name>/parameters.json
```

### 24.2 Recommended additional artifacts

```text
config/training/profiles/<profile_name>/preflight.json
config/training/profiles/<profile_name>/results.json
config/training/profiles/<profile_name>/top-candidates.json
config/training/profiles/<profile_name>/hud-timeseries.json
config/training/profiles/<profile_name>/validation-history.json
config/training/profiles/<profile_name>/compare-cache.json
config/training/profiles/<profile_name>/notes.json
```

### 24.3 Run-specific subdirectories

Strongly recommended:

```text
config/training/profiles/<profile_name>/runs/<run_id>/
```

This allows a profile to evolve while preserving run history.

Per-run files may include:

```text
run.json
parameters.json
preflight.json
results.json
top-candidates.json
trials.jsonl
hud-timeseries.jsonl
events.jsonl
logs.txt
```

### 24.4 No silent clobber

If directory exists and overwrite is not requested:

* create a new run id,
* preserve prior artifacts,
* update profile-level current pointer explicitly.

---

## 25. Required Schema: `parameters.json`

Must include:

### Run metadata

* run_id
* started_at
* finished_at
* duration
* trainer_version
* host_build_revision
* schema_version

### Device metadata

* serial
* model
* API level
* Android release
* transport class
* capability summary

### User inputs

* profile_name
* goal_mode
* codec_set
* target_fps
* resolution_mode
* trial_budget
* explicit overrides

### Search space

* codec candidates
* fps candidates
* size candidates
* bitrate candidates
* tunable ranges
* derived clamps
* disabled dimensions with reasons

### Scoring setup

* gate thresholds
* weight vector
* mode preset
* confidence model settings

### Preflight metrics

* ADB throughput
* ADB RTT
* baseline stream diagnostics
* quality tier
* derived bounds

### Final decision

* selected winner
* selected production bitrate
* top alternatives
* reject reasons summary
* confidence score
* validation summary

### Environment metadata

* OS/platform info
* daemon API version
* trainer GUI version
* relevant toolchain versions if applicable

---

## 26. Additional Schema Recommendations

### 26.1 `results.json`

Should capture:

* winner,
* alternatives,
* Pareto frontier,
* stage summaries,
* bottleneck inference,
* confidence and drift summary.

### 26.2 `trials.jsonl`

One JSON object per trial.
This is easier to append and debug than a single giant array.

### 26.3 `events.jsonl`

Live event stream persisted to disk for replay.

### 26.4 `hud-timeseries.jsonl`

Time-series snapshots suitable for replaying charts.

### 26.5 `validation-history.json`

Append-only validation results for profile longevity.

---

## 27. Event and Telemetry Streaming Model

Trainer should expose a structured live event stream.

### 27.1 Proposed endpoint

* `WS /v1/trainer/stream/{run_id}`

### 27.2 Event categories

* run state transitions
* stage transitions
* trial begin
* trial update
* trial end
* live metrics snapshot
* score update
* gate result
* warning
* error
* validation result
* bottleneck update
* best-so-far changed

### 27.3 Event design principles

Events should be:

* typed,
* versioned,
* timestamped,
* append-friendly,
* replayable,
* forward-compatible.

### 27.4 Snapshot cadence vs event cadence

Use:

* periodic snapshots for charts and HUD,
* event-driven updates for state transitions and meaningful changes.

---

## 28. API Surface for Trainer

Existing endpoints remain operational.
Add trainer-specific endpoints.

### 28.1 Core

* `POST /v1/trainer/preflight`
* `POST /v1/trainer/start`
* `POST /v1/trainer/stop`
* `GET /v1/trainer/runs`
* `GET /v1/trainer/runs/{run_id}`
* `GET /v1/trainer/profiles`
* `GET /v1/trainer/profiles/{profile_name}`
* `WS /v1/trainer/stream/{run_id}`

### 28.2 Recommended additions

* `POST /v1/trainer/validate`
* `GET /v1/trainer/devices`
* `GET /v1/trainer/devices/{serial}`
* `GET /v1/trainer/diagnostics`
* `POST /v1/trainer/export-profile`
* `POST /v1/trainer/import-profile`
* `GET /v1/trainer/search-presets`
* `GET /v1/trainer/scoring-presets`

### 28.3 API expectations

Responses should be stable, typed, and schema-versioned.
All long-running actions should expose progress and cancellability.

---

## 29. `trainer.sh` Launcher Contract

`./trainer.sh` should:

1. verify runtime prerequisites,
2. check daemon health,
3. optionally start daemon,
4. launch trainer GUI app,
5. write logs under `logs/trainer/`,
6. preserve diagnostic startup context.

### Suggested options

* `--start-service`
* `--control-port <port>`
* `--verbose`
* `--headless` (future)
* `--device <serial>`
* `--open-profile <name>`
* `--diagnostics`

### Startup checks

* required binary presence,
* environment sanity,
* daemon compatibility,
* writable artifact directories,
* frontend runtime readiness.

---

## 30. Validation and Revalidation Model

Trainer should not assume profiles remain valid forever.

### 30.1 Revalidation triggers

Profiles may need revalidation when:

* trainer version changes,
* daemon build revision changes,
* Android OS/device version changes,
* transport path changes,
* codec support changes,
* user explicitly requests validation.

### 30.2 Validation modes

* quick validate
* standard validate
* sustained validate

### 30.3 Validation outcomes

* pass
* pass with drift
* warning
* fail
* incompatible

### 30.4 Stale profile marking

Profiles should be marked stale if environmental conditions changed enough to lower confidence.

---

## 31. Regression Detection

A major quality improvement is automatic regression awareness.

### 31.1 Regression dimensions

* score drop
* latency increase
* fps stability loss
* timeout increase
* drop increase
* lower sustained-validation confidence

### 31.2 Regression reporting

The UI should show:

* current vs previous validated result,
* highlighted regressions,
* suspected cause if inferable.

This becomes especially useful after host/app updates.

---

## 32. Failure Handling

Trainer must fail fast and explicitly.

### 32.1 Required explicit failures

* daemon unreachable
* incompatible daemon API version
* no ADB device
* device offline
* device unauthorized
* stream cannot start
* capture consent unavailable
* insufficient sample quality
* artifact write failure
* unsupported codec/device combination

### 32.2 Failure persistence

All failures must persist:

* code
* message
* phase
* timestamps
* recovery hints if applicable

### 32.3 GUI behaviour

Errors should be visible and actionable, not buried in logs only.

---

## 33. Security and Safety

### 33.1 Input validation

Validate:

* profile names
* serial inputs
* export/import paths
* API request bodies

### 33.2 Path safety

Prevent path traversal and unsafe artifact resolution.

### 33.3 Environment hygiene

Sanitize subprocess environment, especially when invoking legacy/proto runners.

### 33.4 No hardcoded personal environment values

Do not embed usernames, private paths, or private IP assumptions.

### 33.5 Explicit overwrite rules

No destructive overwrite without user or explicit policy approval.

---

## 34. Compatibility and Migration

### 34.1 Keep current commands working

Must preserve:

* `./wbeam train wizard`
* proto wrappers
* existing domain scripts

### 34.2 Incremental backend migration

Trainer GUI may initially call existing domain scripts and progressively migrate to richer API-driven orchestration.

### 34.3 Schema migration

Artifact schema versions should be migration-friendly.

---

## 35. Logging, Replay, and Explainability

### 35.1 Logging

Trainer should log:

* orchestration decisions,
* trial lifecycle,
* state transitions,
* warnings/errors,
* score and gate updates.

### 35.2 Replay

Persisted telemetry should allow replay of a completed run in the GUI.

### 35.3 Explainability

For each winning or rejected candidate, the system should be able to explain:

* which gates passed/failed,
* what penalties applied,
* why rank changed,
* which bottleneck was suspected.

This is one of the highest-value quality-of-life improvements.

---

## 36. Recommended UI Visual Style

Trainer should visually read as a technical workstation.

### 36.1 Style goals

* dense but readable,
* chart-heavy,
* stable layout,
* fast scanability,
* strong state coloring.

### 36.2 Color semantics

* green = stable / pass
* yellow = warning / borderline
* red = fail / unstable
* blue = active / current
* grey = inactive / unavailable

### 36.3 Typography

* tabular numeric font for metrics,
* readable sans for labels,
* strong emphasis on large KPI numbers.

### 36.4 Charting

Use a chart library that handles live updates reliably.
Charts should support hover, zoom, replay, and comparison overlays.

---

## 37. Recommended Implementation Phases

### Phase 1 — Foundations

* profile/run artifact model
* `parameters.json`
* preflight benchmarks
* gate + weighted scoring
* device capability probe
* deterministic run persistence

### Phase 2 — Telemetry

* structured event stream
* HUD snapshots
* chart-ready timelines
* replayable run storage
* trial JSONL persistence

### Phase 3 — GUI app

* scaffold `src/apps/trainer-tauri`
* Train / Live HUD / Profiles / Runs / Compare / Devices / Validation / Diagnostics
* API wiring
* artifact browser

### Phase 4 — Quality upgrades

* Pareto frontier
* thermal verification
* bottleneck inference
* regression detection
* stale profile handling
* validation history

### Phase 5 — Hardening

* resume semantics
* better ETA/confidence
* import/export packaging
* schema migration tools
* richer explainability

---

## 38. Acceptance Criteria

A run is accepted only when all of the following are true:

* full preflight executed and stored,
* device capability summary captured,
* full HUD visible and updating during training,
* trials persist metrics and outcomes,
* profile artifacts saved under deterministic profile directory,
* run artifacts saved under deterministic run directory,
* `parameters.json` present and complete,
* winner and alternatives produced,
* Pareto alternatives available,
* validation/sustained verification performed for top candidates,
* explainable score and reject reasons persisted,
* required codecs guarded by capability checks,
* GUI can browse profile and run artifacts successfully.

---

## 39. High-Value Improvements To Prioritise

If implementation capacity is limited, the most impactful upgrades for best results are:

1. **adaptive hybrid search**
2. **thermal / sustained verification**
3. **Pareto frontier ranking**
4. **device capability-based search clamping**
5. **early trial termination**
6. **bottleneck inference**
7. **revalidation + stale profile logic**
8. **full replayable telemetry persistence**

Together, these produce a much stronger system than a basic score-only autotuner.

---

## 40. Resolved Implementation Decisions

The following decisions resolve the former open questions and define the universal default implementation.

### 40.1 MJPEG default policy

`mjpeg` should not be part of the default search space for normal `max_quality` or `balanced` runs when `h264` or `h265` are supported and stable.

Default policy:

* `h264` and `h265` are primary search branches,
* `mjpeg` is enabled automatically only when:
  * hardware/video pipeline capability for `h264` and `h265` is missing,
  * preflight identifies repeated instability in inter-frame codecs,
  * user selects diagnostics or an explicitly resilient mode,
* advanced users may force-enable `mjpeg`.

This keeps the universal implementation practical without wasting budget on a usually inferior default path, while still preserving `mjpeg` as a first-class fallback and diagnostic branch.

### 40.2 Profile naming and device class tags

Canonical profile names should remain user-stable and portable.
Device class tags must not be silently baked into the canonical namespace.

Decision:

* canonical id: user-provided `profile_name`,
* device tags stored as metadata and searchable labels,
* optional derived display label may append device class,
* artifact directory path remains based on canonical profile name plus run id.

This avoids unstable naming, duplicate profiles for the same intent, and path churn when classification logic evolves.

### 40.3 Bitrate safety factor model

The bitrate safety factor should be hierarchical:

* base factor is mode-specific,
* then adjusted by measured device and transport risk,
* optionally overridden by explicit expert policy.

Reference rule:

```text
effective_safety_factor =
    mode_base_factor *
    transport_risk_factor *
    thermal_risk_factor *
    confidence_factor
```

This is more universal than either a single global factor or a hardcoded per-device table. It gives stable defaults while still adapting to real measured conditions.

### 40.4 Baseline warmup duration

Default baseline warmup should be `60s`, with automatic escalation to `90s` under specific conditions.

Escalation triggers:

* quality mode on high-end codecs or native resolution,
* unstable first 60s baseline,
* detected thermal drift,
* high transport jitter,
* explicit strict validation preset.

This keeps normal runs practical while still allowing better universality when the environment is noisy or slow to settle.

### 40.5 Revalidation on version change

Yes, old profiles should be automatically marked for revalidation when relevant environment versions change, but the behavior should be soft by default rather than always blocking.

Decision:

* on host build, daemon API, trainer schema, Android version, or codec capability change:
  * mark profile as `stale` or `revalidation_recommended`,
  * show warning in UI,
  * run quick validation on next profile use if policy allows,
* only block use automatically when compatibility is clearly broken or confidence drops below a defined floor.

This preserves universality and operational convenience while still protecting against silent regressions.

### 40.6 Fast search mode

Yes, there should be a first-class `fast_search` mode.

Modes:

* `fast_search` for quick field iteration,
* `standard_search` as default,
* `deep_search` for full production tuning.

`fast_search` should:

* shrink stage budgets,
* reduce codec branches when capability confidence is high,
* shorten validation windows,
* still run preflight and at least minimal sustained verification.

Universal implementation benefits from this because not every environment can afford long runs, but all environments still need consistent structure.

### 40.7 Peak vs sustained winners

Yes, profiles should store both peak and sustained winners whenever they differ materially.

Required outputs:

* `peak_winner`
* `validated_winner`
* `safe_fallback`

The default recommended production profile should be `validated_winner`, not `peak_winner`.
This is critical for universal correctness because many devices and transports exhibit delayed degradation.

### 40.8 Transport-aware codec ordering

Yes, transport class must influence default codec ordering and initial search priors.

Examples:

* strong USB / low RTT transport can prioritize higher-quality inter-frame branches earlier,
* unstable or bandwidth-constrained transport should raise the priority of conservative branches and lower starting bitrate,
* severe RTT jitter should reduce aggressiveness even if raw throughput appears high.

This should affect initial ordering and budget allocation, but not hard-disable viable codecs unless preflight proves they are unsupported or nonviable.

### 40.9 Artifact retention and pruning

Yes, artifact retention should support automatic pruning with explicit policy and guardrails.

Default retention policy:

* always keep:
  * current profile winner artifacts,
  * latest validated run,
  * latest failure run,
  * schema migration anchors if needed,
* prune oldest low-value run artifacts beyond configurable thresholds,
* never silently prune without recorded policy and audit metadata.

Pruning should target large replay/telemetry artifacts first, not canonical profile summaries.

### 40.10 Diagnostics-only runs

Yes, diagnostics-only runs should be first-class stored objects.

They should use a distinct run type, for example:

* `training`
* `validation`
* `diagnostics`
* `preflight_only`

Diagnostics-only runs should persist enough context to support later comparison and troubleshooting, but they should not mutate the canonical profile winner state unless explicitly promoted.

### 40.11 Universal implementation summary

The universal default design therefore becomes:

* user-stable profile names with metadata-based device tagging,
* mode-aware and transport-aware search,
* adaptive bitrate ceiling discovery,
* separate fast, standard, and deep search budgets,
* persistent distinction between peak and validated outputs,
* soft stale-marking and revalidation on environment change,
* diagnostics as first-class artifacts,
* retention policies that preserve important history while controlling disk growth.

---

## 41. Final Direction

Trainer should become the canonical operational surface for profile intelligence in WBeam.

It should deliver:

* repeatable profile generation,
* transparent optimisation,
* observable live behaviour,
* deterministic artifacts,
* rich comparison and validation,
* regression awareness,
* better quality ceilings with controlled stability,
* and profiles that are trustworthy in real sustained usage.

This specification is the canonical blueprint for implementing the Trainer app and upgrading the training pipeline into a full profile optimisation system.
