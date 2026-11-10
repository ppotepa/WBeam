# WayBeam Wire Protocol and Telemetry

This page describes the current implementation. The authoritative contracts are
[HTTP models](../host/rust/crates/wbeamd-api/src/lib.rs),
[stream packetization](../host/rust/crates/wbeamd-streamer/src/packetize/mod.rs),
[Android parsing](../android/app/src/main/java/com/wbeam/stream/WbtpProtocol.java),
and [client JSON serialization](../android/app/src/main/java/com/wbeam/ClientMetricsSample.java).

## HTTP Metrics

The daemon exposes `GET /v1/metrics` and `POST /v1/client-metrics` on its control
port (default `5001`). Unprefixed routes are compatibility aliases. For multiple
devices, use the same `serial` and `stream_port` query parameters as the stream
session; `run_id` is returned by the host, not supplied in the client payload.

### Client Payload

Example `POST /v1/client-metrics` body (illustrative values):

```json
{
  "recv_fps": 60.0,
  "decode_fps": 60.0,
  "present_fps": 59.0,
  "recv_bps": 1500000,
  "decode_time_ms_p50": 0.3,
  "decode_time_ms_p95": 0.6,
  "render_time_ms_p95": 0.2,
  "frametime_ms_p95": 18.0,
  "e2e_latency_available": false,
  "e2e_latency_ms_p50": 0.0,
  "e2e_latency_ms_p95": 0.0,
  "transport_queue_depth": 1,
  "decode_queue_depth": 1,
  "render_queue_depth": 0,
  "jitter_buffer_frames": 0,
  "dropped_frames": 3,
  "too_late_frames": 0,
  "timestamp_ms": 1700000000000,
  "trace_id": 4294967297
}
```

The original fields, except `trace_id`, are required in a JSON object. The
additive `frametime_ms_p95` field is optional/nullable; it is `null` when no
presentation intervals were observed. `e2e_latency_available` defaults to
`false` for older clients. When unavailable, the E2E numeric fields remain `0`
for compatibility; consumers must check the availability flag.
`timestamp_ms` is Android wall-clock milliseconds since the Unix epoch; zero is
replaced with host wall time. `trace_id` combines a client connection counter
(high 32 bits) and sample counter (low 32 bits). Drop/late fields are cumulative
client counters, not per-sample deltas.

**Units:** `recv_bps` is **bytes/second**, despite its name. The host multiplies
it by eight for `metrics.bitrate_actual_bps` (bits/second). Durations are
milliseconds; queue depths are frames. Decode loops normally emit samples at
approximately one-second intervals. The
[reporter](../android/app/src/main/java/com/wbeam/telemetry/ClientMetricsReporter.java)
limits HTTP posts to at most one every 500 ms, so it does not guarantee a 2 Hz
sampling rate.

### Host Response

`GET /v1/metrics` flattens `BaseResponse` at the top level and adds `ok` and
`metrics`. Key paths are:

| JSON path | Meaning |
|---|---|
| `state`, `active_config`, `run_id` | Daemon session state, requested configuration and run counter |
| `effective_runtime_config` | Resolved configuration, present when available |
| `host_name`, `uptime`, `last_error` | Host name, integer uptime seconds and error string (empty when clear) |
| `target_serial`, `stream_port`, `control_port` | Session routing; `target_serial` is optional |
| `metrics.kpi` | FPS, nullable observed `frametime_ms_p95`, decode/render durations, E2E values and `e2e_latency_available` |
| `metrics.latest_client_metrics` | Most recent accepted client payload, or `null` |
| `metrics.frame_in`, `metrics.frame_out`, `metrics.frame_counts_estimated` | Frame-total estimates derived from rates and uptime; the flag identifies estimates |
| `metrics.bitrate_actual_bps`, `metrics.bitrate_estimated` | Client/streamer observed bitrate, or a configured target marked as estimated |
| `metrics.transport_runtime` | Host sender FPS, queue depths, sequence and timeout/drop/retry counters |
| `metrics.adaptive_level`, `adaptive_events`, `adaptive_action`, `adaptive_reason` | Current adaptation state and decisions (all under `metrics`) |
| `metrics.frame_budget_ms`, `queue_limits`, `e2e_schema` | Declared budgets/schema metadata (all under `metrics`) |
| `metrics.tuning` | Optional tuner progress |

See `MetricsSnapshot` and `KpiSnapshot` in the HTTP models for the complete field
list. In particular, presentation FPS is `metrics.kpi.present_fps`, not a flat
`metrics.fps_present` field.

The host appends accepted samples, with its `run_id`, to
`$XDG_DATA_HOME/wbeam/telemetry/run_<run_id>.jsonl` (default
`~/.local/share/wbeam/telemetry/`). Run counters are per daemon core and restart
with the process; they are not globally unique. Existing files are appended.

## Interpreting Measurements

- `decode_time_ms_p50` and `decode_time_ms_p95` are nearest-rank percentiles of
  observed input-queue call durations in the video loop. They do not measure the
  complete hardware decode pipeline. PNG measures bitmap decode calls separately.
- `render_time_ms_p95` is a percentile of observed output-release calls (video)
  or canvas submission work (PNG), not the time photons appear on the display.
- `frametime_ms_p95` is a percentile of consecutive client output-release times,
  measured with the local monotonic clock. It is `null` until an interval exists;
  the host does not derive a percentile from inverse FPS. Timing windows retain
  up to 128 observations.
- FPS and receive rate use the actual sample-window duration. `present_fps=0`
  stays zero even when receive/decode activity continues. Observed zero frame
  rates and receive bitrate are not replaced by configured targets.
- Transport queue depth is a bounded estimate from locally buffered bytes and
  frame size. It is not inferred by subtracting sender and receiver clocks.
- `frame_in`/`frame_out` remain rate × uptime estimates, explicitly marked by
  `frame_counts_estimated`. Configured bitrate fallback is marked with
  `bitrate_estimated`; these are not physical-frame counts or measured throughput.
- `frame_budget_ms`, `queue_limits` and `e2e_schema` are budgets/schema metadata,
  not observations of every pipeline stage.

**Absolute E2E latency is unavailable in the current protocol.** WBTP provides
an opaque sender PTS, not a clock synchronization exchange. Android maps plausible
forward PTS deltas onto its own monotonic playback timeline, reanchoring resets,
large discontinuities and out-of-range values. It never subtracts sender PTS from
Unix wall time. Both relative GStreamer timestamps and older epoch-based senders
can therefore play without creating epoch-sized latency or queue estimates.

The current client sends `e2e_latency_available=false`. The host clears E2E
values from older clients without that flag and rejects negative, non-finite,
reversed or greater-than-60-second latency values. A future client claiming
availability must establish a valid capture-to-presentation clock mapping;
setting a JSON flag alone does not establish measurement accuracy. Use separate
physical latency instrumentation for release acceptance.

The tuner displays unavailable E2E explicitly and averages only available p95
samples for its summary, including their coverage. In scoring, each missing E2E
sample receives the existing capped 500 ms cost; missing telemetry cannot improve
a candidate's score. Other observed metrics still distinguish candidates.

Implementation references:
[playback timestamp mapping](../android/app/src/main/java/com/wbeam/stream/StreamTimestampMapper.java),
[presentation samples](../android/app/src/main/java/com/wbeam/stream/PresentationMetrics.java),
[framed decoder](../android/app/src/main/java/com/wbeam/stream/FramedVideoDecodeLoop.java),
and [host aggregation](../host/rust/crates/wbeamd-core/src/lib.rs).

## Live Stream Format

The production TCP connection begins with a **24-byte `WBS1` HELLO v2**, followed
by **WBTP/1 frames with 22-byte headers**. All multi-byte integers are big-endian.

### HELLO v2

| Offset | Bytes | Field |
|---|---|---|
| 0 | 4 | Magic `WBS1` |
| 4 | 1 | HELLO version `2` |
| 5 | 1 | Codec/mode flags |
| 6 | 2 | HELLO length `24` |
| 8 | 8 | Random connection `session_id` |
| 16 | 2 | Width |
| 18 | 2 | Height |
| 20 | 2 | FPS |
| 22 | 2 | Reserved, zero |

Codec flags are `0x01` for H.265 and `0x02` for PNG; with neither codec bit set,
the payload is H.264.
Mode bits are `0x10` ultra, `0x20` stable and `0x30` quality. The current Android
caller requires HELLO version 2; a retained v1 builder is not evidence that the
current app accepts a v1 greeting.

### Frame Header

| Offset | Bytes | Field |
|---|---|---|
| 0 | 4 | Magic `WBTP` |
| 4 | 1 | Frame version `1` |
| 5 | 1 | Flags; `0x02` marks a keyframe |
| 6 | 4 | Sequence, `u32`, starts at zero per TCP connection and wraps |
| 10 | 8 | Opaque sender PTS in microseconds; not a synchronized wall-clock timestamp |
| 18 | 4 | Payload length |
| 22 | N | Encoded access unit, or PNG frame for the PNG lane |

The wire `session_id`, daemon `run_id` and client `trace_id` are separate
identifiers. The shared
[`wbtp-core`](../shared/protocol/rust/crates/wbtp-core/src/lib.rs) also defines
optional CRC32 (`0x01`) and end-of-stream (`0x04`) flags. The production packetizer
does not emit them, and Android reads the fixed 22-byte header; do not enable
shared-tool CRC framing against the Android receiver without updating both ends.
