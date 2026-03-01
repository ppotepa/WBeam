# API17 Decoder Policy

- Prefer baseline/low-latency H.264 behavior.
- Keep reorder disabled (`PROTO_H264_REORDER=0`).
- Use bounded queues to minimize freeze/recovery oscillation.
- Fallback to lower bitrate before increasing transport buffering.
