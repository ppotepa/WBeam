#!/usr/bin/env python3
"""Main-lane WBeam trainer wizard (interactive TUI)."""

from __future__ import annotations

import argparse
import itertools
import json
import os
import statistics
import subprocess
import sys
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib import error as urlerror
from urllib import parse as urlparse
from urllib import request as urlrequest


ROOT = Path(__file__).resolve().parents[1]
DEVICE_PORTS_FILE = ROOT / ".wbeam_device_ports"
PROFILE_FILE = ROOT / "proto" / "config" / "profiles.json"
DESKTOP_RUNTIME_FILE = (
    ROOT / "src" / "apps" / "desktop-tauri" / "src" / "config" / "trained-profile-runtime.json"
)
LOG_DIR = ROOT / "logs"


@dataclass(frozen=True)
class TrialConfig:
    encoder: str
    size: str
    fps: int
    bitrate_kbps: int
    cursor_mode: str


@dataclass
class TrialResult:
    trial_id: str
    config: TrialConfig
    score: float
    present_fps_mean: float
    recv_fps_mean: float
    decode_fps_mean: float
    e2e_p95_mean_ms: float
    decode_p95_mean_ms: float
    render_p95_mean_ms: float
    drop_rate_per_sec: float
    late_rate_per_sec: float
    queue_depth_mean: float
    sample_count: int
    notes: str


def eprint(msg: str) -> None:
    print(msg, file=sys.stderr)


def run_cmd(cmd: list[str], timeout: float = 8.0) -> str:
    proc = subprocess.run(
        cmd,
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        err = proc.stderr.strip() or proc.stdout.strip() or f"exit={proc.returncode}"
        raise RuntimeError(f"{' '.join(cmd)} failed: {err}")
    return proc.stdout


def adb_serials() -> list[str]:
    out = run_cmd(["adb", "devices"], timeout=8.0)
    serials: list[str] = []
    for line in out.splitlines()[1:]:
        parts = line.strip().split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


def read_device_ports() -> dict[str, int]:
    mapping: dict[str, int] = {}
    if not DEVICE_PORTS_FILE.exists():
        return mapping
    for raw in DEVICE_PORTS_FILE.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        serial, port_raw = parts[0], parts[1]
        if port_raw.isdigit():
            mapping[serial] = int(port_raw)
    return mapping


def infer_stream_port(
    serial: str,
    serials: list[str],
    default_stream_port: int,
    control_port: int,
    port_map: dict[str, int],
) -> int:
    if serial in port_map:
        return port_map[serial]
    idx = serials.index(serial)
    port = default_stream_port + idx + 1
    if port == control_port:
        port += 1
    return port


def detect_device_size(serial: str) -> str | None:
    try:
        out = run_cmd(["adb", "-s", serial, "shell", "wm", "size"], timeout=8.0)
    except Exception:
        return None
    for line in out.splitlines():
        line = line.strip()
        if "Physical size:" in line:
            size = line.split(":", 1)[1].strip()
            if "x" in size:
                return normalize_size(size)
    return None


def normalize_size(raw: str) -> str:
    raw = raw.strip().lower().replace(" ", "")
    if "x" not in raw:
        return raw
    w_raw, h_raw = raw.split("x", 1)
    if not (w_raw.isdigit() and h_raw.isdigit()):
        return raw
    w, h = int(w_raw), int(h_raw)
    if h > w:
        w, h = h, w
    return f"{w}x{h}"


def parse_num_list(raw: str, *, min_value: int = 1) -> list[int]:
    out: list[int] = []
    for part in raw.replace(";", ",").split(","):
        token = part.strip()
        if not token:
            continue
        if not token.isdigit():
            continue
        value = int(token)
        if value >= min_value:
            out.append(value)
    return dedupe_keep_order(out)


def parse_size_list(raw: str) -> list[str]:
    out: list[str] = []
    for part in raw.replace(";", ",").split(","):
        token = normalize_size(part)
        if "x" not in token:
            continue
        w_raw, h_raw = token.split("x", 1)
        if not (w_raw.isdigit() and h_raw.isdigit()):
            continue
        if int(w_raw) < 320 or int(h_raw) < 240:
            continue
        out.append(token)
    return dedupe_keep_order(out)


def dedupe_keep_order(items: list[Any]) -> list[Any]:
    seen: set[Any] = set()
    out: list[Any] = []
    for item in items:
        if item in seen:
            continue
        seen.add(item)
        out.append(item)
    return out


def prompt_text(prompt: str, default: str | None = None) -> str:
    suffix = f" [{default}]" if default is not None else ""
    while True:
        value = input(f"{prompt}{suffix}: ").strip()
        if value:
            return value
        if default is not None:
            return default


def prompt_yes_no(prompt: str, default_yes: bool = True) -> bool:
    default = "Y/n" if default_yes else "y/N"
    while True:
        value = input(f"{prompt} [{default}]: ").strip().lower()
        if not value:
            return default_yes
        if value in {"y", "yes"}:
            return True
        if value in {"n", "no"}:
            return False


def prompt_choice(title: str, options: list[str], default_idx: int = 0) -> int:
    print(title)
    for i, opt in enumerate(options, start=1):
        marker = " (default)" if i - 1 == default_idx else ""
        print(f"  {i}. {opt}{marker}")
    while True:
        raw = input("Select number: ").strip()
        if not raw:
            return default_idx
        if raw.isdigit():
            idx = int(raw) - 1
            if 0 <= idx < len(options):
                return idx
        print("Invalid choice, try again.")


def prompt_int(prompt: str, default: int, min_value: int, max_value: int) -> int:
    while True:
        raw = prompt_text(prompt, str(default))
        if raw.isdigit():
            value = int(raw)
            if min_value <= value <= max_value:
                return value
        print(f"Enter an integer in range {min_value}..{max_value}.")


def session_url(
    endpoint: str,
    *,
    control_port: int,
    serial: str | None,
    stream_port: int | None,
) -> str:
    params: dict[str, str] = {}
    if serial:
        params["serial"] = serial
    if stream_port is not None:
        params["stream_port"] = str(stream_port)
    query = f"?{urlparse.urlencode(params)}" if params else ""
    return f"http://127.0.0.1:{control_port}{endpoint}{query}"


def http_json(
    endpoint: str,
    *,
    control_port: int,
    serial: str | None = None,
    stream_port: int | None = None,
    method: str = "GET",
    payload: dict[str, Any] | None = None,
    timeout: float = 3.0,
) -> dict[str, Any]:
    url = session_url(endpoint, control_port=control_port, serial=serial, stream_port=stream_port)
    data = None
    headers = {"Accept": "application/json"}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urlrequest.Request(url=url, data=data, method=method, headers=headers)
    try:
        with urlrequest.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", errors="replace")
    except urlerror.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {url} -> HTTP {exc.code}: {detail}") from exc
    except urlerror.URLError as exc:
        raise RuntimeError(f"{method} {url} failed: {exc}") from exc
    try:
        parsed = json.loads(body) if body else {}
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"{method} {url} returned invalid JSON: {body[:200]}") from exc
    if isinstance(parsed, dict):
        return parsed
    raise RuntimeError(f"{method} {url} returned non-object JSON")


def as_float(value: Any, default: float = 0.0) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def as_int(value: Any, default: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def mean(values: list[float]) -> float:
    return statistics.fmean(values) if values else 0.0


def collect_metrics_samples(
    *,
    control_port: int,
    serial: str,
    stream_port: int,
    sample_sec: int,
    poll_sec: float,
) -> list[dict[str, Any]]:
    samples: list[dict[str, Any]] = []
    deadline = time.monotonic() + sample_sec
    while time.monotonic() < deadline:
        snap = http_json(
            "/v1/metrics",
            control_port=control_port,
            serial=serial,
            stream_port=stream_port,
            timeout=2.0,
        )
        samples.append(snap)
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        time.sleep(min(poll_sec, remaining))
    return samples


def score_trial(config: TrialConfig, samples: list[dict[str, Any]], sample_sec: int, trial_id: str) -> TrialResult:
    present_vals: list[float] = []
    recv_vals: list[float] = []
    decode_vals: list[float] = []
    e2e_p95_vals: list[float] = []
    decode_p95_vals: list[float] = []
    render_p95_vals: list[float] = []
    queue_vals: list[float] = []
    drops_first = 0
    drops_last = 0
    late_first = 0
    late_last = 0

    for idx, sample in enumerate(samples):
        metrics = sample.get("metrics", {}) if isinstance(sample, dict) else {}
        kpi = metrics.get("kpi", {}) if isinstance(metrics, dict) else {}
        latest = metrics.get("latest_client_metrics", {}) if isinstance(metrics, dict) else {}
        present_vals.append(as_float(kpi.get("present_fps"), 0.0))
        recv_vals.append(as_float(kpi.get("recv_fps"), 0.0))
        decode_vals.append(as_float(kpi.get("decode_fps"), 0.0))
        e2e_p95_vals.append(as_float(kpi.get("e2e_latency_ms_p95"), 0.0))
        decode_p95_vals.append(as_float(kpi.get("decode_time_ms_p95"), 0.0))
        render_p95_vals.append(as_float(kpi.get("render_time_ms_p95"), 0.0))
        queue_vals.append(
            as_float(latest.get("transport_queue_depth"), 0.0)
            + as_float(latest.get("decode_queue_depth"), 0.0)
            + as_float(latest.get("render_queue_depth"), 0.0)
        )
        drops_now = as_int(metrics.get("drops"), 0)
        late_now = as_int(latest.get("too_late_frames"), 0)
        if idx == 0:
            drops_first = drops_now
            late_first = late_now
        drops_last = drops_now
        late_last = late_now

    target = max(1, config.fps)
    present_mean = mean(present_vals)
    recv_mean = mean(recv_vals)
    decode_mean = mean(decode_vals)
    e2e_p95_mean = mean(e2e_p95_vals)
    decode_p95_mean = mean(decode_p95_vals)
    render_p95_mean = mean(render_p95_vals)
    queue_mean = mean(queue_vals)
    observed_sec = max(float(sample_sec), 1.0)
    drop_rate = max(0.0, (drops_last - drops_first) / observed_sec)
    late_rate = max(0.0, (late_last - late_first) / observed_sec)

    present_ratio = min(1.2, max(0.0, present_mean / target))
    recv_ratio = min(1.2, max(0.0, recv_mean / target))
    decode_ratio = min(1.2, max(0.0, decode_mean / target))
    latency_bonus = max(0.0, 1.0 - (e2e_p95_mean / 180.0))
    base_score = (
        present_ratio * 55.0
        + recv_ratio * 18.0
        + decode_ratio * 12.0
        + latency_bonus * 15.0
    )
    penalty = (
        drop_rate * 8.0
        + late_rate * 5.0
        + max(0.0, decode_p95_mean - 12.0) * 0.9
        + max(0.0, render_p95_mean - 8.0) * 0.6
        + queue_mean * 1.6
    )
    if present_mean < target * 0.70:
        penalty += 18.0
    if recv_mean < target * 0.70:
        penalty += 8.0

    score = base_score - penalty
    notes = "ok"
    if not samples:
        notes = "no_samples"
    elif present_mean <= 1.0 and recv_mean <= 1.0:
        notes = "stream_unstable"

    return TrialResult(
        trial_id=trial_id,
        config=config,
        score=score,
        present_fps_mean=present_mean,
        recv_fps_mean=recv_mean,
        decode_fps_mean=decode_mean,
        e2e_p95_mean_ms=e2e_p95_mean,
        decode_p95_mean_ms=decode_p95_mean,
        render_p95_mean_ms=render_p95_mean,
        drop_rate_per_sec=drop_rate,
        late_rate_per_sec=late_rate,
        queue_depth_mean=queue_mean,
        sample_count=len(samples),
        notes=notes,
    )


def build_trial_space(
    *,
    mode: str,
    current: dict[str, Any],
    device_size: str | None,
) -> tuple[list[str], list[str], list[int], list[int], list[str]]:
    current_size = normalize_size(str(current.get("size", "1280x720")))
    current_fps = max(30, as_int(current.get("fps"), 60))
    current_bitrate = max(2000, as_int(current.get("bitrate_kbps"), 10000))
    current_encoder = str(current.get("encoder", "h264")).strip() or "h264"
    cursor_mode = str(current.get("cursor_mode", "embedded")).strip() or "embedded"

    if mode == "quality":
        # Default quality mode is intentionally biased towards max fidelity:
        # native landscape resolution + high bitrate ladder (up to 200 Mbps).
        encoders = ["h265", "h264", current_encoder]
        sizes = [device_size or current_size, current_size]
        fps_values = [90, 72, 60, current_fps]
        bitrate_values = [200000, 160000, 120000, 90000, 60000, 40000, current_bitrate]
    elif mode == "latency":
        encoders = [current_encoder, "h264"]
        sizes = [current_size, "1280x720", "1280x800", "1600x900"]
        fps_values = [current_fps, 60, 72]
        bitrate_values = [current_bitrate, 6000, 8000, 10000, 12000, 16000]
    else:
        encoders = [current_encoder, "h264", "h265"]
        sizes = [device_size or current_size, current_size, "1280x720", "1280x800", "1920x1080"]
        fps_values = [current_fps, 45, 60, 72]
        bitrate_values = [current_bitrate, 8000, 12000, 16000, 24000, 32000]

    encoders = [e for e in dedupe_keep_order(encoders) if e in {"h264", "h265", "rawpng"}]
    sizes = [s for s in dedupe_keep_order(sizes) if "x" in s]
    fps_values = [v for v in dedupe_keep_order([max(24, v) for v in fps_values]) if v <= 240]
    bitrate_values = [v for v in dedupe_keep_order([max(1000, v) for v in bitrate_values]) if v <= 400000]
    cursor_values = [cursor_mode] if cursor_mode in {"hidden", "embedded", "metadata"} else ["embedded"]
    return encoders, sizes, fps_values, bitrate_values, cursor_values


def _size_pixels(size: str) -> int:
    if "x" not in size:
        return 0
    w_raw, h_raw = size.split("x", 1)
    if not (w_raw.isdigit() and h_raw.isdigit()):
        return 0
    return int(w_raw) * int(h_raw)


def quality_priority_key(config: TrialConfig) -> tuple[int, int, int, int]:
    encoder_score = 2 if config.encoder == "h265" else (1 if config.encoder == "h264" else 0)
    return (
        config.bitrate_kbps,
        _size_pixels(config.size),
        config.fps,
        encoder_score,
    )


def patch_payload(config: TrialConfig) -> dict[str, Any]:
    return {
        "profile": "baseline",
        "encoder": config.encoder,
        "cursor_mode": config.cursor_mode,
        "size": config.size,
        "fps": config.fps,
        "bitrate_kbps": config.bitrate_kbps,
    }


def write_profile_baseline(path: Path, best: TrialResult) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    existing: dict[str, Any] = {}
    if path.exists():
        try:
            existing = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            existing = {}

    version = as_int(existing.get("version"), 1)
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    gop = max(1, best.config.fps // 2)
    doc = {
        "version": version,
        "profiles": {
            "baseline": {
                "description": "Auto-generated by main-lane trainer wizard.",
                "origin": {
                    "generated_at": now,
                    "mode": "main-train-wizard",
                    "score": round(best.score, 4),
                    "trial": best.trial_id,
                    "present_fps_mean": round(best.present_fps_mean, 3),
                    "recv_fps_mean": round(best.recv_fps_mean, 3),
                    "decode_fps_mean": round(best.decode_fps_mean, 3),
                    "e2e_p95_mean_ms": round(best.e2e_p95_mean_ms, 3),
                    "drop_rate_per_sec": round(best.drop_rate_per_sec, 5),
                    "late_rate_per_sec": round(best.late_rate_per_sec, 5),
                    "samples": best.sample_count,
                },
                "values": {
                    "PROTO_CAPTURE_SIZE": best.config.size,
                    "PROTO_CAPTURE_FPS": best.config.fps,
                    "PROTO_CAPTURE_BITRATE_KBPS": best.config.bitrate_kbps,
                    "PROTO_H264_REORDER": 0,
                    "PROTO_CURSOR_MODE": best.config.cursor_mode,
                    "PROTO_PORTAL_PERSIST_MODE": 2,
                },
                "quality": {
                    "PROTO_CAPTURE_BITRATE_KBPS": best.config.bitrate_kbps,
                    "WBEAM_H264_GOP": gop,
                    "WBEAM_APPSINK_MAX_BUFFERS": 1,
                },
                "latency": {
                    "WBEAM_H264_GOP": gop,
                    "WBEAM_VIDEORATE_DROP_ONLY": 0,
                    "WBEAM_FRAMED_DUPLICATE_STALE": 0,
                    "WBEAM_FRAMED_STALE_START_MS": 900,
                    "WBEAM_FRAMED_STALE_DUP_FPS": 2,
                    "WBEAM_PIPEWIRE_KEEPALIVE_MS": 12,
                    "WBEAM_PIPEWIRE_ALWAYS_COPY": 1,
                    "WBEAM_FRAMED_PULL_TIMEOUT_MS": 20,
                    "WBEAM_QUEUE_MAX_BUFFERS": 1,
                    "WBEAM_QUEUE_MAX_TIME_MS": 8,
                    "WBEAM_APPSINK_MAX_BUFFERS": 1,
                    "PROTO_ADB_WRITE_TIMEOUT_MS": 40,
                    "PROTO_H264_SOURCE_READ_TIMEOUT_MS": 800,
                },
            }
        },
    }
    path.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")


def write_desktop_runtime_defaults(path: Path, best: TrialResult) -> None:
    write_desktop_runtime_defaults_explicit(path, best.config.encoder, best.config.cursor_mode)


def write_desktop_runtime_defaults_explicit(path: Path, encoder: str, cursor_mode: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    data: dict[str, Any] = {}
    if path.exists():
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            data = {}
    defaults = data.get("defaultsByProfileId")
    if not isinstance(defaults, dict):
        defaults = {}
    baseline = defaults.get("baseline")
    if not isinstance(baseline, dict):
        baseline = {}
    baseline["encoder"] = encoder
    baseline["cursorMode"] = cursor_mode
    defaults["baseline"] = baseline
    data["defaultsByProfileId"] = defaults
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def write_run_log(data: dict[str, Any]) -> Path:
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    out = LOG_DIR / f"{stamp}.train-wizard.json"
    out.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    return out


def run_proto_autotune_engine(
    *,
    serial: str,
    detected_size: str | None,
    current_cfg: dict[str, Any],
    trials: int,
    warmup_sec: int,
    sample_sec: int,
    overlay: bool,
) -> int:
    proto_dir = ROOT / "proto"
    base_template_path = proto_dir / "config" / "proto.json"
    best_out_rel = "config/autotune-best.json"
    profiles_out_rel = "config/profiles.json"
    if not base_template_path.exists():
        eprint(f"[error] missing proto base config: {base_template_path}")
        return 1

    try:
        base_cfg = json.loads(base_template_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        eprint(f"[error] invalid JSON in {base_template_path}: {exc}")
        return 1
    if not isinstance(base_cfg, dict):
        eprint(f"[error] base config root is not an object: {base_template_path}")
        return 1

    target_size = detected_size or normalize_size(str(current_cfg.get("size", "1280x720")))
    target_fps = max(30, as_int(current_cfg.get("fps"), 60))
    target_bitrate = max(10_000, as_int(current_cfg.get("bitrate_kbps"), 10_000))
    restore_token = f"/tmp/proto-portal-restore-token-{serial}"

    base_cfg["SERIAL"] = serial
    base_cfg["PROTO_CAPTURE_SIZE"] = target_size
    base_cfg["PROTO_CAPTURE_FPS"] = target_fps
    base_cfg["PROTO_CAPTURE_BITRATE_KBPS"] = max(target_bitrate, 90_000)
    base_cfg["PROTO_PORTAL_PERSIST_MODE"] = 2
    base_cfg["PROTO_PORTAL_RESTORE_TOKEN_FILE"] = restore_token

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    temp_base = Path("/tmp") / f"wbeam-train-proto-{serial}-{stamp}.json"
    temp_base.write_text(json.dumps(base_cfg, indent=2) + "\n", encoding="utf-8")

    population = max(4, min(32, trials))
    generations = 1 if population <= 8 else 2
    elite = max(2, min(6, population // 3))
    min_samples = max(8, sample_sec // 2)
    gate_sender = max(20.0, target_fps * 0.70)
    gate_pipe = max(20.0, target_fps * 0.78)

    fps_values = dedupe_keep_order([target_fps, 60, 72, 90, 120])
    fps_values = [v for v in fps_values if 24 <= v <= 240]
    bitrate_values = dedupe_keep_order(
        [target_bitrate, 40_000, 60_000, 90_000, 120_000, 150_000, 200_000]
    )
    bitrate_values = [v for v in bitrate_values if 1_000 <= v <= 400_000]

    cmd = [
        "python3",
        str(proto_dir / "autotune.py"),
        "--base-config",
        str(temp_base),
        "--host-only",
        "--generations",
        str(generations),
        "--population",
        str(population),
        "--elite-count",
        str(elite),
        "--mutation-rate",
        "0.34",
        "--warmup-secs",
        str(warmup_sec),
        "--sample-secs",
        str(sample_sec),
        "--startup-timeout-secs",
        "240",
        "--min-samples",
        str(min_samples),
        "--gate-min-sender-p50",
        f"{gate_sender:.1f}",
        "--gate-min-pipe-p50",
        f"{gate_pipe:.1f}",
        "--gate-max-timeout-mean",
        "25",
        "--require-portal-metrics",
        "--reuse-device",
        "--single-portal-consent",
        "--fast-mode",
        "--fps",
        str(target_fps),
        "--fps-values",
        ",".join(str(v) for v in fps_values),
        "--bitrate-values",
        ",".join(str(v) for v in bitrate_values),
        "--profile-name",
        "baseline",
        "--export-profiles",
        "--profiles-out",
        profiles_out_rel,
        "--best-config-out",
        best_out_rel,
        "--results",
        f"autotune-results-main-{stamp}.json",
    ]
    cmd.append("--overlay" if overlay else "--no-overlay")

    print("")
    print("[proto-engine] running legacy autotune core:")
    print("  " + " ".join(cmd))
    print("  note: this mode reuses portal consent and shows live HUD on streamed screen.")
    print("")

    proc = subprocess.run(cmd, cwd=ROOT, text=True, check=False)
    if proc.returncode != 0:
        eprint(f"[error] proto autotune failed (exit={proc.returncode})")
        return proc.returncode

    best_cfg_path = proto_dir / best_out_rel
    if not best_cfg_path.exists():
        eprint(f"[error] expected best config not found: {best_cfg_path}")
        return 1

    try:
        best_cfg = json.loads(best_cfg_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        eprint(f"[error] invalid best config JSON: {best_cfg_path} ({exc})")
        return 1

    encoder = "h264" if as_int(best_cfg.get("PROTO_H264"), 1) == 1 else "h265"
    cursor = str(best_cfg.get("PROTO_CURSOR_MODE", "embedded")).strip() or "embedded"
    write_desktop_runtime_defaults_explicit(DESKTOP_RUNTIME_FILE, encoder, cursor)
    print(f"[proto-engine] updated desktop runtime defaults: {DESKTOP_RUNTIME_FILE}")
    print(f"[proto-engine] profiles: {proto_dir / profiles_out_rel}")
    print(f"[proto-engine] best config: {best_cfg_path}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="WBeam main-lane trainer wizard.")
    parser.add_argument(
        "--engine",
        choices=["proto", "live_api"],
        default="proto",
        help="Training engine: proto=legacy dynamic autotune with on-screen HUD; live_api=current direct daemon loop.",
    )
    parser.add_argument("--control-port", type=int, default=int(os.environ.get("WBEAM_CONTROL_PORT", "5001")))
    parser.add_argument("--stream-port", type=int, default=None, help="Override stream port for selected serial.")
    parser.add_argument("--serial", default=None, help="Target ADB serial (interactive if omitted).")
    parser.add_argument("--mode", choices=["balanced", "quality", "latency", "custom"], default="quality")
    parser.add_argument("--trials", type=int, default=None)
    parser.add_argument("--warmup-sec", type=int, default=4)
    parser.add_argument("--sample-sec", type=int, default=12)
    parser.add_argument("--poll-sec", type=float, default=1.0)
    parser.add_argument(
        "--overlay",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Enable on-screen tuning HUD (proto engine).",
    )
    args = parser.parse_args()

    print("== WBeam Main Trainer Wizard ==")
    print(f"root: {ROOT}")
    print(f"engine: {args.engine}")
    print(f"control API: http://127.0.0.1:{args.control_port}")

    try:
        serials = adb_serials()
    except Exception as exc:
        eprint(f"[error] adb devices failed: {exc}")
        return 1
    if not serials:
        eprint("[error] no ADB devices connected.")
        return 1

    if args.serial:
        if args.serial not in serials:
            eprint(f"[error] serial {args.serial} is not connected.")
            return 1
        serial = args.serial
    else:
        idx = prompt_choice("Select target device:", serials, default_idx=0)
        serial = serials[idx]

    device_size = detect_device_size(serial)
    if device_size:
        print(f"detected device size: {device_size}")
    else:
        print("detected device size: <unknown>")

    port_map = read_device_ports()
    stream_port = args.stream_port or infer_stream_port(
        serial,
        serials,
        default_stream_port=5000,
        control_port=args.control_port,
        port_map=port_map,
    )
    print(f"target: serial={serial} stream_port={stream_port}")

    current_cfg: dict[str, Any] = {
        "profile": "baseline",
        "encoder": "h264",
        "cursor_mode": "embedded",
        "size": device_size or "1280x720",
        "fps": 60,
        "bitrate_kbps": 10000,
    }
    state = "unknown"
    daemon_reachable = False
    try:
        health = http_json("/v1/health", control_port=args.control_port, timeout=1.6)
        daemon_reachable = True
        print(
            "daemon: ok="
            f"{health.get('ok')} state={health.get('state')} build={health.get('build_revision', '?')}"
        )
    except Exception as exc:
        print(f"daemon: unreachable ({exc})")

    if daemon_reachable:
        status = http_json(
            "/v1/status",
            control_port=args.control_port,
            serial=serial,
            stream_port=stream_port,
        )
        status_cfg = status.get("active_config", {})
        if isinstance(status_cfg, dict):
            current_cfg.update(status_cfg)
        state = str(status.get("state", "unknown"))
        print(f"session state: {state}")
        print(f"current config: {json.dumps(current_cfg, ensure_ascii=True)}")

    warmup_sec = max(1, args.warmup_sec)
    sample_sec = max(4, args.sample_sec)
    requested_trials = args.trials if args.trials is not None else prompt_int(
        "How many trials to run",
        default=18,
        min_value=1,
        max_value=64,
    )

    if args.engine == "proto":
        return run_proto_autotune_engine(
            serial=serial,
            detected_size=device_size,
            current_cfg=current_cfg,
            trials=requested_trials,
            warmup_sec=warmup_sec,
            sample_sec=sample_sec,
            overlay=bool(args.overlay),
        )

    if not daemon_reachable:
        eprint("[error] live_api engine requires daemon API to be reachable.")
        return 1

    if state not in {"running", "starting"}:
        if prompt_yes_no("Session is not running. Try to start stream now?", default_yes=True):
            start_resp = http_json(
                "/v1/start",
                control_port=args.control_port,
                serial=serial,
                stream_port=stream_port,
                method="POST",
                payload={},
                timeout=5.0,
            )
            print(f"start result: state={start_resp.get('state')} ok={start_resp.get('ok')}")
            time.sleep(2.0)
        else:
            eprint("[error] live_api engine needs an active stream session.")
            return 1

    mode = args.mode

    encoders, sizes, fps_values, bitrate_values, cursor_values = build_trial_space(
        mode=mode,
        current=current_cfg,
        device_size=device_size,
    )

    if mode == "custom":
        encoders_raw = prompt_text("Encoders csv (h264,h265,rawpng)", ",".join(encoders))
        encoders = dedupe_keep_order(
            [x.strip() for x in encoders_raw.split(",") if x.strip() in {"h264", "h265", "rawpng"}]
        )
        sizes = parse_size_list(prompt_text("Sizes csv (e.g. 1280x800,1920x1080)", ",".join(sizes)))
        fps_values = parse_num_list(prompt_text("FPS values csv", ",".join(str(v) for v in fps_values)))
        bitrate_values = parse_num_list(
            prompt_text("Bitrate values kbps csv", ",".join(str(v) for v in bitrate_values)),
            min_value=1000,
        )

    if not encoders or not sizes or not fps_values or not bitrate_values:
        eprint("[error] empty search space after parsing parameters.")
        return 1

    combos: list[TrialConfig] = [
        TrialConfig(encoder=e, size=s, fps=f, bitrate_kbps=b, cursor_mode=c)
        for (e, s, f, b, c) in itertools.product(encoders, sizes, fps_values, bitrate_values, cursor_values)
    ]
    combos = dedupe_keep_order(combos)
    if mode == "quality":
        combos = sorted(combos, key=quality_priority_key, reverse=True)

    requested_trials = max(1, min(requested_trials, len(combos)))
    trials = combos[:requested_trials]
    print(
        f"trial space={len(combos)} running={len(trials)} "
        f"(warmup={warmup_sec}s sample={sample_sec}s poll={args.poll_sec:.2f}s)"
    )

    results: list[TrialResult] = []
    started_at = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    for idx, cfg in enumerate(trials, start=1):
        trial_id = f"t{idx:02d}"
        print(
            f"\n[{trial_id}] apply encoder={cfg.encoder} size={cfg.size} "
            f"fps={cfg.fps} bitrate={cfg.bitrate_kbps}"
        )
        try:
            http_json(
                "/v1/apply",
                control_port=args.control_port,
                serial=serial,
                stream_port=stream_port,
                method="POST",
                payload=patch_payload(cfg),
                timeout=3.0,
            )
        except Exception as exc:
            print(f"[{trial_id}] apply failed: {exc}")
            results.append(
                TrialResult(
                    trial_id=trial_id,
                    config=cfg,
                    score=-999.0,
                    present_fps_mean=0.0,
                    recv_fps_mean=0.0,
                    decode_fps_mean=0.0,
                    e2e_p95_mean_ms=0.0,
                    decode_p95_mean_ms=0.0,
                    render_p95_mean_ms=0.0,
                    drop_rate_per_sec=0.0,
                    late_rate_per_sec=0.0,
                    queue_depth_mean=0.0,
                    sample_count=0,
                    notes="apply_failed",
                )
            )
            continue

        if warmup_sec > 0:
            print(f"[{trial_id}] warmup {warmup_sec}s...")
            time.sleep(warmup_sec)

        print(f"[{trial_id}] sampling {sample_sec}s...")
        try:
            samples = collect_metrics_samples(
                control_port=args.control_port,
                serial=serial,
                stream_port=stream_port,
                sample_sec=sample_sec,
                poll_sec=max(0.3, args.poll_sec),
            )
            result = score_trial(cfg, samples, sample_sec, trial_id)
            results.append(result)
            print(
                f"[{trial_id}] score={result.score:.2f} present={result.present_fps_mean:.1f} "
                f"recv={result.recv_fps_mean:.1f} e2e95={result.e2e_p95_mean_ms:.1f}ms "
                f"drops/s={result.drop_rate_per_sec:.3f}"
            )
        except Exception as exc:
            print(f"[{trial_id}] sample failed: {exc}")
            results.append(
                TrialResult(
                    trial_id=trial_id,
                    config=cfg,
                    score=-999.0,
                    present_fps_mean=0.0,
                    recv_fps_mean=0.0,
                    decode_fps_mean=0.0,
                    e2e_p95_mean_ms=0.0,
                    decode_p95_mean_ms=0.0,
                    render_p95_mean_ms=0.0,
                    drop_rate_per_sec=0.0,
                    late_rate_per_sec=0.0,
                    queue_depth_mean=0.0,
                    sample_count=0,
                    notes="sample_failed",
                )
            )

    if not results:
        eprint("[error] no trial results produced.")
        return 1

    ranked = sorted(results, key=lambda item: item.score, reverse=True)
    best = ranked[0]
    print("\nTop results:")
    for rank, item in enumerate(ranked[:5], start=1):
        print(
            f"  {rank}. {item.trial_id} score={item.score:.2f} "
            f"{item.config.encoder} {item.config.size} {item.config.fps}fps "
            f"{item.config.bitrate_kbps}kbps note={item.notes}"
        )

    print(
        f"\nBEST: {best.trial_id} score={best.score:.2f} "
        f"{best.config.encoder} {best.config.size} {best.config.fps}fps {best.config.bitrate_kbps}kbps"
    )
    if prompt_yes_no("Apply best config to current running session now?", default_yes=True):
        http_json(
            "/v1/apply",
            control_port=args.control_port,
            serial=serial,
            stream_port=stream_port,
            method="POST",
            payload=patch_payload(best.config),
            timeout=3.0,
        )
        print("Applied best config.")

    exported = False
    if prompt_yes_no("Export winner as baseline profile for main app path?", default_yes=True):
        write_profile_baseline(PROFILE_FILE, best)
        write_desktop_runtime_defaults(DESKTOP_RUNTIME_FILE, best)
        exported = True
        print(f"Updated {PROFILE_FILE}")
        print(f"Updated {DESKTOP_RUNTIME_FILE}")

    run_log = {
        "started_at": started_at,
        "finished_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "control_port": args.control_port,
        "serial": serial,
        "stream_port": stream_port,
        "mode": mode,
        "warmup_sec": warmup_sec,
        "sample_sec": sample_sec,
        "poll_sec": args.poll_sec,
        "trial_count": len(trials),
        "exported_baseline": exported,
        "best": {
            "trial_id": best.trial_id,
            "score": best.score,
            "config": asdict(best.config),
            "notes": best.notes,
        },
        "results": [
            {
                "trial_id": item.trial_id,
                "score": item.score,
                "config": asdict(item.config),
                "present_fps_mean": item.present_fps_mean,
                "recv_fps_mean": item.recv_fps_mean,
                "decode_fps_mean": item.decode_fps_mean,
                "e2e_p95_mean_ms": item.e2e_p95_mean_ms,
                "drop_rate_per_sec": item.drop_rate_per_sec,
                "late_rate_per_sec": item.late_rate_per_sec,
                "queue_depth_mean": item.queue_depth_mean,
                "sample_count": item.sample_count,
                "notes": item.notes,
            }
            for item in ranked
        ],
    }
    log_path = write_run_log(run_log)
    print(f"Run log: {log_path}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        eprint("\nInterrupted.")
        raise SystemExit(130)
