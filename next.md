This file is for the next agent that continues WBeam. Read this first before changing anything. Current project target is Android as second monitor over USB, KDE Wayland host on Ubuntu, and commercial quality direction. We already proved cable capacity around 261 Mbps with the new bandwidth test endpoint and Android button, so transport bandwidth is not the primary blocker. Main blocker is pipeline stability and frame pacing under real desktop motion.

Current architecture in short: host control plane is Rust daemon wbeamd with HTTP API on port 5001, stream plane is currently Python GStreamer pipeline output on port 5000, Android app receives framed video, decodes with MediaCodec, renders to Surface, and reports metrics back to host. We enforce framed-only mode now to avoid legacy AnnexB parsing stalls. We also added soak scripts that can run local daemon without systemd and fail fast on bad conditions.

What works now: startup path is much more stable, reconnect logic exists, preflight checks exist on Android, no-present watchdog exists, bounded decode queue is in place, and we can get periods of 50 to 62 fps at 720p60. What still fails: periodic collapses to very low fps, visible artifacts, occasional state churn STARTING/STREAMING around portal/capture restarts, and black-screen like symptoms where metrics show recv but present can stall.

Interpretation of latest logs: host_in_out can stay high while present_fps drops to near zero, which means bytes are flowing but decode/present pipeline is not keeping pace or is stuck in a bad queue state. Also when qD reaches hard cap repeatedly, adaptation can clamp quality but still not recover smoothly. That indicates we need stricter frame ownership and faster discard policy before decode backlog forms.

Immediate engineering direction (already agreed): move toward WBTP/1 as explicit framed protocol with a clean core library and separate sender/receiver projects in Rust. Keep control plane as WBCP/1 over HTTP. Build and validate sender+receiver on PC first, then integrate Android receiver. This isolates protocol and pacing problems from Android UI concerns.

Required next steps in order:
1) Refactor boundaries first. Create protocol workspace `protocol/rust` with `wbtp-core`, `wbtp-sender`, `wbtp-receiver-null`.
2) Define fixed header: magic, version, flags, sequence, capture timestamp, payload length, optional checksum.
3) Implement bounded queues and latest-frame-wins semantics in receiver-null for reproducible behavior.
4) Add deterministic metrics output every second: recv fps, drop count, jitter, queue depth, late frames.
5) Run 30 minute USB soak with sender->receiver-null and pass criteria before touching Android decode path again.
6) After protocol is stable, add Android adapter that consumes the same framed packets and feeds MediaCodec.

Performance rules that cannot be broken: no unbounded queues, no blocking waits in hot path if queue is full, drop old frames instead of building lag, avoid per-frame allocations, avoid memcpy loops, and keep one state owner for start/stop/restart. If a decision trades quality vs latency, pick latency and smoothness first.

Operational rules: every meaningful change must be appended to task.md worklog with verification command. Do not add manual steps if automation is possible. Avoid multiple processes fighting for ports. If daemon lock or port conflict appears, fix root cause instead of forcing restart loops.

Useful commands:
- Debug daemon: `./host/scripts/run_wbeamd_debug.sh`
- Local soak: `WAIT_STREAM_READY_SEC=120 ./host/scripts/run_soak_local.sh 1800 5001 5000`
- Android debug build: `cd android && ./gradlew :app:assembleDebug`

Definition of done for this phase: no black screen in 30 minute USB soak, present fps floor stable above agreed threshold during active scene, and predictable recovery without manual intervention. After that, proceed to NDK player abstraction and further quality tuning.

If tests fail, capture host log, Android HUD line, and exact timestamp, then append findings to task.md immediately.
