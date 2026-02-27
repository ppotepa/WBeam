# 30 FPS Snapshot

Source report: `proto/autotune-results.json`  
Run timestamp: `20260227-160254`  
Run window: `2026-02-27T15:02:54Z` -> `2026-02-27T15:37:45Z`  
Duration: `2091.012s` (~34m 51s)  
Population/Generations: `8 / 7`

## Best 30 FPS trial

- Trial: `g04_07_child7`
- Score: `50.29`
- Sender p50: `51.1`
- Pipeline p50: `52.0`
- Timeout mean: `9.0`
- Trial config: `/tmp/proto-autotune-20260227-160254/g04_07_child7.json`
- Trial log: `/tmp/proto-autotune-20260227-160254/g04_07_child7.run.log`

## Top 5 trials with `PROTO_CAPTURE_FPS=30`

1. `g04_07_child7` score=50.29 sender_p50=51.1 pipe_p50=52.0 timeout=9.0
2. `g05_01_elite1` score=48.95 sender_p50=51.0 pipe_p50=51.0 timeout=9.68
3. `g03_03_child3` score=42.87 sender_p50=45.35 pipe_p50=46.0 timeout=18.62
4. `g03_07_child7` score=32.03 sender_p50=40.0 pipe_p50=40.5 timeout=40.35
5. `g05_03_child3` score=31.84 sender_p50=39.5 pipe_p50=40.5 timeout=38.15

## Winning 30 FPS params (key tunables)

- `PROTO_CAPTURE_FPS=30`
- `PROTO_CAPTURE_BITRATE_KBPS=8500`
- `WBEAM_VIDEORATE_DROP_ONLY=0`
- `WBEAM_PIPEWIRE_KEEPALIVE_MS=25`
- `WBEAM_FRAMED_PULL_TIMEOUT_MS=20`
- `WBEAM_QUEUE_MAX_TIME_MS=6`
- `WBEAM_APPSINK_MAX_BUFFERS=1`
- `PROTO_ADB_WRITE_TIMEOUT_MS=30`
- `PROTO_H264_SOURCE_READ_TIMEOUT_MS=2200`

## Preserved artifacts

- Main report JSON: `proto/autotune-results.json`
- Full trial artifacts directory: `/tmp/proto-autotune-20260227-160254`
