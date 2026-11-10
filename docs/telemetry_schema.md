# Wire Protocol and Telemetry

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

All fields except `trace_id` are required in a JSON object.
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
| `metrics.kpi` | `target_fps`, `recv_fps`, `decode_fps`, `present_fps`, `frametime_ms_p95`, latency/decode/render fields |
| `metrics.latest_client_metrics` | Most recent accepted client payload, or `null` |
| `metrics.bitrate_actual_bps` | Bitrate from client receive rate, streamer output or configured fallback |
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

Current telemetry is useful for diagnosis, but several names imply stronger
measurements than the code provides:

- The framed-video loop's `decode_time_ms_p50` is a mean input-queue call duration;
  `decode_time_ms_p95` is computed from those sampled durations. Neither measures
  the complete hardware decoder pipeline.
- `render_time_ms_p95` is a window maximum of output-release/render calls, not
  a measured 95th percentile of physical display presentation.
- `e2e_latency_ms_p50` and `e2e_latency_ms_p95` receive the same single estimate
  from the last presented PTS. They are not independent percentiles.
- The host computes `frametime_ms_p95` as `1000 / present_fps`; it is not a
  percentile of individual frame intervals. `frame_in` and `frame_out` are
  estimates from the latest FPS and stream uptime. In the absence of measurements,
  frame counts and `bitrate_actual_bps` can fall back to configured targets.
- `frame_budget_ms`, `queue_limits` and `e2e_schema` describe budgets/contracts;
  they do not prove that each listed stage timestamp is measured or transmitted.

**Known clock limitation:** the sender forwards GStreamer buffer PTS and only
falls back to Unix time when PTS is missing. EVDI explicitly produces elapsed
capture time, while Android's E2E estimator subtracts the PTS from Unix wall time.
There is no clock-domain conversion in that path. E2E values and transport-depth
estimates derived from this difference can therefore be invalid (including very
large latency values). Do not use them as physical latency acceptance evidence
until timestamp semantics and host/device clock alignment are corrected.

Implementation references: [sender](../host/rust/crates/wbeamd-streamer/src/transport/sender.rs),
[EVDI timestamps](../host/rust/crates/wbeamd-streamer/src/capture/backend/evdi.rs),
[Android latency math](../android/app/src/main/java/com/wbeam/stream/StreamBufferMath.java),
[framed decoder](../android/app/src/main/java/com/wbeam/stream/FramedVideoDecodeLoop.java),
and [host aggregation](../host/rust/crates/wbeamd-core/src/lib.rs).

## Live Stream Format

The production TCP connection begins with a **24-byte `WBS1` HELLO v2**, followed
by **WBTP/1 frames with 22-byte headers**. All multi-byte integers are big-endian.
This framing is implemented; the earlier proposed little-endian `WBM0`/C3 layout
is not the wire format.

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
| 10 | 8 | Timestamp/PTS in microseconds; see clock limitation above |
| 18 | 4 | Payload length |
| 22 | N | Encoded access unit, or PNG frame for the PNG lane |

The wire `session_id`, daemon `run_id` and client `trace_id` are separate
identifiers. The shared
[`wbtp-core`](../shared/protocol/rust/crates/wbtp-core/src/lib.rs) also defines
optional CRC32 (`0x01`) and end-of-stream (`0x04`) flags. The production packetizer
does not emit them, and Android reads the fixed 22-byte header; do not enable
shared-tool CRC framing against the Android receiver without updating both ends.
