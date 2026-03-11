# Proto — Agent Context

> **Last updated: 2026-02-24**
> This file is the authoritative context doc for any AI agent working on this repo.
> Read it fully before making changes. Update it after completing significant work.

---

## 1. What This Is

WBeam **proto** is a USB second-screen prototype. Goal: Android tablet becomes a
wired extended display for Ubuntu KDE Wayland — targeting stable 60 fps, sub-20 ms
end-to-end latency, no Wi-Fi.

Think SuperDisplay or spacedesk over ADB, but open-source, wired, and fast.

**Scope of proto:** prove the full pipeline works on API 17 hardware. Production
quality (`android/`, `src/host/rust/`) is a separate codebase — do not confuse them.

---

## 2. Hardware

| Side   | Device                        | Details                           |
|--------|-------------------------------|-----------------------------------|
| Host   | Ubuntu 24.04 KDE Wayland      | Plasma 6.4.5, KWin 6.4.5          |
| Tablet | Lenovo IdeaTab S6000          | API 17 (Android 4.2.2), ARM       |
|        | Serial: DM7S55KRBQEQU4VO      | Display: 1280×800, 160 dpi        |
|        |                               | CPU: quad-core ARM Cortex-A9      |

---

## 3. Full Pipeline (current state)

```
Ubuntu KDE Wayland
  └─ XDG Desktop Portal (ScreenCast)
       └─ PipeWire node (captures selected output, e.g. HDMI-A-1 @ 1920×1080)
            └─ stream_wayland_portal_h264.py  (src/host/scripts/)
                 ├─ GStreamer pipeline:
                 │    pipewiresrc → videoconvert → videoscale(→1280×800)
                 │    → videorate → openh264enc → h264parse → appsink
                 └─ Rust main.rs reads H264 frames from appsink via TCP
                      └─ ffmpeg subprocess: decode H264 → extract JPEG
                           └─ writes JPEG to /dev/shm/proto-portal-frames/
                                └─ Rust polling loop picks up latest JPEG
                                     └─ ADB push sender thread:
                                          quality governor (Q=68..90, target ~140 KB)
                                          WBJ1 frame: magic(4)+seq(8)+ts_ms(8)+len(4)+JPEG
                                          TCP → 127.0.0.1:5006
                                               │
                                   adb forward tcp:5006 tcp:5006
                                               │
                                      Android :5006
                                        └─ AdbPushTransport.java  (ServerSocket)
                                             └─ FrameMailbox (lock-free mailbox)
                                                  └─ RenderLoop thread
                                                       └─ NativeRenderer.java
                                                            └─ NativeBridge (JNI)
                                                                 └─ native_wbeam.cpp
                                                                      ├─ libturbojpeg.so (JPEG decode)
                                                                      └─ ANativeWindow (blit to screen)
```

### Key numbers (measured, run.sh fast, after stage-1 opts)
- Host: ~60fps @ avg 32 KB/frame JPEG through USB
- Android: receives frames, renders via native turbo path
- Display: 1280×800 native (after resolution detection fix)

---

## 4. Android App Architecture

Location: `proto/front/app/src/main/java/com/proto/demo/`

### Packages (SOLID, SRP)

```
com.proto.demo/
  MainActivity.java          — thin coordinator only (~140 lines)
  config/
    StreamConfig.java        — constants + Intent parsing
  jni/
    NativeBridge.java        — System.loadLibrary + native declarations
  transport/
    Transport.java           — interface: run() + stop()
    FrameListener.java       — callback: onFrame(byte[], int)
    IoUtils.java             — readFully, readLine, u32be
    AdbPushTransport.java    — WBJ1 frame parser + ServerSocket (port 5006)
    HttpMjpegTransport.java  — MJPEG pull over HTTP (port 5005)
  pipeline/
    FrameMailbox.java        — lock-free single-slot mailbox + 3-buffer pool
  rendering/
    FrameRenderer.java       — interface: render, onSurfaceChanged(w,h), release
    NativeRenderer.java      — libturbojpeg → ANativeWindow
    JavaRenderer.java        — BitmapFactory → lockCanvas (fallback)
    RendererChain.java       — picks native first, java fallback
    RenderLoop.java          — dedicated wbeam-render thread
  stats/
    FrameStats.java          — FPS counter (unused after stage-1, RenderLoop has own stats)
  ui/
    StatusUpdater.java       — Handler(mainLooper).post()
    ScreenLayout.java        — fullscreen SurfaceView + TextView overlay
```

### Threading model (stage-1, current)

```
wbeam-io  (URGENT_DISPLAY)
  reads TCP bytes → WBJ1 parse → arraycopy → FrameMailbox.publish()
  never blocks on render

wbeam-render  (URGENT_DISPLAY)
  FrameMailbox.poll() → NativeRenderer.render() → recycle()
  reports fps+drops every 2s via StatusUpdater
```

### Native layer

- `native_wbeam.cpp` — JNI, all functions bound to `com.proto.demo.jni.NativeBridge`
- JNI export prefix: `Java_com_proto_demo_jni_NativeBridge_nativeXxx`
- `pick_scaled_size()`: turbojpeg scaling factors → decode to ≤ surface dims
- Decode format: RGBX_8888 (not RGBA — avoids black on some HW compositors)
- Flags: `TJFLAG_FASTDCT | TJFLAG_FASTUPSAMPLE`

### CRITICAL: library load order

```java
// In NativeBridge.java static block — ORDER MATTERS:
System.loadLibrary("turbojpeg");  // MUST be first
System.loadLibrary("wbeam");      // libwbeam.so depends on libturbojpeg.so
```

If `turbojpeg` loads after `wbeam`, dlopen fails with:
`could not load library "libturbojpeg.so" needed by "libwbeam.so"`

### CRITICAL: ServerSocket bind pattern

```java
ServerSocket ss = new ServerSocket();   // no-arg — unbound
ss.setReuseAddress(true);               // MUST be before bind()
ss.bind(new InetSocketAddress(5006));   // explicit bind
```

`new ServerSocket(5006)` binds in constructor — `setReuseAddress` after is too late.

### CRITICAL: ADB forward ordering in run.sh

```bash
adb forward --remove tcp:5006   # FIRST — stop adbd queuing new connections
sleep 1
am force-stop com.proto.demo    # THEN kill app
sleep 5                         # let kernel drain orphan sockets
adb forward tcp:5006 tcp:5006   # re-add forward
```

If you `force-stop` before removing the forward, adbd floods the port with
zero-window-probe connections that stay stuck until device reboot.

---

## 5. Host Side

Location: `proto/host/src/main.rs`

### Key constants

```rust
const DEFAULT_CAPTURE_SIZE: &str = "1280x720";
const JPEG_Q_MIN: u8 = 68;
const JPEG_Q_MAX: u8 = 90;
const JPEG_Q_DEFAULT: u8 = 82;
const JPEG_Q_TARGET_KB: usize = 140;      // quality governor target
const DEFAULT_CAPTURE_FPS_STR: &str = "60";
```

### Quality governor

- Starts at Q=82
- If frame > `max_frame_bytes`: Q -= 4 (min Q_MIN=68)
- `max_frame_bytes` is set from bitrate target at startup
- ADB push stats logged every ~5s: `sent=N avg_kb=N skip_same=N late_ticks=N drop_oversize=N`

### Capture methods (priority order)

1. **Portal pipeline** (Wayland, PROTO_FORCE_PORTAL=1): Python + GStreamer + PipeWire
2. **grim** (direct Wayland screencopy): `grim -o <output> -q <Q> -t jpeg -`
   - NOTE: `grim -s` is a **float scale factor**, NOT "WxH" — passing "1280x800" is silently ignored
   - grim always captures full output resolution
3. **spectacle** (KDE screenshot tool)
4. **import** (ImageMagick X11): `-resize WxH!` — only one that does resize

### run.sh resolution auto-detection (added 2026-02-24)

After preset block, before host start:
```bash
# Method 1: wm size (Android >= 4.3)
_dev_size=$(adb shell wm size | grep -oP '(?<=: )\d+x\d+' | tail -1)
# Method 2: dumpsys window (API 17+) — "init=1280x800 160dpi"
_dev_size=$(adb shell dumpsys window | grep -oP 'init=\K\d+x\d+' | head -1)
# Result: PROTO_CAPTURE_SIZE=1280x800 (overrides preset)
```

Override with `PROTO_CAPTURE_SIZE_OVERRIDE=1` to keep preset.

### Presets (run.sh)

| Preset    | Size (before auto-detect) | FPS | Bitrate   |
|-----------|--------------------------|-----|-----------|
| fast      | 960×540                  | 60  | 12000 kbps |
| balanced  | 1280×720                 | 30  | 16000 kbps |
| quality   | 1920×1080                | 50  | 26000 kbps |

After auto-detect, all presets use **1280×800** on Lenovo S6000.

---

## 6. Portal Pipeline — Deep Detail

The Wayland capture path (always active in run.sh fast|balanced|quality, PROTO_FORCE_PORTAL=1):

```
stream_wayland_portal_h264.py
  → XDG Desktop Portal ScreenCast API (D-Bus)
  → user picks screen/output in KDE dialog (must pick every launch)
  → PipeWire fd + node_id
  → GStreamer pipeline:
       pipewiresrc → queue(leaky,40ms) → videoconvert → videoscale
       → videorate → capsfilter(1280×800@60fps,I420)
       → tee
         ├─ queue → openh264enc → h264parse → capsfilter → appsink/tcpserversink
         └─ [debug branch: queue → videorate → jpegenc → multifilesink]
  → Rust receives H264 frames from TCP/appsink
  → ffmpeg process per-frame or per-interval: H264 → JPEG
  → writes to /dev/shm/proto-portal-frames/frameNNN.jpg  (PROTO_PORTAL_JPEG_SOURCE=debug)
  → Rust polls directory for latest JPEG
```

**Bottleneck identified:** Every frame goes through two encoders:
1. `openh264enc` (H264) — GStreamer
2. → ffmpeg (decode H264 → JPEG) — subprocess  
3. → file I/O to /dev/shm
4. → Rust reads file

This is 2 encode/decode passes + IPC overhead per frame.

**The fix (not yet implemented):** Replace the H264 path with direct JPEG:
```
pipewiresrc → videoconvert → videoscale → jpegenc → appsink
```
GStreamer already has `jpegenc` element (it's used in the debug branch).
Rust would receive JPEG bytes directly from appsink via TCP — zero intermediate files,
zero H264 roundtrip. This is the **next big optimization target**.

---

## 7. Virtual Display Situation

KDE Plasma 6 on Wayland (Kubuntu 24.04) does **not** have a UI option to add a
virtual monitor.

The "Virtual-virtual-xdp-kde-snap.code" output seen in kscreen-doctor was created
by VS Code during a screen-share session, not by WBeam.

KWin 6 D-Bus has no `createVirtualOutput` API.

The `kscreen-doctor output.NAME.mode.WxH@fps` command exists but requires the
output to already have that mode in its EDID/mode list — virtual outputs typically
only have one mode (1920×1080).

**Conclusion:** Don't chase the virtual monitor approach. The GStreamer `videoscale`
already scales to 1280×800 before H264 encode — that's a sub-ms SIMD operation.
The real win is eliminating the H264→JPEG roundtrip entirely.

---

## 8. Optimizations Done (chronological)

### Stage 0 — Make it work (2026-02-22)
- Complete rewrite of Android app: single-file `MainActivity.java` (~385 lines),
  replaced broken SOLID refactor
- Fixed `libturbojpeg` load order (must precede `libwbeam`)
- Fixed `ServerSocket` bind: no-arg + `setReuseAddress` + explicit `bind`
- Fixed `run.sh` ADB forward ordering (remove before force-stop)
- Fixed 70-second bind retry loop (zero-window-probe sockets drain time)
- Device reboot to clear permanently stuck sockets
- **Result:** end-to-end streaming working, host at 60fps, `avg_kb=32`

### SOLID refactor (2026-02-24)
- Deleted the single-file monolith, split into 15 classes across 6 packages
- JNI exports renamed: `Java_com_proto_demo_MainActivity_` → `Java_com_proto_demo_jni_NativeBridge_`
- Build confirmed clean

### Stage 1 — Decouple IO from render (2026-02-24) — commit 699ac3d
- `FrameMailbox`: lock-free single-slot + 3-buffer pool
  - IO thread: `acquire() → fill → publish()` (never blocks on render)
  - Old unread frames evicted atomically (drop counter)
- `RenderLoop`: dedicated `wbeam-render` thread at `URGENT_DISPLAY`
  - Parks 0.5ms when mailbox empty (no spin-burn)
  - Reports `fps + drop=N` every 2s
- `AdbPushTransport`: `SO_RCVBUF 1 MB`, `BufferedInputStream 512 KB`, `setSoTimeout 5s`
- `MainActivity`: IO thread only does `read + arraycopy(~30KB) + publish`

### Stage 2 — Native device resolution (2026-02-24) — commit 8ef976e
- Removed hardcoded `OUT_W=1920, OUT_H=1080` from `StreamConfig`
- `surfaceChanged(w, h)` propagates real surface dims through chain:
  `RendererChain → NativeRenderer → nativeCreate(w,h) → nativeDecodeAndRender(w,h)`
- `NativeRenderer` handle created lazily on first `surfaceChanged`,
  re-created only when dims change
- turbojpeg `pick_scaled_size()` now targets 1280×800 instead of 1920×1080
- ~50% fewer decoded pixels → faster decode + blit

### Stage 3 — Auto-detect host capture resolution (2026-02-24) — commit 39b9061
- `run.sh` queries device after preset block:
  - Method 1: `adb shell wm size` (API >= 18)
  - Method 2: `adb shell dumpsys window | grep init=` (API 17+)
- Overrides `PROTO_CAPTURE_SIZE` with device native resolution (1280×800)
- Portal Python gets `--size 1280x800` → GStreamer `videoscale` targets this
- `PROTO_CAPTURE_SIZE_OVERRIDE=1` to keep preset
- Virtual display mode attempt via `kscreen-doctor` also added (no-op when
  virtual output not present, which is the common case)

---

## 9. Known Issues / Gotchas

### Portal screen picker
Every `./run.sh ...` run triggers the KDE Wayland portal dialog asking which
screen/monitor to share. The user **must** pick the correct output (the extended
virtual monitor if present, or HDMI-A-1 for now). This is an OS-level dialog —
WBeam cannot pre-select it. There is no way to persist the selection across runs
in KDE portal.

### Portal source appears static
`run.sh` checks frame hashes after portal starts. If all hashes are equal:
```
WARNING: portal source appears static (same frame hash)
```
This means the user picked a static source (e.g., a wallpaper-only desktop) or
the portal is showing a frozen initial frame. Solutions:
- Re-run and pick a different output in the picker
- `PROTO_PORTAL_JPEG_SOURCE=file` (uses /tmp/proto-portal-frame.jpg) if debug mode fails
- `PROTO_PORTAL_JPEG_SOURCE=debug` (reads /dev/shm/proto-portal-frames/*.jpg) — default

### ADB port 5006 stuck (zero-window-probe sockets)
If `run.sh` dies uncleanly (Ctrl+C before app force-stop), the next run
may get `EADDRINUSE` for 70 seconds. The `run.sh` fix (remove forward before kill)
prevents accumulation, but existing stuck sockets need 70s to drain or `adb reboot`.

Symptoms: `adb shell "grep '00000000000000000000000000000000:138E' /proc/net/tcp6 | grep ' 0A '"`
shows many entries (0A = TCP_LISTEN... actually look for stuck CLOSE_WAIT/TIME_WAIT states).

### API 17 limitations
- No `wm` binary → use `dumpsys window` for resolution
- No `head` command in shell → grep/parse on host side
- `adb shell` stderr mixed with stdout in some commands
- Old ARM core → JPEG decode is the limiting factor on tablet side

---

## 10. Next Optimization Targets (in priority order)

### TARGET A — Eliminate H264→JPEG roundtrip (HIGH IMPACT)
Replace the GStreamer pipeline in `stream_wayland_portal_h264.py`:

**Current:**
```
pipewiresrc → ... → openh264enc → h264parse → appsink
                                               ↓
                                    ffmpeg decode H264 → JPEG
                                               ↓
                                    /dev/shm/proto-portal-frames/
                                               ↓
                                    Rust reads file
```

**Target:**
```
pipewiresrc → videoconvert → videoscale → jpegenc → appsink
                                                       ↓
                                             Rust reads JPEG bytes directly via TCP
```

Changes needed:
1. `stream_wayland_portal_h264.py`: add `--encoder jpeg` mode, build JPEG pipeline,
   send raw JPEG bytes (or WBJ1-framed) over TCP instead of H264
2. `host/src/main.rs`: add `PROTO_PORTAL_JPEG_SOURCE=gstream` or similar mode
   that reads JPEG bytes directly from the Python TCP socket instead of polling files
3. Kill ffmpeg subprocess for this path entirely

Expected win: remove 1 encode + 1 decode + file I/O per frame → ~2-5ms latency reduction.

### TARGET B — JPEG quality tuning
The quality governor starts at Q=82 and drops when frames are too large.
At 1280×800, Q=75 gives ~20-25 KB/frame which is well within USB ADB bandwidth.
Consider tuning `JPEG_Q_TARGET_KB` down from 140 to 60-80 for 1280×800 targets.

### TARGET C — Remove videorate (frame duplication)
`videorate` with `drop-only=False` synthesizes frames by duplicating on static scenes.
For a second-screen usecase, duplicating frames wastes USB bandwidth with identical JPEG data.
The Rust host already has `skip_same` (frame signature dedup) — but that's post-encode.
Consider `drop-only=True` + `max-rate=60` to only pass real damage-driven frames.

### TARGET D — Pre-allocated JPEG decode buffer in NDK
Currently `tjDecompress2` in `native_wbeam.cpp` writes directly to `ANativeWindow_Buffer.bits`.
Consider pre-allocated intermediate buffer to allow double-buffering:
decode into buffer A while buffer B is being displayed, then flip.

### TARGET E — TCP_NODELAY + socket pacing
Rust ADB push sender: add `TCP_NODELAY` and `TCP_QUICKACK` on the connected socket
to reduce Nagle algorithm delays. At 30KB/frame you're in Nagle territory.

---

## 11. File Map

```
proto/
  run.sh                              — main orchestrator (build, forward, launch)
  run.sh                              — single launcher: run.sh [fast|balanced|quality]
  agents.md                           — THIS FILE
  host/
    src/main.rs                       — Rust host: capture, encode, ADB push sender
    Cargo.toml
  front/
    app/src/main/
      java/com/proto/demo/            — Android app (see §4)
      cpp/
        native_wbeam.cpp              — JNI: turbojpeg decode + ANativeWindow render
        CMakeLists.txt
        include/turbojpeg.h
      jniLibs/
        arm64-v8a/libturbojpeg.so
        armeabi-v7a/libturbojpeg.so
        x86/libturbojpeg.so
    scripts/
      build_turbojpeg_android.sh      — cross-compile libturbojpeg for Android
    build.gradle
    app/build.gradle
../../src/host/scripts/
  stream_wayland_portal_h264.py       — GStreamer + Portal + H264 pipeline
```

---

## 12. How to Run

```bash
# Plug in Lenovo S6000 via USB, enable ADB debug
cd /mnt/fat_boii/git/WBeam/proto

# Fast preset (60fps, auto-detects device resolution)
./run.sh

# When KDE portal dialog appears: pick the screen you want to share
# Watch logcat for: "ADB: client connected — streaming"
# Watch logcat for: "ADB  XX.X fps  drop=N"

# Stop: Ctrl+C in terminal
```

### Useful debug commands

```bash
# Check device resolution detection
adb shell dumpsys window | grep -oP 'init=\K\d+x\d+'

# Watch live logcat from Android app
adb logcat -s WBeam WBeamNative

# Check for stuck port-5006 sockets on device
adb shell "cat /proc/net/tcp6" | grep "138E"

# Check ADB forward
adb forward --list

# Portal frame check
ls -la /dev/shm/proto-portal-frames/ | tail -5
```

---

## 13. Commit History (relevant)

| Hash    | Summary |
|---------|---------|
| c8c154e | proto: prototype working — ADB push JPEG streaming to Android display |
| afba9cf | proto: SOLID refactor — 15 classes across 6 packages, JNI renamed to NativeBridge |
| 699ac3d | proto: stage-1 render opt — decouple IO from render thread (FrameMailbox, RenderLoop) |
| 8ef976e | proto: use native device resolution for rendering (remove hardcoded 1920×1080) |
| 39b9061 | proto: auto-detect device native resolution for host encode size (dumpsys window) |
