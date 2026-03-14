#!/usr/bin/env python3
import argparse
import fcntl
import json
import logging
import os
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field, asdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Any, Dict, Optional, Tuple


STATES = {"IDLE", "STARTING", "STREAMING", "RECONNECTING", "ERROR", "STOPPING"}

PRESETS: Dict[str, Dict[str, Any]] = {
    "default": {
        "profile": "default",
        "encoder": "h264",
        "cursor_mode": "embedded",
        "size": "1280x800",
        "fps": 60,
        "bitrate_kbps": 10000,
        "debug_fps": 0,
    },
}

VALID_ENCODERS = {"h264", "h265", "rawpng", "auto", "nvenc", "openh264"}
VALID_CURSOR_MODES = {"hidden", "embedded", "metadata"}


@dataclass
class Metrics:
    start_count: int = 0
    stop_count: int = 0
    restart_count: int = 0
    reconnects: int = 0
    frame_in: int = 0
    frame_out: int = 0
    drops: int = 0
    bitrate_actual_bps: int = 0
    encode_latency_ms: float = 0.0
    stream_start_time: float = 0.0
    stream_uptime_sec: int = 0


@dataclass
class RuntimeState:
    state: str = "IDLE"
    active_config: Dict[str, Any] = field(default_factory=lambda: PRESETS["default"].copy())
    last_error: str = ""
    host_name: str = socket.gethostname()
    uptime_start: float = field(default_factory=time.time)

    def uptime_sec(self) -> int:
        return int(time.time() - self.uptime_start)


class ConfigError(Exception):
    pass


class StreamProcess:
    def __init__(self, script_path: Path, adb_reverse_script: Path, port: int, logger: logging.Logger):
        self.script_path = script_path
        self.adb_reverse_script = adb_reverse_script
        self.port = port
        self.logger = logger
        self.proc: Optional[subprocess.Popen] = None
        self.stdout_thread: Optional[threading.Thread] = None
        self.stderr_thread: Optional[threading.Thread] = None
        self.last_streaming_line = 0.0
        self._on_streaming = None
        self._on_exit = None

    def set_callbacks(self, on_streaming, on_exit):
        self._on_streaming = on_streaming
        self._on_exit = on_exit

    def _reader(self, pipe, is_err: bool = False):
        try:
            for raw in iter(pipe.readline, b""):
                line = raw.decode("utf-8", errors="replace").rstrip()
                if not line:
                    continue
                if is_err:
                    self.logger.warning("stream: %s", line)
                else:
                    self.logger.info("stream: %s", line)
                if "Streaming Wayland screencast" in line:
                    self.last_streaming_line = time.time()
                    if self._on_streaming:
                        self._on_streaming()
        except Exception:
            self.logger.exception("stream output reader crashed")

    def _ensure_stream_port_available(self):
        probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            probe.bind(("0.0.0.0", self.port))
            return
        except OSError:
            self.logger.warning("stream port %s is busy; trying self-heal", self.port)
        finally:
            probe.close()

        # Best-effort cleanup of stale listeners.
        if shutil.which("fuser"):
            subprocess.run(["fuser", "-k", f"{self.port}/tcp"], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            time.sleep(0.2)

        verify = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            verify.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            verify.bind(("0.0.0.0", self.port))
        except OSError as exc:
            raise RuntimeError(f"stream port {self.port} is busy") from exc
        finally:
            verify.close()

    def is_running(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def start(self, config: Dict[str, Any]):
        if self.is_running():
            raise RuntimeError("stream already running")

        self._ensure_stream_port_available()
        subprocess.run([str(self.adb_reverse_script), str(self.port)], check=False)

        cmd = [
            sys.executable,
            str(self.script_path),
            "--port",
            str(self.port),
            "--encoder",
            str(config["encoder"]),
            "--cursor-mode",
            str(config["cursor_mode"]),
            "--size",
            str(config["size"]),
            "--fps",
            str(config["fps"]),
            "--bitrate-kbps",
            str(config["bitrate_kbps"]),
            "--debug-dir",
            "/tmp/wbeam-frames",
            "--debug-fps",
            str(config["debug_fps"]),
        ]

        self.logger.info("starting stream process: %s", " ".join(cmd))
        self.proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            stdin=subprocess.DEVNULL,
        )

        self.stdout_thread = threading.Thread(target=self._reader, args=(self.proc.stdout, False), daemon=True)
        self.stderr_thread = threading.Thread(target=self._reader, args=(self.proc.stderr, True), daemon=True)
        self.stdout_thread.start()
        self.stderr_thread.start()

        def _watch():
            if self.proc is None:
                return
            code = self.proc.wait()
            self.logger.warning("stream process exited with code=%s", code)
            if self._on_exit:
                self._on_exit(code)

        threading.Thread(target=_watch, daemon=True).start()

    def stop(self):
        if not self.is_running():
            self.proc = None
            return

        proc = self.proc
        self.proc = None

        try:
            proc.terminate()
            proc.wait(timeout=3)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass


class WBeamDaemon:
    def __init__(self, root: Path, port: int, control_port: int, logger: logging.Logger):
        self.root = root
        self.port = port
        self.control_port = control_port
        self.logger = logger

        self.lock = threading.RLock()
        self.runtime = RuntimeState()
        self.metrics = Metrics()
        self.auto_start = True

        stream_script = self.root / "host" / "scripts" / "stream_wayland_portal_h264.py"
        adb_reverse_script = self.root / "host" / "scripts" / "usb_reverse.sh"

        self.stream = StreamProcess(stream_script, adb_reverse_script, port, logger)
        self.stream.set_callbacks(self._on_streaming, self._on_stream_exit)

    def _set_state(self, new_state: str):
        if new_state not in STATES:
            raise ValueError(new_state)
        self.runtime.state = new_state

    def _on_streaming(self):
        with self.lock:
            self._set_state("STREAMING")
            self.runtime.last_error = ""
            self.metrics.stream_start_time = time.time()

    def _on_stream_exit(self, code: int):
        with self.lock:
            if self.runtime.state in {"STOPPING", "IDLE"}:
                self._set_state("IDLE")
                return

            self.runtime.last_error = f"stream exited with code={code}"
            self._set_state("ERROR")
            self.metrics.reconnects += 1
            if self.auto_start:
                self._set_state("RECONNECTING")
                self.metrics.restart_count += 1
                cfg = dict(self.runtime.active_config)
                threading.Thread(target=self._delayed_restart, args=(cfg,), daemon=True).start()

    def _delayed_restart(self, config: Dict[str, Any]):
        time.sleep(1.0)
        with self.lock:
            if self.runtime.state != "RECONNECTING":
                return
        try:
            self.start(config)
        except Exception as exc:
            with self.lock:
                self.runtime.last_error = str(exc)
                self._set_state("ERROR")

    def _update_stream_uptime(self):
        if self.metrics.stream_start_time > 0 and self.runtime.state in {"STREAMING", "RECONNECTING", "ERROR"}:
            self.metrics.stream_uptime_sec = int(time.time() - self.metrics.stream_start_time)
        else:
            self.metrics.stream_uptime_sec = 0

    def validate_config(self, incoming: Dict[str, Any]) -> Dict[str, Any]:
        cfg = dict(PRESETS["default"])
        cfg.update({k: v for k, v in incoming.items() if v is not None and k != "profile"})
        cfg["profile"] = "default"

        if cfg.get("encoder") not in VALID_ENCODERS:
            raise ConfigError("invalid encoder")
        if cfg.get("cursor_mode") not in VALID_CURSOR_MODES:
            raise ConfigError("invalid cursor_mode")

        size = str(cfg.get("size", ""))
        if "x" not in size:
            raise ConfigError("size must be WxH")
        try:
            w, h = [int(x) for x in size.lower().split("x", 1)]
        except Exception as exc:
            raise ConfigError("invalid size") from exc

        w = max(640, min(3840, w))
        h = max(360, min(2160, h))
        w -= w % 2
        h -= h % 2

        cfg["size"] = f"{w}x{h}"
        cfg["fps"] = max(24, min(120, int(cfg.get("fps", 60))))
        cfg["bitrate_kbps"] = max(4000, min(120000, int(cfg.get("bitrate_kbps", 25000))))
        cfg["debug_fps"] = max(0, min(10, int(cfg.get("debug_fps", 0))))

        return cfg

    def start(self, incoming_cfg: Optional[Dict[str, Any]] = None):
        incoming_cfg = incoming_cfg or {}
        with self.lock:
            cfg = self.validate_config(incoming_cfg)
            self.runtime.active_config = cfg
            self._set_state("STARTING")
            self.runtime.last_error = ""

            if self.stream.is_running():
                self.stream.stop()

            self.stream.start(cfg)
            self.metrics.start_count += 1

    def stop(self):
        with self.lock:
            self._set_state("STOPPING")
            self.stream.stop()
            self.metrics.stop_count += 1
            self._set_state("IDLE")

    def apply(self, incoming_cfg: Dict[str, Any]):
        with self.lock:
            cfg = self.validate_config(incoming_cfg)
            self.runtime.active_config = cfg
            running = self.stream.is_running()

        if running:
            with self.lock:
                self.metrics.restart_count += 1
            self.start(cfg)
        return cfg

    def snapshot(self) -> Dict[str, Any]:
        with self.lock:
            self._update_stream_uptime()
            return {
                "state": self.runtime.state,
                "active_config": dict(self.runtime.active_config),
                "host_name": self.runtime.host_name,
                "uptime": self.runtime.uptime_sec(),
                "last_error": self.runtime.last_error,
            }

    def status(self) -> Dict[str, Any]:
        payload = self.snapshot()
        payload["ok"] = True
        return payload

    def health(self) -> Dict[str, Any]:
        payload = self.snapshot()
        payload.update({
            "ok": True,
            "service": "wbeamd",
            "stream_process_alive": self.stream.is_running(),
        })
        return payload

    def presets(self) -> Dict[str, Any]:
        payload = self.snapshot()
        payload.update({
            "ok": True,
            "presets": PRESETS,
            "valid": {
                "encoder": sorted(VALID_ENCODERS),
                "cursor_mode": sorted(VALID_CURSOR_MODES),
            },
        })
        return payload

    def metrics_payload(self) -> Dict[str, Any]:
        payload = self.snapshot()
        payload.update({
            "ok": True,
            "metrics": asdict(self.metrics),
        })
        return payload


class ApiHandler(BaseHTTPRequestHandler):
    daemon_ref: WBeamDaemon = None  # type: ignore

    def _json(self, status_code: int, payload: Dict[str, Any]):
        data = json.dumps(payload).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body_json(self) -> Dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0") or 0)
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        if not raw:
            return {}
        return json.loads(raw.decode("utf-8"))

    def do_GET(self):
        try:
            if self.path == "/status":
                return self._json(200, self.daemon_ref.status())
            if self.path == "/health":
                return self._json(200, self.daemon_ref.health())
            if self.path == "/presets":
                return self._json(200, self.daemon_ref.presets())
            if self.path == "/metrics":
                return self._json(200, self.daemon_ref.metrics_payload())
            self._json(404, {"ok": False, "error": "not_found"})
        except Exception as exc:
            self._json(500, {"ok": False, "error": str(exc)})

    def do_POST(self):
        try:
            if self.path == "/start":
                cfg = self._body_json()
                self.daemon_ref.start(cfg)
                return self._json(200, self.daemon_ref.status())

            if self.path == "/stop":
                self.daemon_ref.stop()
                return self._json(200, self.daemon_ref.status())

            if self.path == "/apply":
                cfg = self._body_json()
                self.daemon_ref.apply(cfg)
                return self._json(200, self.daemon_ref.status())

            self._json(404, {"ok": False, "error": "not_found"})
        except ConfigError as exc:
            self._json(400, {
                "ok": False,
                "error": str(exc),
                **self.daemon_ref.snapshot(),
            })
        except Exception as exc:
            self._json(500, {
                "ok": False,
                "error": str(exc),
                **self.daemon_ref.snapshot(),
            })

    def log_message(self, fmt: str, *args):
        return


def setup_logger(log_dir: Path) -> logging.Logger:
    log_dir.mkdir(parents=True, exist_ok=True)
    logger = logging.getLogger("wbeamd")
    logger.setLevel(logging.INFO)

    if logger.handlers:
        return logger

    formatter = logging.Formatter("%(asctime)s %(levelname)s %(message)s")

    file_handler = RotatingFileHandler(log_dir / "wbeamd.log", maxBytes=5_000_000, backupCount=5)
    file_handler.setFormatter(formatter)
    logger.addHandler(file_handler)

    stream_handler = logging.StreamHandler(sys.stdout)
    stream_handler.setFormatter(formatter)
    logger.addHandler(stream_handler)

    return logger


def acquire_lock(lock_path: Path):
    lock_file = open(lock_path, "w+")
    try:
        fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise SystemExit(f"wbeamd already running (lock: {lock_path})")
    lock_file.write(str(os.getpid()))
    lock_file.flush()
    return lock_file


def main():
    parser = argparse.ArgumentParser(description="WBeam host daemon")
    parser.add_argument("--control-port", type=int, default=5001)
    parser.add_argument("--stream-port", type=int, default=5000)
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[2]))
    parser.add_argument("--lock-file", default="/tmp/wbeamd.lock")
    parser.add_argument("--log-dir", default=None)
    args = parser.parse_args()

    root = Path(args.root).resolve()
    log_dir = Path(args.log_dir) if args.log_dir else (root / "host" / "logs")
    logger = setup_logger(log_dir)

    lock_file = acquire_lock(Path(args.lock_file))

    daemon = WBeamDaemon(root=root, port=args.stream_port, control_port=args.control_port, logger=logger)
    ApiHandler.daemon_ref = daemon

    server = ThreadingHTTPServer(("0.0.0.0", args.control_port), ApiHandler)

    stop_event = threading.Event()

    def _stop(*_):
        if stop_event.is_set():
            return
        stop_event.set()
        logger.info("shutdown requested")
        try:
            daemon.stop()
        except Exception:
            logger.exception("failed during daemon stop")
        server.shutdown()

    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)

    logger.info("wbeamd started control_port=%s stream_port=%s host=%s", args.control_port, args.stream_port, daemon.runtime.host_name)

    try:
        server.serve_forever(poll_interval=0.5)
    finally:
        try:
            daemon.stop()
        except Exception:
            pass
        try:
            lock_file.close()
        except Exception:
            pass


if __name__ == "__main__":
    main()
