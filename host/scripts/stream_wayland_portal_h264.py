#!/usr/bin/env python3
import argparse
import os
import random
import signal
import sys

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

PROFILE_DEFAULTS = {
    "lowlatency": {"size": "1920x1080", "fps": 60, "bitrate_kbps": 18000, "nv_preset": "p1"},
    "balanced": {"size": "1920x1080", "fps": 60, "bitrate_kbps": 25000, "nv_preset": "p4"},
    "ultra": {"size": "2560x1440", "fps": 60, "bitrate_kbps": 38000, "nv_preset": "p6"},
}

STATE = {
    "main_loop": None,
    "session_handle": None,
    "pipeline": None,
    "bus": None,
}


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


def select_sources(session_bus, iface, session_handle, cursor_mode):
    token = random.randint(100000, 999999)
    options = build_variant_dict(
        {
            "handle_token": dbus.String(f"wbeam_select_{token}"),
            "types": dbus.UInt32(1),  # monitor
            "multiple": dbus.Boolean(False),
            "cursor_mode": dbus.UInt32(CURSOR_MODE_MAP[cursor_mode]),
        }
    )
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

    return int(streams[0][0])


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
        print(f"[warn] failed to close portal session: {exc}", file=sys.stderr)


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
    if encoder_name == "nvenc":
        enc.set_property("bitrate", int(bitrate_kbps))
        enc.set_property("max-bitrate", int(bitrate_kbps))
        enc.set_property("rc-mode", "cbr")
        enc.set_property("preset", nv_preset)
        enc.set_property("gop-size", max(60, int(fps) * 2))
        enc.set_property("bframes", 0)
        enc.set_property("zerolatency", True)
        enc.set_property("aud", True)
        enc.set_property("repeat-sequence-header", True)
        return

    # openh264enc bitrate is bits/s
    enc.set_property("bitrate", int(bitrate_kbps) * 1000)
    enc.set_property("rate-control", "bitrate")
    enc.set_property("complexity", "high")
    enc.set_property("gop-size", max(60, int(fps) * 2))
    enc.set_property("multi-thread", 0)
    enc.set_property("slice-mode", "n-slices")
    enc.set_property("num-slices", 1)
    enc.set_property("scene-change-detection", False)
    enc.set_property("background-detection", False)
    enc.set_property("qp-min", 8)
    enc.set_property("qp-max", 32)


def make_pipeline(
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
):
    pipeline = Gst.Pipeline.new("wbeam-wayland-pipeline")

    src = Gst.ElementFactory.make("pipewiresrc", "src")
    queue = Gst.ElementFactory.make("queue", "q1")
    convert = Gst.ElementFactory.make("videoconvert", "conv")
    scale = Gst.ElementFactory.make("videoscale", "scale")
    rate = Gst.ElementFactory.make("videorate", "rate")
    caps1 = Gst.ElementFactory.make("capsfilter", "caps1")
    tee = Gst.ElementFactory.make("tee", "tee")

    queue_main = Gst.ElementFactory.make("queue", "qmain")
    enc = Gst.ElementFactory.make("nvh264enc" if encoder_name == "nvenc" else "openh264enc", "enc")
    parse = Gst.ElementFactory.make("h264parse", "parse")
    caps2 = Gst.ElementFactory.make("capsfilter", "caps2")
    sink = Gst.ElementFactory.make("tcpserversink", "sink")

    elements = [src, queue, convert, scale, rate, caps1, tee, queue_main, enc, parse, caps2, sink]
    if any(e is None for e in elements):
        missing = [
            name
            for name, e in zip(
                [
                    "pipewiresrc",
                    "queue",
                    "videoconvert",
                    "videoscale",
                    "videorate",
                    "capsfilter",
                    "tee",
                    "queue",
                    "encoder",
                    "h264parse",
                    "capsfilter",
                    "tcpserversink",
                ],
                elements,
            )
            if e is None
        ]
        raise RuntimeError(f"Missing GStreamer elements: {', '.join(missing)}")

    src.set_property("fd", int(fd))
    src.set_property("path", str(node_id))
    src.set_property("do-timestamp", True)
    src.set_property("keepalive-time", 1000)

    raw_format = "NV12" if encoder_name == "nvenc" else "I420"
    caps1.set_property(
        "caps",
        Gst.Caps.from_string(
            f"video/x-raw,format={raw_format},width={width},height={height},framerate={fps}/1"
        ),
    )

    configure_encoder(enc, encoder_name, bitrate_kbps, fps, nv_preset)

    parse.set_property("disable-passthrough", True)
    parse.set_property("config-interval", 1)
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
        (caps1, tee),
        (tee, queue_main),
        (queue_main, enc),
        (enc, parse),
        (parse, caps2),
        (caps2, sink),
    ]:
        if not a.link(b):
            raise RuntimeError(f"Failed to link {a.get_name()} -> {b.get_name()}")

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

    return pipeline


def on_bus_message(bus, message):
    mtype = message.type
    if mtype == Gst.MessageType.ERROR:
        err, debug = message.parse_error()
        print(f"[gst-error] {err}: {debug}", file=sys.stderr)
        stop()
    elif mtype == Gst.MessageType.EOS:
        print("[gst] EOS")
        stop()


def stop(*_args):
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
    parser.add_argument("--profile", choices=["lowlatency", "balanced", "ultra"], default="balanced")
    parser.add_argument("--port", type=int, default=5000)
    parser.add_argument("--size", default=None)
    parser.add_argument("--fps", type=int, default=None)
    parser.add_argument("--bitrate-kbps", type=int, default=None)
    parser.add_argument("--encoder", choices=["auto", "nvenc", "openh264"], default="auto")
    parser.add_argument("--cursor-mode", choices=["hidden", "embedded", "metadata"], default="hidden")
    parser.add_argument("--debug-dir", default="/tmp/wbeam-frames")
    parser.add_argument("--debug-fps", type=int, default=0)
    return parser.parse_args()


def resolve_profile(args):
    defaults = PROFILE_DEFAULTS[args.profile].copy()

    size = args.size or defaults["size"]
    fps = args.fps if args.fps is not None else defaults["fps"]
    bitrate_kbps = args.bitrate_kbps if args.bitrate_kbps is not None else defaults["bitrate_kbps"]
    nv_preset = defaults["nv_preset"]

    if "x" not in size:
        raise SystemExit("--size must be WIDTHxHEIGHT")
    width, height = [int(x) for x in size.lower().split("x", 1)]

    return width, height, fps, bitrate_kbps, nv_preset


def main():
    args = parse_args()

    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    Gst.init(None)

    width, height, fps, bitrate_kbps, nv_preset = resolve_profile(args)
    encoder_name = pick_encoder(args.encoder)

    session_bus = dbus.SessionBus()
    portal = session_bus.get_object(PORTAL_BUS, PORTAL_PATH)
    iface = dbus.Interface(portal, SCREENCAST_IFACE)

    print(f"[wbeam] profile={args.profile} size={width}x{height} fps={fps} bitrate={bitrate_kbps}kbps encoder={encoder_name} cursor={args.cursor_mode}")
    print("[wbeam] Requesting ScreenCast portal session (you will get KDE share prompt)...")

    session_handle = create_screencast_session(session_bus, iface)
    STATE["session_handle"] = session_handle
    STATE["bus"] = session_bus

    print("[wbeam] Select source in KDE prompt")
    select_sources(session_bus, iface, session_handle, args.cursor_mode)

    print("[wbeam] Starting portal session")
    node_id = start_session(session_bus, iface, session_handle)
    print(f"[wbeam] Got PipeWire node id: {node_id}")

    fd = open_pipewire_fd(iface, session_handle)
    print(f"[wbeam] Opened PipeWire fd: {fd}")

    pipeline = make_pipeline(
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
    )
    STATE["pipeline"] = pipeline

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

    main_loop = GLib.MainLoop()
    STATE["main_loop"] = main_loop
    main_loop.run()


if __name__ == "__main__":
    main()
