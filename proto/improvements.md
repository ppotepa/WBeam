# Proto Improvements

## Table 1: Core Optimization Tasks

| Task | Layer | Location | What to optimize | Expected gain | Complexity | Priority |
|---|---|---|---|---|---|---|
| T1 | Front (API17) | `H264Transport.java:48` | Replace static stall thresholds (`DECODER_STALL_MS`, `DECODER_STALL_MIN_QUEUED`) with adaptive thresholds based on `inFps`, `outFps`, and backlog. | Large stability boost, fewer freezes. | M | P0 |
| T2 | Front (API17) | `H264Transport.java:681` | Improve backpressure around `dequeueInputBuffer` (less aggressive dropping during short overloads). | +5 to +12 FPS in weak periods. | M | P0 |
| T3 | Front (API17) | `H264Transport.java:355` | Add a faster recover path for `waitingForIdr` / restart logic after stall. | Strong reduction of "frozen background" cases. | M | P0 |
| T4 | Front (API17) | `H264Transport.java:304` | Reduce AU/NAL copy overhead (`System.arraycopy` loops) and allocations. | +2 to +6 FPS, lower GC pressure. | M | P1 |
| T5 | Rust host | `main.rs:351` | Add source health gate to detect capture degradation early and reset only capture-side pipeline. | Large FPS stability boost. | M | P0 |
| T6 | Rust host | `main.rs:454` | Optimize AnnexB parser buffer movement (`drain`/compaction), preferably ring-buffer style. | +3 to +8 FPS CPU headroom. | H | P1 |
| T7 | Rust host | `main.rs:402` | Adaptive `sink_write_timeout` and write strategy for transient ADB lag. | Fewer drops and FPS oscillation. | M | P1 |
| T8 | Rust host | `main.rs:613` | Extend telemetry with rolling p95/p99 latency and freeze detector (no frame signature change). | Faster regression root-cause. | L | P1 |
| T9 | Rust↔Front | `main.rs:792` + `H264Transport.java:264` | Unify keyframe signaling (WBTP/WBH1) and decoder reaction path. | Fewer "mouse moves but video stuck" incidents. | M | P0 |
| T10 | Front (API17) | `H264Transport.java:365` | Shorter stats window and separate counters for input starvation vs decoder starvation. | Better quality of next tuning iterations. | L | P2 |

## Table 2: Code Review Tasks (Redundancy / Low-opt / Cleanup)

| Task | Layer | Location | Code review finding / improvement | Expected gain | Complexity | Priority |
|---|---|---|---|---|---|---|
| T11 | Autotune/Host | `stream_wayland_portal_h264.py:383` | Split scoring metrics into `real_fps` vs `dup_fps` so stale-dup does not inflate results. | Better preset selection, fewer false winners. | M | P0 |
| T12 | Rust host | `main.rs:613` | Rename/clarify telemetry (`units` are NALs, not frames) and add explicit `frame_fps`. | Correct decision-making during tuning. | L | P0 |
| T13 | Front (API17) | `H264Transport.java:250` | Deduplicate repeated flush/drop branches into one helper. | Lower regression risk, cleaner maintenance. | L | P1 |
| T14 | Rust host | `main.rs:295` | Replace line-based JSON parsing with robust parser (`serde_json`). | Fewer hidden config parsing bugs. | M | P1 |
| T15 | Front (API17) | `H264Transport.java:709` | Replace broad `catch (Exception)` with typed handling and clearer status mapping. | Faster diagnosis of runtime issues. | L | P1 |
| T16 | Rust host | `main.rs:369` | Add fail-fast path when portal is unavailable instead of long retry loops. | Less "run hangs" confusion. | L | P1 |
| T17 | Front (API17) | `H264Transport.java:431` | Reduce copying in reorder path (`Arrays.copyOf`) where possible. | Lower CPU/GC cost, smoother long sessions. | M | P1 |
| T18 | Host script | `stream_wayland_portal_h264.py:590` | Cleanup minor redundancy/unused pieces and normalize defaults. | Better readability and safer iteration. | L | P2 |
