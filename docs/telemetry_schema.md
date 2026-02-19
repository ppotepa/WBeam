# WBeam Telemetry Schema v1

Task-ID: A3

## Per-Frame Timestamps

Every frame in the pipeline carries these timestamps (microseconds, monotonic clock):

| Field | Type | Set by | Description |
|-------|------|--------|-------------|
| `frame_seq` | u64 | host encoder | Monotonic frame counter, resets on stream start |
| `run_id` | u64 | host daemon | Session identifier, changes on each `/v1/start` |
| `capture_ts_us` | u64 | host Python | Timestamp when GStreamer src buffer is acquired |
| `encode_ts_us` | u64 | host Python | Timestamp after encoder outputs the NAL/AU |
| `send_ts_us` | u64 | host Python | Timestamp when `tcpserversink` pushes bytes |
| `recv_ts_us` | u64 | Android | Timestamp when last byte of frame arrives in `readBuf` |
| `decode_ts_us` | u64 | Android | Timestamp after `codec.queueInputBuffer()` returns |
| `present_ts_us` | u64 | Android | Timestamp after `releaseOutputBuffer(render=true)` |

**Current status:** `recv_ts_us`, `decode_ts_us`, `present_ts_us` available (Android wall clock).  
`capture_ts_us`, `encode_ts_us`, `send_ts_us` require C3 framing protocol (not yet implemented).  
`e2e_p95` will be non-zero only after C3 is merged.

## Derived Metrics (computed per frame or per 1s window)

| Field | Formula | Unit |
|-------|---------|------|
| `capture_to_send_ms` | `(send_ts_us - capture_ts_us) / 1000` | ms |
| `network_ms` | `(recv_ts_us - send_ts_us) / 1000` | ms |
| `decode_ms` | `(decode_ts_us - recv_ts_us) / 1000` | ms |
| `present_delay_ms` | `(present_ts_us - decode_ts_us) / 1000` | ms |
| `e2e_ms` | `(present_ts_us - capture_ts_us) / 1000` | ms |

## `/v1/metrics` Response Schema

```json
{
  "state": "STREAMING",
  "active_config": { ... },
  "run_id": 1234567890,
  "uptime": 42.3,
  "host_name": "mypc",
  "last_error": null,
  "metrics": {
    "adaptive_level": 0,
    "adaptive_action": "hold",
    "adaptive_reason": "",
    "fps_present": 59.8,
    "frametime_p95_ms": 18.2,
    "e2e_p95_ms": 0.0,
    "decode_p95_ms": 5.4,
    "render_p95_ms": 2.1,
    "bitrate_actual_bps": 11800000,
    "queue_transport": 1,
    "queue_decode": 1,
    "queue_render": 0,
    "drops_total": 3,
    "late_total": 0
  }
}
```

## `POST /v1/client-metrics` Payload

Sent by Android every 1000 ms:

```json
{
  "run_id": 1234567890,
  "fps_present": 59.8,
  "decode_p50_ms": 4.1,
  "decode_p95_ms": 5.4,
  "render_p95_ms": 2.1,
  "queue_transport": 1,
  "queue_decode": 1,
  "queue_render": 0,
  "drops_delta": 0,
  "late_delta": 0,
  "recv_bps": 11750000,
  "reason": "ok"
}
```

## C3 Frame Packet Header (planned, not yet implemented)

Binary little-endian header prepended to each access unit:

```
offset  size  field        description
──────  ────  ───────────  ─────────────────────────────────────────
0       4     magic        0x57424D30 ("WBM0")
4       1     version      0x01
5       1     flags        bit0=keyframe, bit1=has_pts, bits2-7=reserved
6       2     reserved     padding, must be 0
8       4     seq          frame sequence number (u32 LE)
12      8     pts_us       presentation timestamp µs (u64 LE)
20      4     len          payload length in bytes (u32 LE)
24      N     payload      complete H.264 access unit (AnnexB byte-stream)
```

Total header overhead: 24 bytes per frame (~0.001% at 12 Mbps).
