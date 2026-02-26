# WBeam PROTO - Kompletny opis techniczny

Ten dokument opisuje aktualny stan prototypu w katalogu `proto/` (Android API 17 + host Linux).
Opis jest oparty o bieżący kod i skrypty uruchomieniowe.

## 1) Cel i zakres

- Cel: przewodowy second-screen (USB/ADB), niska latencja, stabilny streaming.
- Zakres `proto`: udowodnienie pipeline end-to-end na legacy Android (minSdk 17).
- Poza zakresem: produkcyjna architektura (oddzielne gałęzie/moduły poza `proto`).

## 2) Mapa komponentów

```text
proto/
  run.sh                      # pojedynczy launcher całego flow
  config/proto.conf           # jedyne źródło runtime konfiguracji

  front/                      # Android app (minSdk 17)
    app/src/main/java/com/proto/demo/
      MainActivity.java       # coordinator lifecycle/surface/start-stop transport
      config/StreamConfig.java
      transport/
        AdbPushTransport.java # WBJ1 JPEG over ADB forward :5006
        H264Transport.java    # WBH1 H264 over ADB forward :5006 -> MediaCodec Surface
        HttpMjpegTransport.java
      rendering/
        RenderLoop.java       # thread renderujący
        RendererChain.java
        NativeRenderer.java   # JNI turbojpeg + ANativeWindow
        JavaRenderer.java     # fallback BitmapFactory + Canvas
      pipeline/FrameMailbox.java
      jni/NativeBridge.java
      ui/ScreenLayout.java
      ui/StatusUpdater.java
    app/src/main/cpp/native_wbeam.cpp

  host/                       # Rust backend
    src/main.rs               # capture + HTTP + ADB push + H264 relay

  host-cs/                    # opcjonalny backend C# (H264 framed relay)
    Program.cs
```

## 3) Architektura end-to-end (ASCII)

### 3.1 Tryb JPEG (WBJ1)

```text
[Linux desktop]
   |
   | capture (portal/grim/spectacle/import)
   v
[Rust host main.rs]
   |  shared latest JPEG
   |  + quality governor
   |
   +--> HTTP :5005 (/image, /mjpeg) --------------------+
   |                                                    |
   +--> ADB push framed WBJ1 -> tcp 127.0.0.1:5006 ----+-- adb forward --> Android :5006
                                                         
[Android app]
   AdbPushTransport (wbeam-io) -> FrameMailbox -> RenderLoop (wbeam-render)
   -> NativeRenderer (turbojpeg/ANativeWindow) lub JavaRenderer fallback
   -> SurfaceView fullscreen
```

### 3.2 Tryb H264 (WBH1)

```text
[Wayland Portal + GStreamer helper]
   pipewiresrc -> H264 stream -> tcp://127.0.0.1:5500 (WBTP framed lub Annex-B)
                                |
                                v
[Rust host run_h264_loop OR host-cs relay]
   parse źródła -> konwersja na WBH1 (magic+seq+ts+len+payload)
   -> tcp 127.0.0.1:5006 -> adb forward -> Android :5006

[Android app]
   H264Transport (wbeam-io)
   -> MediaCodec video/avc decoder
   -> output Surface (SurfaceView)
```

## 4) Sekwencja startu (`run.sh`)

```text
1. load config/proto.conf
2. walidacja: brak runtime ENV override (PROTO_*, RUN_*, ...)
3. wybór backendu: rust|cs, urządzenia: adb|qemu
4. dobór presetu (fast|balanced|quality)
5. opcjonalna autodetekcja natywnej rozdzielczości urządzenia (adb wm/dumpsys)
6. build APK (debug/release), install na urządzenie
7. adb reverse :5005 (jeśli możliwe) + adb forward :5006 (ADB push)
8. start host backendu (Rust lub C#)
9. opcjonalny wait na pierwszą klatkę portal (pomijany dla H264)
10. launch MainActivity z Intent extras
```

Krytyczny porządek przy restarcie app:

```text
adb forward --remove tcp:5006
sleep 1
am force-stop com.proto.demo
sleep 5
adb forward tcp:5006 tcp:5006
am start ...
```

## 5) Transporty, porty i protokoły

### 5.1 Porty

- `5005`: host HTTP (`/health`, `/image`, `/mjpeg`) - tylko gdy `PROTO_H264 != 1` w Rust.
- `5006`: kanał ADB framed push do Android app (`WBJ1` lub `WBH1`).
- `5500`: lokalny port źródła portal helper (`stream_wayland_portal_h264.py`).

### 5.2 WBJ1 (JPEG over TCP)

Nagłówek 24 bajty (big-endian):

```text
magic[4] = "WBJ1"
seq[8]
ts_ms[8]
len[4]
payload[len] = JPEG
```

### 5.3 WBH1 (H264 NAL over TCP)

Nagłówek identycznej długości, inny magic:

```text
magic[4] = "WBH1"
seq[8]
ts_ms[8]
len[4]
payload[len] = H264 NAL / unit
```

### 5.4 WBTP (portal framed source, wejście do hosta)

22 bajty:

```text
magic[4] = "WBTP"
version[1] = 1
flags[1]
seq[4]
capture_ts_us[8]
payload_len[4]
payload[...] (H264 access unit)
```

Opcjonalny handshake `WBS1` (16+ bajtów) na początku strumienia.

## 6) Android - szczegóły wykonania

### 6.1 Lifecycle i wiring

`MainActivity`:

- `onCreate`:
- pełny ekran, keep-screen-on
- tworzy `StreamConfig`, layout, status updater, renderer chain
- podłącza `SurfaceHolder.Callback`
- `surfaceChanged`:
- dla JPEG: ustawia surface do native renderer
- startuje/restartuje IO (`startIO()`)
- `surfaceDestroyed`:
- zatrzymuje IO i odłącza surface rendererów

### 6.2 Wątki Android

- `wbeam-io` (`THREAD_PRIORITY_URGENT_DISPLAY`):
- odbiór TCP/HTTP, parsowanie ramek
- `wbeam-render` (`THREAD_PRIORITY_URGENT_DISPLAY`, tylko JPEG):
- drain mailbox + render
- UI thread:
- tylko overlay status (`StatusUpdater` -> `Handler(Looper.getMainLooper())`)

### 6.3 JPEG path

- `AdbPushTransport`:
- `ServerSocket` bind retry (do 70 prób)
- `setReuseAddress(true)` przed `bind`
- reorder queue (`MAX_REORDER_FRAMES=8`, `REORDER_WAIT_MS=35`)
- `FrameMailbox`:
- lock-free single pending slot
- nadpisanie pending = drop starszej klatki (brak blokad producer/consumer)
- `RenderLoop`:
- park 0.5 ms gdy idle
- statystyki co 2 sekundy

### 6.4 H264 path

`H264Transport`:

- nasłuch na `:5006`, odbiór `WBH1`
- dwa tryby decode:
- direct (domyślny, najniższa latencja)
- reorder (opcjonalny, `h264_reorder=true`)
- agregacja NAL do Access Unit (`MAX_AU=4MB`), heurystyki boundary
- konfiguracja `MediaCodec video/avc` po otrzymaniu SPS/PPS
- output na `SurfaceView` surface

Parametry istotne:

- `MAX_NAL=2MB`
- reorder H264: `MAX_REORDER_FRAMES=8`, `REORDER_WAIT_MS=12ms`
- stats co `2s`

### 6.5 JNI/native rendering (JPEG)

`NativeBridge`:

- kolejność ładowania bibliotek ma znaczenie:
- `System.loadLibrary("turbojpeg")` potem `System.loadLibrary("wbeam")`

`native_wbeam.cpp`:

- decode `tjDecompress2` (libturbojpeg)
- render przez `ANativeWindow_lock/unlockAndPost`
- format bufora: `WINDOW_FORMAT_RGBX_8888`
- flagi turbojpeg: `TJFLAG_FASTDCT | TJFLAG_FASTUPSAMPLE`
- wybór skali: `pick_scaled_size` (turbo scaling factors)

## 7) Host Rust - szczegóły wykonania

### 7.1 Tryb główny

- jeśli `PROTO_H264=1` -> uruchamia `run_h264_loop()` i kończy ścieżkę HTTP/JPEG.
- jeśli `PROTO_H264=0` -> uruchamia:
- capture refresh loop (aktualizacja latest JPEG)
- opcjonalny sender ADB `WBJ1`
- serwer HTTP `tiny_http` na `0.0.0.0:5005`

### 7.2 Capture źródła desktopu

Priorytet:

1. portal (`capture_portal_jpeg`) gdy włączony
2. `grim`
3. `spectacle`
4. `import` (ImageMagick)

Resize:

- `grim -s <scale>` gdy da się policzyć skalę
- fallback `ffmpeg scale=...` dla JPEG

### 7.3 ADB push sender (WBJ1)

- docelowy FPS: `PROTO_ADB_PUSH_FPS` (clamp 1..60)
- adaptive quality governor:
- `JPEG_Q_MIN=68`, `JPEG_Q_MAX=90`, start `82`
- target rozmiaru klatki `PROTO_JPEG_TARGET_KB` (domyślnie 140 KB)
- oversize drop jeśli `jpeg.len > PROTO_MAX_FRAME_BYTES`
- keepalive resend, gdy frame się nie zmienia
- chunked write (`PROTO_MAX_CHUNK_BYTES`, default 14KB)

### 7.4 H264 relay (WBTP -> WBH1)

- sink: `PROTO_ADB_PUSH_ADDR` (default `127.0.0.1:5006`)
- source: `127.0.0.1:<PROTO_H264_SOURCE_PORT>` (default 5500)
- `PROTO_H264_SOURCE_FRAMED=1`: parser WBTP + opcjonalne WBS1 hello
- rozbicie access-unit na NAL (Annex-B start codes) i wysyłka `WBH1`
- retry/reconnect z backoff przy rozłączeniach

## 8) Host C# (opcjonalny backend)

- aktywowany przez `RUN_BACKEND=cs`.
- obecnie wspiera tylko H264 (`PROTO_H264=1`).
- relay framed source (`WBTP`) -> `WBH1` do Android sink.
- Annex-B relay w C# nie jest zaimplementowany (explicit exception).

## 9) Konfiguracja runtime (`config/proto.conf`)

Najważniejsze flagi:

- `RUN_BACKEND=rust|cs`
- `RUN_DEVICE=adb|qemu`
- `PROTO_PRESET=fast|balanced|quality`
- `PROTO_H264=0|1`
- `PROTO_H264_SOURCE_FRAMED=0|1`
- `PROTO_ADB_PUSH=0|1`
- `PROTO_CAPTURE_SIZE`, `PROTO_CAPTURE_FPS`, `PROTO_CAPTURE_BITRATE_KBPS`
- `PROTO_FORCE_JAVA_FALLBACK=0|1`
- `PROTO_REQUIRE_TURBO=0|1`

Domyślny profil w repo (aktualny `proto.conf`):

- `RUN_BACKEND=rust`
- `RUN_DEVICE=adb`
- `PROTO_PRESET=fast`
- `PROTO_H264=1`
- `PROTO_H264_SOURCE_FRAMED=1`
- `PROTO_H264_REORDER=0`
- `PROTO_FORCE_NATIVE_SIZE=0`
- `WBEAM_VIDEORATE_DROP_ONLY=0`
- `WBEAM_FRAMED_DUPLICATE_STALE=1`
- `WBEAM_PIPEWIRE_KEEPALIVE_MS=33`
- `WBEAM_PIPEWIRE_ALWAYS_COPY=1`
- `WBEAM_FRAMED_PULL_TIMEOUT_MS=8`

## 10) Presety (`run.sh`)

`fast`:

- size `1024x640`, bitrate `16000 kbps`, fps capture `45`, ADB push `45`

`balanced`:

- size `960x600`, bitrate `9000 kbps`, fps capture `30`, ADB push `24`

`quality`:

- size `1920x1080`, bitrate `26000 kbps`, fps capture `50`

Dodatkowo `run.sh` może nadpisać size przez natywną rozdzielczość urządzenia z ADB (`wm size` / `dumpsys window`).

## 11) Krytyczne inwarianty stabilności

1. `ServerSocket`:
- `new ServerSocket()` -> `setReuseAddress(true)` -> `bind(...)`

2. ADB restart flow:
- usunięcie forward przed `force-stop`, potem ponowny forward

3. JNI lib order:
- `turbojpeg` musi być załadowane przed `wbeam`

4. H264 decode start:
- `MediaCodec.dequeueOutputBuffer` tylko po `configure()+start()`

5. API17:
- preferowane ścieżki minimalizujące GC i kopiowanie
- separacja IO/render thread

## 12) Ostatnia poprawka stabilności (API17 / IllegalStateException)

W `front/app/src/main/java/com/proto/demo/transport/H264Transport.java` dodano warunek,
żeby `drainOutput(codec)` nie wywoływało `dequeueOutputBuffer()` przed `MediaCodec.start()`.

Efekt:

- eliminuje typowy `IllegalStateException` na starszych urządzeniach, gdy output drain
  następuje zanim codec wejdzie w stan `STARTED`.

## 13) Failure modes i szybkie debugowanie

### 13.1 App nie startuje / crash po launch

- `adb logcat -d | grep -E "AndroidRuntime|FATAL EXCEPTION|com.proto.demo|IllegalStateException"`
- sprawdź, czy `run.sh` wykonał sekwencję forward/remove/force-stop

### 13.2 Brak klatek H264

- host log: `/tmp/proto-portal-streamer.log`, `/tmp/proto-portal-ffmpeg.log`
- sprawdź portal picker (wybrany monitor)
- sprawdź `PROTO_H264_SOURCE_FRAMED` zgodnie z helperem

### 13.3 Brak native turbo

- szukaj w logcat: `failed to load libwbeam|failed to load libturbojpeg`
- sprawdź `jniLibs/<abi>/libturbojpeg.so` i `libwbeam.so`

### 13.4 Port 5006 busy

- na Android: wait/retry jest zaimplementowany, ale przy szybkim restarcie nadal może być backoff
- upewnij się, że app została ubita i forward odtworzony we właściwej kolejności

## 14) Co warto usprawnić dalej

1. Zredukować koszt ścieżki portal->JPEG (usunąć zbędne transkody).
2. Rozszerzyć telemetry (in/out fps, decode queue depth, reconnect reasons).
3. Dodać testy integracyjne parserów ramek (`WBJ1/WBH1/WBTP`) na fixture binarnych.
4. Ujednolicić backend Rust/C# (feature parity, zwłaszcza Annex-B relay w C#).

## 15) Responsiveness - wyniki inwestygacji

Największy bottleneck wykryty w praktyce to nie Android decode/render, tylko niestabilna
częstotliwość klatek z portal helpera (PipeWire/portal source), co dawało bardzo niskie
`pipeline_fps` i wtórnie niskie `H264 out`.

Wdrożone zmiany:

1. `drop-only=0` w `videorate` (umożliwia duplikację klatek dla stabilniejszego CFR).
2. Dynamiczny `pipewiresrc keepalive-time` + domyślnie `33 ms`.
3. `pipewiresrc always-copy=1` (konfigurowalne).
4. Krótszy pull-timeout framed sendera (konfigurowalne), żeby fallback duplikacji nie
   ograniczał maksymalnego FPS.
5. Domyślna konfiguracja `run.sh` ustawiona na tryb responsiveness-first (`fast`,
   brak wymuszenia natywnej rozdzielczości).

Efekt (pomiar po zmianach, run na urządzeniu API17):

- Android `H264 out` średnio ~24.9 fps (p50 ~24.8, max ~29.4), `drop=0`.
- Przed zmianami w praktyce obserwowane było typowo ~0.8-9 fps i odczuwalny lag.
