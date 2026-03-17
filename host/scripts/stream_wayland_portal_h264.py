#!/usr/bin/env python3
import argparse
import os
import random
import re
import signal
import socket
import struct
import sys
import threading
from pathlib import Path

import dbus
import dbus.mainloop.glib
import gi

gi.require_version("Gst", "1.0")
from gi.repository import GLib, Gst

PORTAL_BUS = "org.freedesktop.portal.Desktop"
PORTAL_PATH = "/org/freedesktop/portal/desktop"
SCREENCAST_IFACE = "org.freedesktop.portal.ScreenCast"
REQUEST_IFACE = "org.freedesktop.portal.Request"
SESSION_IFACE = "org.freedesktop.portal.Session"

CURSOR_MODE_MAP = {
    "hidden": 1,
    "embedded": 2,
    "metadata": 4,
}

DEFAULT_CAPTURE_SIZE = "1280x800"
DEFAULT_CAPTURE_FPS = 60
DEFAULT_CAPTURE_BITRATE_KBPS = 10000
DEFAULT_NV_PRESET = "p4"

# WBTP/1 wire format - must match wbtp-core/src/lib.rs exactly.
#   magic(4)=b"WBTP" | version(1)=1 | flags(1) | seq(4) | capture_ts_us(8) | payload_len(4)
#   Total: 22 bytes  (no reserved, no CRC - CRC flag not set)
# flags bit-mask:
#   0x01 = HAS_CHECKSUM (not used here)
#   0x02 = KEYFRAME
#   0x04 = END_OF_STREAM
FRAME_MAGIC = b"WBTP"
FRAME_VERSION = 0x01
FRAME_FLAG_KEYFRAME = 0x02
FRAME_FLAG_EOS      = 0x04
FRAME_HEADER_SIZE = 22
FRAME_STRUCT = struct.Struct(">4sBBIQI")  # magic(4s) ver flags seq ts_us len = 22 bytes
HELLO_MAGIC = b"WBS1"
HELLO_VERSION = 0x01
HELLO_STRUCT = struct.Struct(">4sBBHQ")  # magic ver flags len session_id = 16 bytes

STATE = {
    "main_loop": None,
    "session_handle": None,
    "pipeline": None,
    "bus": None,
    "framing_thread": None,
    "framing_stop": None,
}


def send_all_iov(conn, iov):  # NOSONAR: partial-send accounting needs explicit branching
    """Send full iovec payload via sendmsg, handling partial writes."""
    views = [memoryview(chunk) for chunk in iov if len(chunk)]
    if not views:
        return 1

    idx = 0
    offset = 0
    send_calls = 0
    while idx < len(views):
        if offset:
            chunks = [views[idx][offset:]]
            if idx + 1 < len(views):
                chunks.extend(views[idx + 1 :])
        else:
            chunks = views[idx:]

        sent = conn.sendmsg(chunks)
        send_calls += 1
        if sent <= 0:
            raise BrokenPipeError("sendmsg returned 0 bytes")

        remaining = sent
        while idx < len(views):
            left = len(views[idx]) - offset
            if remaining < left:
                offset += remaining
                break
            remaining -= left
            idx += 1
            offset = 0
            if remaining == 0:
                break

    return send_calls


def build_variant_dict(values):
    return dbus.Dictionary(values, signature="sv")


def wait_for_request_response(session_bus, request_path):
    result = {}
    loop = GLib.MainLoop()

    def on_response(response, results):
        result["response"] = int(response)
        result["results"] = dict(results)
        loop.quit()

    session_bus.add_signal_receiver(
        on_response,
        signal_name="Response",
        dbus_interface=REQUEST_IFACE,
        path=request_path,
    )

    loop.run()

    session_bus.remove_signal_receiver(
        on_response,
        signal_name="Response",
        dbus_interface=REQUEST_IFACE,
        path=request_path,
    )

    return result


def portal_request(session_bus, iface, method_name, *args):
    method = iface.get_dbus_method(method_name, SCREENCAST_IFACE)
    request_path = method(*args)
    response = wait_for_request_response(session_bus, request_path)
    if response.get("response", 2) != 0:
        raise RuntimeError(f"Portal request '{method_name}' failed: {response}")
    return response.get("results", {})


def create_screencast_session(session_bus, iface):
    token = random.randint(100000, 999999)
    options = build_variant_dict(
        {
            "handle_token": dbus.String(f"wbeam_create_{token}"),
            "session_handle_token": dbus.String(f"wbeam_session_{token}"),
        }
    )
    results = portal_request(session_bus, iface, "CreateSession", options)
    return str(results["session_handle"])


def select_sources(session_bus, iface, session_handle, cursor_mode, persist_mode=0, restore_token=""):
    token = random.randint(100000, 999999)
    values = {
        "handle_token": dbus.String(f"wbeam_select_{token}"),
        "types": dbus.UInt32(1),  # monitor
        "multiple": dbus.Boolean(False),
        "cursor_mode": dbus.UInt32(CURSOR_MODE_MAP[cursor_mode]),
    }
    if int(persist_mode) > 0:
        values["persist_mode"] = dbus.UInt32(int(persist_mode))
    if restore_token:
        values["restore_token"] = dbus.String(str(restore_token))
    options = build_variant_dict(values)
    portal_request(session_bus, iface, "SelectSources", dbus.ObjectPath(session_handle), options)


def start_session(session_bus, iface, session_handle):
    token = random.randint(100000, 999999)
    options = build_variant_dict({"handle_token": dbus.String(f"wbeam_start_{token}")})
    results = portal_request(
        session_bus,
        iface,
        "Start",
        dbus.ObjectPath(session_handle),
        "",
        options,
    )

    streams = results.get("streams")
    if not streams:
        raise RuntimeError("Portal start returned no streams")
    restore_token = results.get("restore_token")
    if restore_token is not None:
        restore_token = str(restore_token)
    return int(streams[0][0]), restore_token


def open_pipewire_fd(iface, session_handle):
    fd = iface.OpenPipeWireRemote(
        dbus.ObjectPath(session_handle),
        build_variant_dict({}),
        dbus_interface=SCREENCAST_IFACE,
    )

    if hasattr(fd, "take"):
        return int(fd.take())
    return int(fd)


def close_session(session_bus, session_handle):
    if not session_handle:
        return
    try:
        session_obj = session_bus.get_object(PORTAL_BUS, session_handle)
        dbus.Interface(session_obj, SESSION_IFACE).Close()
    except Exception as exc:
        sys.stderr.write(f"[warn] failed to close portal session: {exc}" + "\n")


def load_restore_token(path):
    if not path:
        return ""
    p = Path(path).expanduser()
    try:
        token = p.read_text(encoding="utf-8").strip()
    except FileNotFoundError:
        return ""
    except Exception as exc:
        sys.stderr.write(f"[warn] failed to read restore token from {p}: {exc}" + "\n")
        return ""
    return token


def save_restore_token(path, token):
    if not path or not token:
        return
    p = Path(path).expanduser()
    try:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(str(token).strip() + "\n", encoding="utf-8")
        print(f"[wbeam] saved restore token to {p}", flush=True)
    except Exception as exc:
        sys.stderr.write(f"[warn] failed to save restore token to {p}: {exc}" + "\n")


def pick_encoder(requested):
    nv = Gst.ElementFactory.find("nvh264enc") is not None
    oh = Gst.ElementFactory.find("openh264enc") is not None

    if requested == "nvenc":
        if not nv:
            raise RuntimeError("Requested NVENC, but nvh264enc element is not available")
        return "nvenc"

    if requested == "openh264":
        if not oh:
            raise RuntimeError("Requested openh264, but openh264enc element is not available")
        return "openh264"

    if nv:
        return "nvenc"
    if oh:
        return "openh264"

    raise RuntimeError("No supported H264 encoder found (nvh264enc/openh264enc)")


def configure_encoder(enc, encoder_name, bitrate_kbps, fps, nv_preset):
    # Shorter GOP helps decoder recovery on transient transport damage and reduces
    # long artifact streaks at the cost of slightly higher bitrate pressure.
    gop_default = max(15, min(int(fps), 30))
    gop = max(10, min(240, env_int("WBEAM_H264_GOP", gop_default)))
    if encoder_name == "nvenc":
        enc.set_property("bitrate", int(bitrate_kbps))
        enc.set_property("max-bitrate", int(bitrate_kbps))
        enc.set_property("rc-mode", "cbr")
        enc.set_property("preset", nv_preset)
        enc.set_property("gop-size", gop)
        enc.set_property("bframes", 0)
        enc.set_property("zerolatency", True)
        enc.set_property("aud", True)
        enc.set_property("repeat-sequence-header", True)
        return

    # openh264enc bitrate is bits/s
    enc.set_property("bitrate", int(bitrate_kbps) * 1000)
    enc.set_property("rate-control", "bitrate")
    enc.set_property("complexity", "high")
    enc.set_property("gop-size", gop)
    enc.set_property("multi-thread", 0)
    enc.set_property("slice-mode", "n-slices")
    enc.set_property("num-slices", 1)
    enc.set_property("scene-change-detection", False)
    enc.set_property("background-detection", False)
    enc.set_property("qp-min", 8)
    enc.set_property("qp-max", 32)


def set_if_supported(element, prop_name, value):
    if element is not None and element.find_property(prop_name) is not None:
        element.set_property(prop_name, value)


def env_flag(name, default):
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def env_int(name, default):
    value = os.getenv(name)
    if value is None:
        return default
    try:
        return int(value.strip())
    except Exception:
        return default


def framed_tcp_server_thread(appsink, port, stop_event, pipeline_fps_counter=None, target_fps=60):  # NOSONAR: sender loop complexity is intentional
    """WBTP/1 framed sender: accept one TCP client; send WBTP/1-framed H264 access units.

    Header (big-endian, 22 bytes):
        magic(4)=b"WBTP" | version(1)=1 | flags(1) | seq(4) | capture_ts_us(8) | payload_len(4)
    """
    import time as _time
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", port))
    srv.listen(1)
    srv.settimeout(1.0)
    # Default to blocking send for stability over ADB tunnel; set env >0 to enable timeout.
    send_timeout_s = float(os.getenv("WBEAM_FRAMED_SEND_TIMEOUT_S", "0"))
    duplicate_stale = env_flag("WBEAM_FRAMED_DUPLICATE_STALE", False)
    # Dropping encoded H264 AUs can break inter-frame decode (P/B refs) and look like
    # "frozen video". Keep disabled by default; q1/qmain already drop raw pre-encode.
    drop_queued_encoded = env_flag("WBEAM_FRAMED_DROP_QUEUED", False)
    seq = 0
    print(f"[wbeam-framed] listening on :{port}", flush=True)
    # Optional fallback: duplicate latest keyframe only after prolonged source stall.
    # Re-sending delta/P-frames can create visible artifacts; keyframe-only duplication
    # is visually safer (at the cost of less motion smoothness during hard stalls).
    last_keyframe = None
    last_keyframe_len = 0
    # Pull timeout drives how quickly we can observe source stalls.
    fps = max(1, int(target_fps))
    pull_timeout_ms_auto = max(2, min(40, int(round(1000.0 / fps))))
    pull_timeout_ms = max(1, min(100, env_int("WBEAM_FRAMED_PULL_TIMEOUT_MS", pull_timeout_ms_auto)))
    pull_timeout_ns = int(pull_timeout_ms * 1_000_000)
    frame_period_ns = int(1_000_000_000 / fps)
    stale_start_ms = max(40, min(2000, env_int("WBEAM_FRAMED_STALE_START_MS", 180)))
    stale_start_ns = stale_start_ms * 1_000_000
    stale_dup_fps = max(1, min(30, env_int("WBEAM_FRAMED_STALE_DUP_FPS", 12)))
    stale_period_ns = int(1_000_000_000 / stale_dup_fps)
    next_stale_due_ns = 0
    last_real_sample_ns = _time.monotonic_ns()
    print(
        "[wbeam-framed] sender_cfg "
        f"fps={fps} pull_timeout_ms={pull_timeout_ms} "
        f"duplicate_stale={int(duplicate_stale)} drop_queued={int(drop_queued_encoded)} "
        f"send_timeout_s={send_timeout_s} "
        f"stale_start_ms={stale_start_ms} stale_dup_fps={stale_dup_fps}",
        flush=True,
    )

    while not stop_event.is_set():
        try:
            conn, addr = srv.accept()
        except socket.timeout:
            continue
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        # Larger send buffer reduces syscall overhead at high frame rates
        conn.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 512 * 1024)
        if send_timeout_s > 0:
            conn.settimeout(send_timeout_s)
        try:
            session_id = random.getrandbits(64)
            hello = HELLO_STRUCT.pack(HELLO_MAGIC, HELLO_VERSION, 0x00, HELLO_STRUCT.size, session_id)
            send_all_iov(conn, [hello])
            print(f"[wbeam-framed] client connected: {addr}", flush=True)
            print(f"[wbeam-framed] session_id=0x{session_id:016x}", flush=True)
            _stat_frames = 0
            _stat_timeout_misses = 0
            _stat_stale_duplicates = 0
            _stat_partial_writes = 0
            _stat_send_timeouts = 0
            _stat_dropped_queued = 0
            _stat_t0 = _time.monotonic()
            while not stop_event.is_set():
                sample = appsink.emit("try-pull-sample", pull_timeout_ns)
                payload_view = None
                payload_len = 0
                flags = FRAME_FLAG_KEYFRAME
                # Wall-clock capture timestamp used by Rust-side latency stats.
                # Keep it in epoch microseconds; do not use GStreamer running-time PTS.
                pts_us = int(_time.time() * 1_000_000)
                buf = None
                map_info = None

                if sample is None:
                    # No fresh buffer from PipeWire.
                    _stat_timeout_misses += 1
                    now_ns = _time.monotonic_ns()
                    stale_age_ns = now_ns - last_real_sample_ns
                    if (
                        not duplicate_stale
                        or last_keyframe is None
                        or stale_age_ns < stale_start_ns
                    ):
                        elapsed = _time.monotonic() - _stat_t0
                        if elapsed >= 1.0:
                            sent_fps = _stat_frames / elapsed
                            pipe_fps = 0
                            if pipeline_fps_counter is not None:
                                pipe_fps = pipeline_fps_counter[0]
                                pipeline_fps_counter[0] = 0
                            print(
                                f"[wbeam-framed] pipeline_fps={pipe_fps} sender_fps={sent_fps:.1f}"
                                f" timeout_misses={_stat_timeout_misses}"
                                f" stale_dupe={_stat_stale_duplicates} seq={seq}",
                                flush=True,
                            )
                            _stat_frames = 0
                            _stat_timeout_misses = 0
                            _stat_stale_duplicates = 0
                            _stat_t0 = _time.monotonic()
                        continue

                    if next_stale_due_ns == 0:
                        next_stale_due_ns = now_ns
                    if now_ns < next_stale_due_ns:
                        continue
                    payload_view = memoryview(last_keyframe)
                    payload_len = last_keyframe_len
                    flags = FRAME_FLAG_KEYFRAME
                    _stat_stale_duplicates += 1
                    while next_stale_due_ns <= now_ns:
                        next_stale_due_ns += stale_period_ns
                else:
                    # Optional latency guard: drop queued encoded frames only when
                    # explicitly enabled (can cause decode freezes on inter-frame codecs).
                    if drop_queued_encoded:
                        while True:
                            newer = appsink.emit("try-pull-sample", 0)
                            if newer is None:
                                break
                            sample = newer
                            _stat_dropped_queued += 1
                    buf = sample.get_buffer()
                    ok, map_info = buf.map(Gst.MapFlags.READ)
                    if not ok:
                        continue
                    payload_len = map_info.size
                    payload_view = memoryview(map_info.data)
                    flags = 0x00 if buf.has_flags(Gst.BufferFlags.DELTA_UNIT) else FRAME_FLAG_KEYFRAME
                    # Copy keyframe only for safe stale duplication.
                    if flags & FRAME_FLAG_KEYFRAME:
                        last_keyframe = bytes(map_info.data)
                        last_keyframe_len = payload_len
                    last_real_sample_ns = _time.monotonic_ns()
                    next_stale_due_ns = _time.monotonic_ns() + frame_period_ns

                header = FRAME_STRUCT.pack(
                    FRAME_MAGIC, FRAME_VERSION, flags,
                    seq & 0xFFFFFFFF, pts_us, payload_len
                )

                try:
                    send_calls = send_all_iov(conn, [header, payload_view])
                except socket.timeout:
                    _stat_send_timeouts += 1
                    _stat_timeout_misses += 1
                    continue
                finally:
                    if buf is not None and map_info is not None:
                        buf.unmap(map_info)

                if send_calls > 1:
                    _stat_partial_writes += 1
                seq += 1
                _stat_frames += 1
                elapsed = _time.monotonic() - _stat_t0
                if elapsed >= 1.0:
                    sent_fps = _stat_frames / elapsed
                    pipe_fps = 0
                    if pipeline_fps_counter is not None:
                        pipe_fps = pipeline_fps_counter[0]
                        pipeline_fps_counter[0] = 0
                    print(
                        f"[wbeam-framed] pipeline_fps={pipe_fps} sender_fps={sent_fps:.1f}"
                        f" timeout_misses={_stat_timeout_misses}"
                        f" stale_dupe={_stat_stale_duplicates}"
                        f" dropped_queued={_stat_dropped_queued}"
                        f" partial_writes={_stat_partial_writes}"
                        f" send_timeouts={_stat_send_timeouts}"
                        f" seq={seq}",
                        flush=True,
                    )
                    _stat_frames = 0
                    _stat_timeout_misses = 0
                    _stat_stale_duplicates = 0
                    _stat_dropped_queued = 0
                    _stat_partial_writes = 0
                    _stat_send_timeouts = 0
                    _stat_t0 = _time.monotonic()
        except OSError as exc:
            print(f"[wbeam-framed] client disconnected: {exc}", flush=True)
        finally:
            try:
                conn.close()
            except Exception:
                pass
    try:
        srv.close()
    except Exception:
        pass
    print("[wbeam-framed] sender thread stopped", flush=True)


def make_pipeline(  # NOSONAR: pipeline assembly requires many guarded branches
    fd,
    node_id,
    width,
    height,
    fps,
    bitrate_kbps,
    port,
    debug_dir,
    debug_fps,
    encoder_name,
    nv_preset,
    framed=False,
):
    pipeline = Gst.Pipeline.new("wbeam-wayland-pipeline")

    src = Gst.ElementFactory.make("pipewiresrc", "src")
    queue = Gst.ElementFactory.make("queue", "q1")
    convert = Gst.ElementFactory.make("videoconvert", "conv")
    scale = Gst.ElementFactory.make("videoscale", "scale")
    rate = Gst.ElementFactory.make("videorate", "rate")
    caps1 = Gst.ElementFactory.make("capsfilter", "caps1")
    overlays = []
    overlay_enabled = (
        env_flag("WBEAM_OVERLAY_ENABLE", False)
        or bool(os.getenv("WBEAM_OVERLAY_TEXT", "").strip())
        or bool(os.getenv("WBEAM_OVERLAY_TEXT_FILE", "").strip())
    )
    if overlay_enabled:
        # Single unified full-frame HUD overlay.
        ov = Gst.ElementFactory.make("textoverlay", "hud_main")
        if ov is not None:
            set_if_supported(ov, "halignment", 0)  # left
            set_if_supported(ov, "valignment", 0)  # top
            set_if_supported(ov, "xpad", 6)
            set_if_supported(ov, "ypad", 6)
            overlays = [ov]
        if not overlays:
            sys.stderr.write("[warn] textoverlay element unavailable; HUD overlay disabled" + "\n")
            overlay_enabled = False
    tee = Gst.ElementFactory.make("tee", "tee")

    queue_main = Gst.ElementFactory.make("queue", "qmain")
    enc = Gst.ElementFactory.make("nvh264enc" if encoder_name == "nvenc" else "openh264enc", "enc")
    parse = Gst.ElementFactory.make("h264parse", "parse")
    caps2 = Gst.ElementFactory.make("capsfilter", "caps2")
    # C3: framed mode uses appsink + framed_tcp_server_thread; legacy uses tcpserversink
    if framed:
        sink = Gst.ElementFactory.make("appsink", "sink")
    else:
        sink = Gst.ElementFactory.make("tcpserversink", "sink")

    elements = [src, queue, convert, scale, rate, caps1]
    if overlays:
        elements.extend(overlays)
    elements.extend([tee, queue_main, enc, parse, caps2, sink])
    if any(e is None for e in elements):
        element_names = [
            "pipewiresrc",
            "queue",
            "videoconvert",
            "videoscale",
            "videorate",
            "capsfilter",
        ]
        if overlay_enabled:
            element_names.extend(["textoverlay(hud_main)"])
        element_names.extend(
            [
                "tee",
                "queue",
                "encoder",
                "h264parse",
                "capsfilter",
                "appsink" if framed else "tcpserversink",
            ]
        )
        missing = [name for name, e in zip(element_names, elements) if e is None]
        raise RuntimeError(f"Missing GStreamer elements: {', '.join(missing)}")

    # C1: hard queue limits – prevents buffer bloat and unbounded latency growth.
    # leaky=2 (downstream) drops oldest frames so encoder always gets the freshest input.
    # max-size-time 40 ms = ~2.4 frames @60fps; max-size-buffers=2 as secondary guard.
    queue_max_buffers = max(1, min(8, env_int("WBEAM_QUEUE_MAX_BUFFERS", 1)))
    queue_max_time_ms = max(4, min(200, env_int("WBEAM_QUEUE_MAX_TIME_MS", 16)))
    queue_max_time_ns = int(queue_max_time_ms * 1_000_000)
    for q, label in [(queue, "q1"), (queue_main, "qmain")]:
        q.set_property("max-size-buffers", queue_max_buffers)
        q.set_property("max-size-bytes", 0)       # disable bytes limit, use time+buf
        q.set_property("max-size-time", queue_max_time_ns)
        q.set_property("leaky", 2)                # 2 = GST_QUEUE_LEAK_DOWNSTREAM (drop oldest)

    src.set_property("fd", int(fd))
    src.set_property("path", str(node_id))
    src.set_property("do-timestamp", True)
    set_if_supported(src, "always-copy", env_flag("WBEAM_PIPEWIRE_ALWAYS_COPY", True))
    # Lower keepalive improves perceived responsiveness when PipeWire source is
    # damage-driven and temporarily emits sparse updates.
    keepalive_default_ms = max(25, int(round(1000.0 / max(1, int(fps)))))
    keepalive_ms = max(10, min(1000, env_int("WBEAM_PIPEWIRE_KEEPALIVE_MS", keepalive_default_ms)))
    src.set_property("keepalive-time", keepalive_ms)

    raw_format = "NV12" if encoder_name == "nvenc" else "I420"
    caps1.set_property(
        "caps",
        Gst.Caps.from_string(
            f"video/x-raw,format={raw_format},width={width},height={height},framerate={fps}/1"
        ),
    )
    if overlays:
        overlay_text = os.getenv("WBEAM_OVERLAY_TEXT", "").strip() or "AUTOTUNE"
        overlay_font = (
            os.getenv("WBEAM_OVERLAY_FONT_DESC", "JetBrains Mono SemiBold 13").strip()
            or "JetBrains Mono SemiBold 13"
        )
        for ov in overlays:
            set_if_supported(ov, "font-desc", overlay_font)
            set_if_supported(ov, "use-markup", True)
            # Transparent background + light shadow for readability.
            set_if_supported(ov, "shaded-background", False)
            set_if_supported(ov, "draw-shadow", True)
            # Semi-transparent light text (ARGB).
            set_if_supported(ov, "color", 0xB3EAF4FF)
            set_if_supported(ov, "text", "")
        # Backward-compatible fallback when no sectioned text is provided.
        if overlays:
            set_if_supported(overlays[0], "text", overlay_text)

    configure_encoder(enc, encoder_name, bitrate_kbps, fps, nv_preset)
    # Responsiveness-first default: allow videorate to duplicate when source is sparse.
    # Can be disabled via WBEAM_VIDEORATE_DROP_ONLY=1 for lower bandwidth/CPU.
    set_if_supported(rate, "drop-only", env_flag("WBEAM_VIDEORATE_DROP_ONLY", False))
    set_if_supported(rate, "max-rate", int(fps))
    set_if_supported(rate, "average-period", int(1_000_000_000 / max(1, int(fps))))

    parse.set_property("disable-passthrough", True)
    parse.set_property("config-interval", 1)
    if framed:
        # C3: alignment=au → one buffer = one complete H264 access unit (frame)
        caps2.set_property(
            "caps",
            Gst.Caps.from_string("video/x-h264,stream-format=byte-stream,alignment=au"),
        )
        sink.set_property("emit-signals", False)
        # sync=true keeps pipeline on the clock so videorate can synthesize stable CFR output.
        sink.set_property("sync", True)
        # Keep a tiny sink queue, but do not drop encoded AUs at appsink level:
        # dropping inter-coded frames breaks reference chains and creates visual artifacts.
        sink.set_property("max-buffers", max(1, min(8, env_int("WBEAM_APPSINK_MAX_BUFFERS", 2))))
        sink.set_property("drop", False)
    else:
        caps2.set_property(
            "caps",
            Gst.Caps.from_string("video/x-h264,stream-format=byte-stream,alignment=nal"),
        )
        sink.set_property("host", "0.0.0.0")
        sink.set_property("port", int(port))
        sink.set_property("sync", False)

    for element in elements:
        pipeline.add(element)

    for a, b in [
        (src, queue),
        (queue, convert),
        (convert, scale),
        (scale, rate),
        (rate, caps1),
        (tee, queue_main),
        (queue_main, enc),
        (enc, parse),
        (parse, caps2),
        (caps2, sink),
    ]:
        if not a.link(b):
            raise RuntimeError(f"Failed to link {a.get_name()} -> {b.get_name()}")
    if overlays:
        prev = caps1
        for ov in overlays:
            if not prev.link(ov):
                raise RuntimeError(f"Failed to link {prev.get_name()} -> {ov.get_name()}")
            prev = ov
        if not prev.link(tee):
            raise RuntimeError(f"Failed to link {prev.get_name()} -> {tee.get_name()}")
    else:
        if not caps1.link(tee):
            raise RuntimeError(f"Failed to link {caps1.get_name()} -> {tee.get_name()}")

    if debug_dir and int(debug_fps) > 0:
        os.makedirs(debug_dir, exist_ok=True)
        queue_dbg = Gst.ElementFactory.make("queue", "qdbg")
        vr_dbg = Gst.ElementFactory.make("videorate", "vrdbg")
        caps_dbg = Gst.ElementFactory.make("capsfilter", "capsdbg")
        jpeg = Gst.ElementFactory.make("jpegenc", "jpegdbg")
        multi = Gst.ElementFactory.make("multifilesink", "filesdbg")
        dbg_elements = [queue_dbg, vr_dbg, caps_dbg, jpeg, multi]
        if any(e is None for e in dbg_elements):
            raise RuntimeError("Missing GStreamer debug elements for frame dump")
        # C1: qdbg bounded – debug branch must not stall the main pipeline
        queue_dbg.set_property("max-size-buffers", 1)
        queue_dbg.set_property("max-size-bytes", 0)
        queue_dbg.set_property("max-size-time", 200_000_000)  # 200 ms, debug ok
        queue_dbg.set_property("leaky", 2)

        caps_dbg.set_property("caps", Gst.Caps.from_string(f"video/x-raw,framerate={int(debug_fps)}/1"))
        multi.set_property("location", os.path.join(debug_dir, "frame-%06d.jpg"))
        multi.set_property("post-messages", False)
        multi.set_property("max-files", 300)

        for e in dbg_elements:
            pipeline.add(e)

        for a, b in [
            (tee, queue_dbg),
            (queue_dbg, vr_dbg),
            (vr_dbg, caps_dbg),
            (caps_dbg, jpeg),
            (jpeg, multi),
        ]:
            if not a.link(b):
                raise RuntimeError(f"Failed to link debug branch {a.get_name()} -> {b.get_name()}")

    # D1: FPS probe on appsink sinkpad – counts how many encoded frames the
    # pipeline actually delivers (before appsink drop). Compared against sender fps
    # to determine if the bottleneck is pipewiresrc/encoder or the Python sender.
    pipeline_fps_counter = [0]  # [int] – mutated from C probe callback, read from sender thread
    if framed:
        def _fps_probe(pad, info, counter):  # noqa: E306
            counter[0] += 1
            return Gst.PadProbeReturn.OK
        appsink_sinkpad = sink.get_static_pad("sink")
        if appsink_sinkpad:
            appsink_sinkpad.add_probe(
                Gst.PadProbeType.BUFFER,
                _fps_probe,
                pipeline_fps_counter,
            )

    return pipeline, pipeline_fps_counter


def on_bus_message(bus, message):
    mtype = message.type
    if mtype == Gst.MessageType.ERROR:
        err, debug = message.parse_error()
        sys.stderr.write(f"[gst-error] {err}: {debug}" + "\n")
        stop()
    elif mtype == Gst.MessageType.EOS:
        src_name = message.src.get_name() if message.src else "unknown"
        # tcpserversink can emit EOS when a client disconnects; keep pipeline alive.
        if src_name == "sink":
            print("[gst] EOS from sink (client disconnect), keeping pipeline alive")
            return
        print(f"[gst] EOS from {src_name}")
        stop()


def stop(*_args):
    # C3: stop framing thread before pipeline teardown
    stop_event = STATE.get("framing_stop")
    if stop_event is not None:
        stop_event.set()
    framing_thread = STATE.get("framing_thread")
    if framing_thread is not None and framing_thread.is_alive():
        framing_thread.join(timeout=3.0)
    STATE["framing_thread"] = None
    STATE["framing_stop"] = None

    pipeline = STATE.get("pipeline")
    if pipeline is not None:
        pipeline.set_state(Gst.State.NULL)
        STATE["pipeline"] = None

    close_session(STATE.get("bus"), STATE.get("session_handle"))
    STATE["session_handle"] = None

    loop = STATE.get("main_loop")
    if loop is not None and loop.is_running():
        loop.quit()


def parse_args():
    parser = argparse.ArgumentParser(
        description="Wayland KDE screencast via portal -> PipeWire -> H264 TCP"
    )
    parser.add_argument("--port", type=int, default=5000)
    parser.add_argument("--size", default=None)
    parser.add_argument("--fps", type=int, default=None)
    parser.add_argument("--bitrate-kbps", type=int, default=None)
    parser.add_argument(
        "--encoder",
        default="auto",
        help="encoder (auto|nvenc|openh264; h264/h265/rawpng/mjpeg are normalized to compatible fallback)",
    )
    parser.add_argument("--cursor-mode", choices=["hidden", "embedded", "metadata"], default="embedded")
    parser.add_argument("--debug-dir", default="/tmp/wbeam-frames")
    parser.add_argument("--debug-fps", type=int, default=0)
    parser.add_argument("--persist-mode", type=int, default=2,
                        help="Portal persist_mode for source restore (0=off, 1=session, 2=persistent).")
    parser.add_argument("--restore-token-file", default="/tmp/proto-portal-restore-token")
    parser.add_argument("--framed", action="store_true", default=False,
                        help="C3: use framed protocol (or set WBEAM_FRAMED=1)")
    return parser.parse_args()


def resolve_capture_defaults(args):
    size = args.size or DEFAULT_CAPTURE_SIZE
    fps = args.fps if args.fps is not None else DEFAULT_CAPTURE_FPS
    bitrate_kbps = args.bitrate_kbps if args.bitrate_kbps is not None else DEFAULT_CAPTURE_BITRATE_KBPS
    nv_preset = DEFAULT_NV_PRESET

    if "x" not in size:
        raise SystemExit("--size must be WIDTHxHEIGHT")
    width, height = [int(x) for x in size.lower().split("x", 1)]

    return width, height, fps, bitrate_kbps, nv_preset


def normalize_encoder_name(raw_encoder: str) -> str:
    requested = str(raw_encoder or "auto").strip().lower()
    if requested in {"auto", "nvenc", "openh264"}:
        return requested
    if requested == "h264":
        return "auto"
    if requested in {"h265", "rawpng", "mjpeg", "jpeg"}:
        sys.stderr.write(f"[warn] encoder '{requested}' not supported by wayland portal h264 helper; falling back to auto" + "\n")
        return "auto"
    sys.stderr.write(f"[warn] unknown encoder '{requested}', falling back to auto" + "\n")
    return "auto"


def main():  # NOSONAR: startup flow intentionally coordinates many setup stages
    args = parse_args()

    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    Gst.init(None)

    width, height, fps, bitrate_kbps, nv_preset = resolve_capture_defaults(args)
    requested_encoder = normalize_encoder_name(args.encoder)
    encoder_name = pick_encoder(requested_encoder)
    framed = args.framed or os.environ.get("WBEAM_FRAMED", "0") == "1"
    if framed:
        print("[wbeam] C3 framed protocol enabled (alignment=au)", flush=True)

    session_bus = dbus.SessionBus()
    portal = session_bus.get_object(PORTAL_BUS, PORTAL_PATH)
    iface = dbus.Interface(portal, SCREENCAST_IFACE)

    print(
        f"[wbeam] size={width}x{height} fps={fps} "
        f"bitrate={bitrate_kbps}kbps encoder={encoder_name} cursor={args.cursor_mode}"
    )
    print("[wbeam] Requesting ScreenCast portal session (you will get KDE share prompt)...")

    session_handle = create_screencast_session(session_bus, iface)
    STATE["session_handle"] = session_handle
    STATE["bus"] = session_bus

    persist_mode = max(0, min(2, int(args.persist_mode)))
    restore_token = load_restore_token(args.restore_token_file) if persist_mode > 0 else ""
    if restore_token:
        print("[wbeam] Attempting source restore via portal token", flush=True)
    else:
        print("[wbeam] Select source in KDE prompt", flush=True)

    try:
        select_sources(session_bus, iface, session_handle, args.cursor_mode, persist_mode, restore_token)
    except RuntimeError:
        if restore_token:
            print("[wbeam] restore token failed; falling back to manual source selection", flush=True)
            select_sources(session_bus, iface, session_handle, args.cursor_mode, persist_mode, "")
        else:
            raise

    print("[wbeam] Starting portal session")
    node_id, new_restore_token = start_session(session_bus, iface, session_handle)
    print(f"[wbeam] Got PipeWire node id: {node_id}")
    if persist_mode > 0 and new_restore_token:
        save_restore_token(args.restore_token_file, new_restore_token)

    fd = open_pipewire_fd(iface, session_handle)
    print(f"[wbeam] Opened PipeWire fd: {fd}")

    pipeline, pipeline_fps_counter = make_pipeline(
        fd,
        node_id,
        width,
        height,
        fps,
        bitrate_kbps,
        args.port,
        args.debug_dir,
        args.debug_fps,
        encoder_name,
        nv_preset,
        framed=framed,
    )
    STATE["pipeline"] = pipeline

    overlay_file = os.getenv("WBEAM_OVERLAY_TEXT_FILE", "").strip()
    if overlay_file:
        overlay_elements = {"MAIN": pipeline.get_by_name("hud_main")}
        if any(v is not None for v in overlay_elements.values()):
            print(f"[wbeam] Overlay text source: {overlay_file}", flush=True)
            overlay_state = {"MAIN": ""}

            def _parse_overlay_sections(raw_text: str) -> dict[str, str]:
                text = raw_text.replace("\r\n", "\n").replace("\r", "\n").strip()
                out = {"MAIN": ""}
                if not text:
                    return out

                section_rx = re.compile(r"^\[(MAIN|TL|TR|BL|BR)\]\s*$", re.IGNORECASE)
                lines = text.split("\n")
                current = None
                buckets = {"MAIN": [], "TL": [], "TR": [], "BL": [], "BR": []}
                section_count = 0
                for line in lines:
                    m = section_rx.match(line.strip())
                    if m:
                        current = m.group(1).upper()
                        section_count += 1
                        continue
                    if current is not None:
                        buckets[current].append(line.rstrip())

                if section_count > 0:
                    # Prefer new MAIN block, fallback to merged legacy quadrants.
                    main = "\n".join(buckets["MAIN"]).strip()
                    if main:
                        out["MAIN"] = main
                        return out
                    merged = []
                    for key in ("TL", "TR", "BL", "BR"):
                        block = "\n".join(buckets[key]).strip()
                        if block:
                            merged.append(block)
                    out["MAIN"] = "\n\n".join(merged).strip()
                    return out

                # Backward compatibility: old 1-block overlay goes to MAIN.
                out["MAIN"] = text
                return out

            def _refresh_overlay_text():  # NOSONAR: GLib callback intentionally returns True
                try:
                    text = Path(overlay_file).read_text(encoding="utf-8", errors="replace")
                except FileNotFoundError:
                    return True
                except Exception as exc:
                    sys.stderr.write(f"[warn] failed to read overlay text {overlay_file}: {exc}\n")
                    return True

                sections = _parse_overlay_sections(text)
                for key, elem in overlay_elements.items():
                    if elem is None:
                        continue
                    new_text = sections.get(key, "")
                    if new_text == overlay_state[key]:
                        continue
                    try:
                        try:
                            elem.set_property("markup", new_text)
                        except Exception:
                            plain_text = re.sub(r"<[^>]+>", "", new_text)
                            elem.set_property("text", plain_text)
                        overlay_state[key] = new_text
                    except Exception as exc:
                        sys.stderr.write(f"[warn] failed to set overlay text ({key}): {exc}\n")
                return True

            _refresh_overlay_text()
            GLib.timeout_add(250, _refresh_overlay_text)

    bus = pipeline.get_bus()
    bus.add_signal_watch()
    bus.connect("message", on_bus_message)

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)

    print(f"[wbeam] Streaming Wayland screencast on tcp://0.0.0.0:{args.port}")
    if args.debug_dir and args.debug_fps > 0:
        print(f"[wbeam] Debug frames: {args.debug_dir} ({args.debug_fps} fps, max 300 files)")

    ret = pipeline.set_state(Gst.State.PLAYING)
    if ret == Gst.StateChangeReturn.FAILURE:
        raise RuntimeError("Failed to start GStreamer pipeline")

    # C3: start framed TCP sender before entering GLib main loop
    if framed:
        stop_event = threading.Event()
        appsink = pipeline.get_by_name("sink")
        t = threading.Thread(
            target=framed_tcp_server_thread,
            args=(appsink, args.port, stop_event, pipeline_fps_counter, args.fps),
            daemon=True,
            name="wbeam-framed-sender",
        )
        t.start()
        STATE["framing_thread"] = t
        STATE["framing_stop"] = stop_event

    main_loop = GLib.MainLoop()
    STATE["main_loop"] = main_loop
    main_loop.run()


if __name__ == "__main__":
    main()
