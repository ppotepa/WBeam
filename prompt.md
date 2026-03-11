# WBeam Quality Improvement Prompt (for another agent)

Use this prompt as your **single task definition**.  
You are working inside the WBeam repository and your mission is to improve stream quality, stability, and artifact resistance.

---

## Role

Act as a senior video-streaming systems engineer (Rust + Android + GStreamer + low-latency transport), focused on practical, testable improvements.

---

## Repository context (must read first)

1. Read and use this file:
- `backend.md`

2. Key code areas:
- Host API/server:
  - `src/host/rust/crates/wbeamd-server/src/main.rs`
  - `src/host/rust/crates/wbeamd-api/src/lib.rs`
- Host core/adaptation:
  - `src/host/rust/crates/wbeamd-core/src/lib.rs`
  - `src/host/rust/crates/wbeamd-core/src/domain/policy/*`
- Streamer:
  - `src/host/rust/crates/wbeamd-streamer/src/main.rs`
  - `src/host/rust/crates/wbeamd-streamer/src/cli.rs`
  - `src/host/rust/crates/wbeamd-streamer/src/pipeline.rs`
  - `src/host/rust/crates/wbeamd-streamer/src/encoder.rs`
  - `src/host/rust/crates/wbeamd-streamer/src/transport.rs`
- Android decode/client metrics:
  - `android/app/src/main/java/com/wbeam/MainActivity.java`
  - `android/app/src/main/java/com/wbeam/stream/H264TcpPlayer.java`
  - `android/app/src/main/java/com/wbeam/api/*`
- Trainer:
  - `src/domains/training/wizard.py`
  - `src/apps/trainer-tauri/src/App.tsx`

---

## Problem statement

Current behavior is good but still below “production-grade premium quality” for dynamic scenes.
We need to improve:

1. Visual quality under motion (reduce block artifacts, banding, shimmer).
2. Stability (fewer reconnects/restarts, fewer stalls).
3. Parameter coherence (UI/trainer values must match effective runtime behavior).
4. Better codec-specific tuning strategy (especially H264 fallback paths).

Known context:
- Effective bitrate floor is 4000 kbps.
- On some hosts NVENC is unavailable and fallback uses x264/x265.
- H264 software config currently prioritizes ultra-low-latency and may over-sacrifice compression efficiency.

---

## Hard requirements

1. Do **not** produce only theory. Implement concrete changes.
2. Keep changes incremental and auditable (no giant risky rewrite).
3. Add/extend diagnostics so we can prove what effective runtime config is used.
4. If a parameter is exposed in UI/trainer, ensure it is either:
   - actually applied end-to-end, or
   - clearly marked unsupported/not wired.
5. Preserve existing API17 compatibility path.
6. Preserve multi-device session behavior by serial/stream_port.

---

## Execution plan (mandatory)

Follow these phases in order:

### Phase 1 - Baseline audit

Produce a short baseline report:
- active encoder backend per codec (nvenc vs x264/x265),
- effective runtime params (fps, bitrate, size, gop, intra-only, key queue params),
- restart triggers on `/apply`,
- top 5 suspected quality bottlenecks.

### Phase 2 - Implement high-impact fixes

Implement at least 3 meaningful fixes from the list below (or better):

- H264 quality profile path for software fallback (without breaking low-latency mode).
- Better separation of `latency` vs `quality` encoder presets and exposed controls.
- Effective config snapshot endpoint/logging (single source of truth).
- Guardrails for impossible combinations (too low bitrate for selected size/fps).
- Improved adaptation behavior to avoid oscillation/restart thrash.
- Better trial scoring/selection signals for dynamic content artifact risk.

### Phase 3 - Validate

Run checks/builds/tests that are available and report:
- what passed,
- what failed,
- what could not be executed.

### Phase 4 - Deliver

Provide:
- summary of changes,
- exact file list + reasoning,
- measurable expected impact,
- residual risks and next steps.

---

## Preferred technical directions

1. **Dual H264 strategy**:
- `h264_latency` (existing behavior)
- `h264_quality` (better compression efficiency: preset/tune/options adjusted)

2. **Deterministic effective-config visibility**:
- Add explicit host-side snapshot of effective encoder backend + params.
- Must be visible in logs and API response used by Trainer/Live Stats.

3. **Adaptive safety envelope**:
- If bitrate is low for selected resolution/fps, auto-warn and optionally auto-adjust.
- Avoid hidden clamps that are invisible to operator.

4. **Training relevance upgrade**:
- Make scoring penalize visible-risk conditions more strongly:
  - sustained drops,
  - decode/render p95 spikes,
  - queue pressure bursts,
  - “present fps collapse under dynamic scenes”.

---

## Output format (mandatory)

Return your final answer with:

1. `Findings` (ordered by severity, with file references and line hints).
2. `Implemented changes` (what, where, why).
3. `Validation` (commands and key output summary).
4. `Impact` (what should improve and under what conditions).
5. `Next 3 actions` (high-value follow-ups).

---

## Constraints on style

- Be direct, technical, and concise.
- No marketing language.
- Prefer reproducible facts over assumptions.
- If uncertain, instrument first, then change.

---

## Success criteria

We consider this task successful if:

1. At least 3 concrete backend/runtime quality improvements are merged.
2. Effective encoder/runtime config becomes clearly observable.
3. H264 fallback path has a quality-oriented mode (not only ultra-low-latency).
4. Trainer/live controls are consistent with effective backend behavior.
5. Build/check commands succeed for changed modules (or failures are clearly explained).

