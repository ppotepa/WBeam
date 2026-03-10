#!/usr/bin/env python3
"""Main-lane WBeam trainer wizard (interactive TUI)."""

from __future__ import annotations

import argparse
import html
import itertools
import json
import os
import re
import statistics
import subprocess
import sys
import tempfile
import time
from collections.abc import Callable
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib import error as urlerror
from urllib import parse as urlparse
from urllib import request as urlrequest


ROOT = Path(__file__).resolve().parents[3]
DEVICE_PORTS_FILE = ROOT / ".wbeam_device_ports"
TRAINING_DIR = ROOT / "config" / "training"
PROFILE_FILE = TRAINING_DIR / "profiles.json"
PROFILE_OUTPUT_DIR = TRAINING_DIR / "profiles"
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
    bitrate_mbps_mean: float
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


def sanitize_profile_name(raw: str) -> str:
    token = raw.strip().replace(" ", "_")
    token = re.sub(r"[^A-Za-z0-9._-]+", "_", token)
    token = re.sub(r"_+", "_", token).strip("._-")
    return token or "profile"


def session_suffix(serial: str) -> str:
    token = re.sub(r"[^A-Za-z0-9_-]+", "_", serial.strip())
    token = re.sub(r"_+", "_", token).strip("_")
    return token or "default"


def trainer_overlay_path(serial: str, stream_port: int) -> Path:
    return Path(f"/tmp/wbeam-trainer-overlay-{session_suffix(serial)}-{stream_port}.txt")


def trainer_active_marker_path(serial: str, stream_port: int) -> Path:
    return Path(f"/tmp/wbeam-trainer-active-{session_suffix(serial)}-{stream_port}.flag")


def unlink_if_exists(path: Path) -> None:
    try:
        path.unlink()
    except FileNotFoundError:
        return
    except Exception:
        return


def bar_line(value: float, max_value: float, width: int = 24) -> str:
    if max_value <= 0:
        max_value = 1.0
    ratio = max(0.0, min(1.0, value / max_value))
    fill = int(round(ratio * width))
    fill = max(0, min(width, fill))
    return f"[{'#' * fill}{'-' * (width - fill)}] {value:.1f}/{max_value:.1f}"


def fmt_mbps_from_kbps(kbps: int | float) -> str:
    mbps = max(0.0, float(kbps) / 1000.0)
    return f"{mbps:.1f} Mbps"


def severity_tag(value: float, warn_at: float, risk_at: float, invert: bool = False) -> str:
    v = float(value)
    if invert:
        if v <= risk_at:
            return "RISK"
        if v <= warn_at:
            return "WARN"
        return "OK"
    if v >= risk_at:
        return "RISK"
    if v >= warn_at:
        return "WARN"
    return "OK"


def severity_markup(tag: str) -> str:
    tone = (tag or "").upper()
    if tone == "OK":
        return '<span foreground="#6EE7B7">OK</span>'
    if tone == "WARN":
        return '<span foreground="#FBBF24">WARN</span>'
    if tone == "RISK":
        return '<span foreground="#F87171">RISK</span>'
    return html.escape(tone or "-")


def strip_markup(text: str) -> str:
    return re.sub(r"<[^>]+>", "", text or "")


def visible_len(text: str) -> int:
    return len(strip_markup(text))


def note_severity(note: str) -> str:
    token = (note or "").strip().lower()
    if token in {"ok", "stable"}:
        return "OK"
    if token in {"no_samples", "stream_unstable", "gate_failed", "sample_failed", "apply_failed"}:
        return "RISK"
    return "WARN"


def kv_line(left: str, right: str, width: int = 86) -> str:
    l = left.strip()
    r = right.strip()
    gap = max(2, width - visible_len(l) - visible_len(r))
    return f"{l}{' ' * gap}{r}"


def box_line(text: str, width: int = 86) -> str:
    payload = text
    max_vis = width - 2
    vis = visible_len(payload)
    if vis > max_vis:
        plain = strip_markup(payload)
        payload = plain[:max_vis]
        vis = len(payload)
    return f"|{payload}{' ' * max(0, max_vis - vis)}|"


def box_sep(width: int = 86) -> str:
    return "+" + ("-" * (width - 2)) + "+"


def spark_ascii(values: list[float], width: int = 24, chars: str = " .:-=+*#%@") -> str:
    if not values:
        return "." * max(6, width)
    if width <= 0:
        width = len(values)
    tail = values[-width:]
    lo = min(tail)
    hi = max(tail)
    span = hi - lo
    if span <= 1e-9:
        return "=" * len(tail)
    out: list[str] = []
    for item in tail:
        ratio = max(0.0, min(1.0, (item - lo) / span))
        idx = int(round(ratio * (len(chars) - 1)))
        out.append(chars[idx])
    return "".join(out)


def trend_line(values: list[float], width: int = 24) -> str:
    return spark_ascii(values, width=width, chars=" .-:=+*#%@")


def trend_bars(values: list[float], width: int = 24) -> str:
    return spark_ascii(values, width=width, chars=" ▁▂▃▄▅▆▇█")


def trend_render(values: list[float], mode: str, width: int = 24) -> str:
    if mode == "line":
        return trend_line(values, width=width)
    return trend_bars(values, width=width)


def write_overlay_snapshot(
    path: Path,
    *,
    run_id: str,
    profile_name: str,
    trial_id: str,
    trial_index: int,
    trial_total: int,
    generation_index: int,
    generation_total: int,
    cfg: TrialConfig | None = None,
    result: TrialResult | None = None,
    history: list[TrialResult] | None = None,
    note: str = "",
    chart_mode: str = "bars",
    layout_mode: str = "wide",
) -> None:
    recent = history or []
    valid_scores = [item.score for item in recent if item.score > -900.0]
    valid_present = [item.present_fps_mean for item in recent if item.present_fps_mean > 0.0]
    valid_drop = [item.drop_rate_per_sec * 100.0 for item in recent if item.sample_count > 0]
    valid_bitrate = [item.bitrate_mbps_mean for item in recent if item.bitrate_mbps_mean > 0.0]
    best_recent = max(valid_scores) if valid_scores else 0.0
    best_trial = ""
    if recent:
        ranked = sorted((item for item in recent if item.score > -900.0), key=lambda x: x.score, reverse=True)
        if ranked:
            best_trial = ranked[0].trial_id

    target_fps = float(cfg.fps if cfg is not None else 60)
    content_w = 84 if layout_mode == "compact" else 108
    frame_w = content_w + 2
    lines: list[str] = ["[MAIN]"]
    lines.append(box_sep(width=frame_w))
    lines.append(box_line(kv_line("WBEAM TRAINER HUD", f"RUN {run_id}", width=content_w), width=frame_w))
    lines.append(box_line(kv_line(f"PROFILE {profile_name}", f"GEN {generation_index}/{generation_total}", width=content_w), width=frame_w))
    lines.append(box_line(kv_line(f"TRIAL {trial_id} [{trial_index}/{trial_total}]", f"NOTE {note or 'running'}", width=content_w), width=frame_w))
    lines.append(
        box_line(
            kv_line(
                f"LEGEND {severity_markup('OK')} stable",
                f"{severity_markup('WARN')} watch  {severity_markup('RISK')} critical",
                width=content_w,
            ),
            width=frame_w,
        )
    )
    lines.append(box_sep(width=frame_w))
    if cfg is not None:
        lines.append(
            box_line(
                kv_line(
                    f"CODEC {cfg.encoder.upper()} | SIZE {cfg.size} | FPS {cfg.fps}",
                    f"TARGET {fmt_mbps_from_kbps(cfg.bitrate_kbps)}",
                    width=content_w,
                ),
                width=frame_w,
            )
        )
        lines.append(
            box_line(
                kv_line(f"CURSOR {cfg.cursor_mode}", f"CHART {chart_mode.upper()} | LAYOUT {layout_mode.upper()}", width=content_w),
                width=frame_w,
            )
        )
    else:
        lines.append(box_line("STREAM CONFIG pending...", width=frame_w))
    if best_trial:
        lines.append(box_line(kv_line(f"BEST {best_trial}", f"SCORE {best_recent:.2f}", width=content_w), width=frame_w))
    lines.append(box_sep(width=frame_w))
    if result is not None:
        fps_state = severity_tag(result.present_fps_mean, target_fps * 0.75, target_fps * 0.55, invert=True)
        lat_state = severity_tag(result.e2e_p95_mean_ms, 70.0, 120.0)
        drop_state = severity_tag(result.drop_rate_per_sec, 0.06, 0.20)
        queue_state = severity_tag(result.queue_depth_mean, 0.80, 1.60)
        late_state = severity_tag(result.late_rate_per_sec, 0.30, 1.00)
        mbps_state = severity_tag(
            result.bitrate_mbps_mean,
            max(1.0, (float(cfg.bitrate_kbps) / 1000.0) * 0.55) if cfg is not None else 6.0,
            max(1.0, (float(cfg.bitrate_kbps) / 1000.0) * 0.35) if cfg is not None else 3.5,
            invert=True,
        )
        lines.extend(
            [
                box_line(kv_line(f"SCORE {result.score:.2f}", f"FPS {result.present_fps_mean:.1f} [{severity_markup(fps_state)}]", width=content_w), width=frame_w),
                box_line(kv_line(f"PIPE {result.recv_fps_mean:.1f} | DECODE {result.decode_fps_mean:.1f}", f"LAT {result.e2e_p95_mean_ms:.1f}ms [{severity_markup(lat_state)}]", width=content_w), width=frame_w),
                box_line(kv_line(f"LIVE Mbps {result.bitrate_mbps_mean:.1f} [{severity_markup(mbps_state)}]", f"DROPS/s {result.drop_rate_per_sec:.3f} [{severity_markup(drop_state)}]", width=content_w), width=frame_w),
                box_line(kv_line(f"QUEUE {result.queue_depth_mean:.3f} [{severity_markup(queue_state)}]", f"LATE/s {result.late_rate_per_sec:.3f} [{severity_markup(late_state)}]", width=content_w), width=frame_w),
                box_line(kv_line(f"SAMPLES {result.sample_count}", f"NOTE {html.escape(result.notes)}", width=content_w), width=frame_w),
            ]
        )
    else:
        lines.extend(
            [
                box_line(kv_line("SCORE <sampling>", f"FPS 0.0/{target_fps:.1f} [PENDING]", width=content_w), width=frame_w),
                box_line("PIPE/DECODE/LAT pending...", width=frame_w),
                box_line("LIVE Mbps pending...", width=frame_w),
            ]
        )
    lines.append(box_sep(width=frame_w))
    if result is not None:
        quality_state = "OK"
        if result.drop_rate_per_sec > 0.20 or result.e2e_p95_mean_ms > 120.0 or result.queue_depth_mean > 1.60:
            quality_state = "RISK"
        elif result.drop_rate_per_sec > 0.06 or result.e2e_p95_mean_ms > 70.0 or result.queue_depth_mean > 0.80:
            quality_state = "WARN"
        note_state = note_severity(result.notes)
        lines.extend(
            [
                box_line(kv_line(f"SCORE TR  {trend_render(valid_scores, chart_mode, width=32 if layout_mode == 'wide' else 24)}", f"FPS TR {trend_render(valid_present, chart_mode, width=28 if layout_mode == 'wide' else 22)}", width=content_w), width=frame_w),
                box_line(kv_line(f"DROP TR   {trend_render(valid_drop, chart_mode, width=32 if layout_mode == 'wide' else 24)}", f"MBPS TR {trend_render(valid_bitrate, chart_mode, width=28 if layout_mode == 'wide' else 22)}", width=content_w), width=frame_w),
                box_line(kv_line(f"E2E/DECODE/RENDER p95: {result.e2e_p95_mean_ms:.1f}/{result.decode_p95_mean_ms:.1f}/{result.render_p95_mean_ms:.1f} ms", f"STATE {severity_markup(quality_state)} | NOTE {severity_markup(note_state)}", width=content_w), width=frame_w),
            ]
        )
    else:
        lines.extend(
            [
                box_line(kv_line(f"SCORE TR  {trend_render(valid_scores, chart_mode, width=32 if layout_mode == 'wide' else 24)}", f"FPS TR {trend_render(valid_present, chart_mode, width=28 if layout_mode == 'wide' else 22)}", width=content_w), width=frame_w),
                box_line(kv_line(f"DROP TR   {trend_render(valid_drop, chart_mode, width=32 if layout_mode == 'wide' else 24)}", f"MBPS TR {trend_render(valid_bitrate, chart_mode, width=28 if layout_mode == 'wide' else 22)}", width=content_w), width=frame_w),
                box_line("STATE PENDING / waiting for metrics...", width=frame_w),
            ]
        )
    lines.append(box_sep(width=frame_w))
    payload = "\n".join(lines).strip() + "\n"
    try:
        path.write_text(payload, encoding="utf-8")
    except Exception:
        return


def profile_dir(profile_name: str) -> Path:
    return PROFILE_OUTPUT_DIR / sanitize_profile_name(profile_name)


def profile_run_dir(profile_name: str, run_id: str) -> Path:
    return profile_dir(profile_name) / "runs" / sanitize_profile_name(run_id)


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def trial_to_profile_doc(
    *,
    profile_name: str,
    best: TrialResult,
    mode: str,
    serial: str,
    stream_port: int,
) -> dict[str, Any]:
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    gop = max(1, best.config.fps // 2)
    return {
        "schema_version": 1,
        "profile_name": profile_name,
        "generated_at": now,
        "engine": "trainer_v2",
        "device": {"serial": serial, "stream_port": stream_port},
        "profile": {
            "description": "Auto-generated by trainer wizard.",
            "origin": {
                "generated_at": now,
                "mode": mode,
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
            "runtime": {
                "encoder": best.config.encoder,
                "cursor_mode": best.config.cursor_mode,
                "size": best.config.size,
                "fps": best.config.fps,
                "bitrate_kbps": best.config.bitrate_kbps,
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
        },
    }


def write_profile_artifacts(
    *,
    profile_name: str,
    profile_doc: dict[str, Any],
    parameters: dict[str, Any],
) -> tuple[Path, Path, Path | None]:
    out_dir = profile_dir(profile_name)
    profile_path = out_dir / f"{sanitize_profile_name(profile_name)}.json"
    params_path = out_dir / "parameters.json"
    write_json(profile_path, profile_doc)
    write_json(params_path, parameters)
    preflight_path: Path | None = None
    preflight = parameters.get("preflight")
    if isinstance(preflight, dict):
        preflight_path = out_dir / "preflight.json"
        write_json(preflight_path, preflight)
    return profile_path, params_path, preflight_path


def write_run_artifacts(
    *,
    profile_name: str,
    run_id: str,
    run_doc: dict[str, Any],
    profile_doc: dict[str, Any],
    parameters_doc: dict[str, Any],
    preflight: dict[str, Any] | None,
) -> Path:
    out_dir = profile_run_dir(profile_name, run_id)
    out_dir.mkdir(parents=True, exist_ok=True)
    write_json(out_dir / "run.json", run_doc)
    write_json(out_dir / "parameters.json", parameters_doc)
    write_json(out_dir / f"{sanitize_profile_name(profile_name)}.json", profile_doc)
    if isinstance(preflight, dict):
        write_json(out_dir / "preflight.json", preflight)
    return out_dir


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


def parse_encoder_list(raw: str) -> list[str]:
    out: list[str] = []
    for part in raw.replace(";", ",").split(","):
        token = part.strip().lower()
        if not token:
            continue
        if token == "jpeg":
            token = "mjpeg"
        if token in {"h264", "h265", "rawpng", "mjpeg"}:
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


def percentile(values: list[float], q: float) -> float:
    if not values:
        return 0.0
    if q <= 0:
        return min(values)
    if q >= 1:
        return max(values)
    ordered = sorted(values)
    idx = q * (len(ordered) - 1)
    lo = int(idx)
    hi = min(lo + 1, len(ordered) - 1)
    if lo == hi:
        return ordered[lo]
    frac = idx - lo
    return ordered[lo] * (1.0 - frac) + ordered[hi] * frac


def adb_push_benchmark(serial: str, *, size_mb: int = 8) -> dict[str, Any]:
    temp_path = Path(tempfile.gettempdir()) / f"wbeam-preflight-{serial}-{int(time.time())}.bin"
    remote_path = "/data/local/tmp/wbeam-preflight.bin"
    with temp_path.open("wb") as handle:
        handle.truncate(size_mb * 1024 * 1024)
    start = time.monotonic()
    proc = subprocess.run(
        ["adb", "-s", serial, "push", str(temp_path), remote_path],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=120,
        text=True,
        check=False,
    )
    elapsed = max(0.001, time.monotonic() - start)
    output = proc.stdout or ""
    parsed = re.search(r"\\((\\d+) bytes in ([0-9.]+)s\\)", output)
    if parsed:
        sent_bytes = int(parsed.group(1))
        parsed_elapsed = max(0.001, float(parsed.group(2)))
        mbps = (sent_bytes / parsed_elapsed) / (1024 * 1024)
    else:
        sent_bytes = size_mb * 1024 * 1024
        mbps = (sent_bytes / elapsed) / (1024 * 1024)
    subprocess.run(
        ["adb", "-s", serial, "shell", "rm", "-f", remote_path],
        cwd=ROOT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    try:
        temp_path.unlink()
    except FileNotFoundError:
        pass
    return {
        "ok": proc.returncode == 0,
        "size_mb": size_mb,
        "elapsed_sec": round(elapsed, 4),
        "throughput_mb_s": round(mbps, 3),
        "adb_output": output.strip(),
    }


def adb_shell_rtt_benchmark(serial: str, *, loops: int = 10) -> dict[str, Any]:
    samples_ms: list[float] = []
    failures = 0
    for _ in range(max(1, loops)):
        start = time.monotonic()
        proc = subprocess.run(
            ["adb", "-s", serial, "shell", "true"],
            cwd=ROOT,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=8,
            check=False,
        )
        elapsed_ms = (time.monotonic() - start) * 1000.0
        if proc.returncode == 0:
            samples_ms.append(elapsed_ms)
        else:
            failures += 1
    return {
        "ok": bool(samples_ms),
        "loops": loops,
        "failures": failures,
        "rtt_avg_ms": round(mean(samples_ms), 3),
        "rtt_p50_ms": round(percentile(samples_ms, 0.50), 3),
        "rtt_p95_ms": round(percentile(samples_ms, 0.95), 3),
    }


def collect_metrics_samples(
    *,
    control_port: int,
    serial: str,
    stream_port: int,
    sample_sec: int,
    poll_sec: float,
    on_sample: Callable[[list[dict[str, Any]], float], None] | None = None,
) -> list[dict[str, Any]]:
    samples: list[dict[str, Any]] = []
    started = time.monotonic()
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
        if on_sample is not None:
            elapsed = max(0.0, time.monotonic() - started)
            try:
                on_sample(samples, elapsed)
            except Exception:
                pass
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        time.sleep(min(poll_sec, remaining))
    return samples


def run_preflight(
    *,
    serial: str,
    control_port: int,
    stream_port: int,
    daemon_reachable: bool,
    state: str,
    current_cfg: dict[str, Any],
    mode: str,
    sample_sec: int,
) -> dict[str, Any]:
    started_at = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    print("\n[preflight] ADB link benchmark...")
    adb_push = adb_push_benchmark(serial, size_mb=8)
    adb_shell = adb_shell_rtt_benchmark(serial, loops=10)

    link_mbps = as_float(adb_push.get("throughput_mb_s"), 0.0)
    recommended_bitrate = int(max(4000.0, link_mbps * 1024.0 * 0.62))
    print(
        "[preflight] push="
        f"{adb_push.get('throughput_mb_s', 0.0)}MB/s "
        f"shell_rtt_p95={adb_shell.get('rtt_p95_ms', 0.0)}ms "
        f"recommended_bitrate={recommended_bitrate}kbps"
    )

    stream_baseline: dict[str, Any] = {
        "ok": False,
        "note": "daemon_unreachable",
    }
    if daemon_reachable:
        baseline_sec = max(6, min(12, sample_sec))
        pre_cfg = TrialConfig(
            encoder=str(current_cfg.get("encoder", "h264")),
            size=normalize_size(str(current_cfg.get("size", "1280x720"))),
            fps=max(24, as_int(current_cfg.get("fps"), 60)),
            bitrate_kbps=max(1000, as_int(current_cfg.get("bitrate_kbps"), 10000)),
            cursor_mode=str(current_cfg.get("cursor_mode", "embedded")),
        )
        try:
            print(f"[preflight] stream baseline sampling {baseline_sec}s...")
            samples = collect_metrics_samples(
                control_port=control_port,
                serial=serial,
                stream_port=stream_port,
                sample_sec=baseline_sec,
                poll_sec=0.8,
            )
            baseline = score_trial(pre_cfg, samples, baseline_sec, "preflight", mode)
            stream_baseline = {
                "ok": baseline.notes not in {"no_samples", "stream_unstable"},
                "state": state,
                "sample_sec": baseline_sec,
                "sample_count": baseline.sample_count,
                "score": round(baseline.score, 3),
                "present_fps_mean": round(baseline.present_fps_mean, 3),
                "recv_fps_mean": round(baseline.recv_fps_mean, 3),
                "decode_fps_mean": round(baseline.decode_fps_mean, 3),
                "e2e_p95_mean_ms": round(baseline.e2e_p95_mean_ms, 3),
                "drop_rate_per_sec": round(baseline.drop_rate_per_sec, 5),
                "late_rate_per_sec": round(baseline.late_rate_per_sec, 5),
                "notes": baseline.notes,
            }
            print(
                "[preflight] baseline "
                f"present={baseline.present_fps_mean:.1f} "
                f"recv={baseline.recv_fps_mean:.1f} "
                f"e2e95={baseline.e2e_p95_mean_ms:.1f}ms note={baseline.notes}"
            )
        except Exception as exc:
            stream_baseline = {
                "ok": False,
                "state": state,
                "note": f"baseline_failed: {exc}",
            }
            print(f"[preflight] baseline failed: {exc}")

    return {
        "started_at": started_at,
        "serial": serial,
        "control_port": control_port,
        "stream_port": stream_port,
        "mode": mode,
        "adb_push": adb_push,
        "adb_shell_rtt": adb_shell,
        "stream_baseline": stream_baseline,
        "recommended_bitrate_kbps": recommended_bitrate,
    }


def scoring_preset(mode: str) -> dict[str, float]:
    if mode == "low_latency":
        return {
            "w_present": 44.0,
            "w_recv": 14.0,
            "w_decode": 8.0,
            "w_latency": 34.0,
            "drop_penalty": 9.0,
            "late_penalty": 7.0,
            "decode_penalty": 1.2,
            "render_penalty": 1.1,
            "queue_penalty": 2.1,
            "gate_present_ratio": 0.86,
            "gate_recv_ratio": 0.82,
            "gate_drop_rate": 2.8,
        }
    if mode == "balanced":
        return {
            "w_present": 50.0,
            "w_recv": 16.0,
            "w_decode": 12.0,
            "w_latency": 22.0,
            "drop_penalty": 8.2,
            "late_penalty": 6.0,
            "decode_penalty": 1.0,
            "render_penalty": 0.8,
            "queue_penalty": 1.8,
            "gate_present_ratio": 0.84,
            "gate_recv_ratio": 0.80,
            "gate_drop_rate": 3.2,
        }
    # quality default
    return {
        "w_present": 56.0,
        "w_recv": 18.0,
        "w_decode": 11.0,
        "w_latency": 15.0,
        "drop_penalty": 7.8,
        "late_penalty": 5.0,
        "decode_penalty": 0.9,
        "render_penalty": 0.6,
        "queue_penalty": 1.5,
        "gate_present_ratio": 0.82,
        "gate_recv_ratio": 0.78,
        "gate_drop_rate": 3.8,
    }


def score_trial(
    config: TrialConfig,
    samples: list[dict[str, Any]],
    sample_sec: int,
    trial_id: str,
    mode: str,
) -> TrialResult:
    preset = scoring_preset(mode)
    present_vals: list[float] = []
    recv_vals: list[float] = []
    decode_vals: list[float] = []
    e2e_p95_vals: list[float] = []
    decode_p95_vals: list[float] = []
    render_p95_vals: list[float] = []
    bitrate_mbps_vals: list[float] = []
    queue_vals: list[float] = []
    drops_first = 0
    drops_last = 0
    late_first = 0
    late_last = 0

    for idx, sample in enumerate(samples):
        metrics = sample.get("metrics", {}) if isinstance(sample, dict) else {}
        kpi = metrics.get("kpi", {}) if isinstance(metrics, dict) else {}
        latest = metrics.get("latest_client_metrics", {}) if isinstance(metrics, dict) else {}
        bitrate_bps = as_float(metrics.get("bitrate_actual_bps"), 0.0) if isinstance(metrics, dict) else 0.0
        if bitrate_bps <= 0.0 and isinstance(latest, dict):
            bitrate_bps = as_float(latest.get("recv_bps"), 0.0)
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
        bitrate_mbps_vals.append(max(0.0, bitrate_bps / 1_000_000.0))
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
    bitrate_mbps_mean = mean(bitrate_mbps_vals)
    observed_sec = max(float(sample_sec), 1.0)
    drop_rate = max(0.0, (drops_last - drops_first) / observed_sec)
    late_rate = max(0.0, (late_last - late_first) / observed_sec)

    present_ratio = min(1.2, max(0.0, present_mean / target))
    recv_ratio = min(1.2, max(0.0, recv_mean / target))
    decode_ratio = min(1.2, max(0.0, decode_mean / target))
    latency_bonus = max(0.0, 1.0 - (e2e_p95_mean / 180.0))
    base_score = (
        present_ratio * preset["w_present"]
        + recv_ratio * preset["w_recv"]
        + decode_ratio * preset["w_decode"]
        + latency_bonus * preset["w_latency"]
    )
    penalty = (
        drop_rate * preset["drop_penalty"]
        + late_rate * preset["late_penalty"]
        + max(0.0, decode_p95_mean - 12.0) * preset["decode_penalty"]
        + max(0.0, render_p95_mean - 8.0) * preset["render_penalty"]
        + queue_mean * preset["queue_penalty"]
    )
    hard_fail = False
    if present_mean < target * preset["gate_present_ratio"]:
        penalty += 22.0
        hard_fail = True
    if recv_mean < target * preset["gate_recv_ratio"]:
        penalty += 12.0
        hard_fail = True
    if drop_rate > preset["gate_drop_rate"]:
        penalty += 16.0
        hard_fail = True

    score = base_score - penalty
    notes = "ok"
    if not samples:
        notes = "no_samples"
    elif present_mean <= 1.0 and recv_mean <= 1.0:
        notes = "stream_unstable"
    elif hard_fail:
        notes = "gate_failed"

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
        bitrate_mbps_mean=bitrate_mbps_mean,
        sample_count=len(samples),
        notes=notes,
    )


def build_trial_space(
    *,
    mode: str,
    current: dict[str, Any],
    device_size: str | None,
    encoder_mode: str,
    selected_encoders: list[str] | None,
    bitrate_min_kbps: int,
    bitrate_max_kbps: int,
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
        sizes = [device_size] if device_size else [current_size]
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

    encoders = [e for e in dedupe_keep_order(encoders) if e in {"h264", "h265", "rawpng", "mjpeg"}]
    if selected_encoders:
        allowed = {e for e in selected_encoders if e in {"h264", "h265", "rawpng", "mjpeg"}}
        if allowed:
            encoders = [e for e in encoders if e in allowed]
            if not encoders:
                encoders = sorted(allowed)
    if encoder_mode == "single" and encoders:
        encoders = [encoders[0]]
    sizes = [s for s in dedupe_keep_order(sizes) if "x" in s]
    fps_values = [v for v in dedupe_keep_order([max(24, v) for v in fps_values]) if v <= 240]
    bitrate_values = [v for v in dedupe_keep_order([max(1000, v) for v in bitrate_values]) if v <= 400000]
    lo = max(1000, min(bitrate_min_kbps, bitrate_max_kbps))
    hi = max(lo, max(bitrate_min_kbps, bitrate_max_kbps))
    bitrate_values = [v for v in bitrate_values if lo <= v <= hi]
    if not bitrate_values:
        bitrate_values = [min(hi, max(lo, current_bitrate))]
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


def main() -> int:
    parser = argparse.ArgumentParser(description="WBeam main-lane trainer wizard.")
    parser.add_argument("--control-port", type=int, default=int(os.environ.get("WBEAM_CONTROL_PORT", "5001")))
    parser.add_argument("--stream-port", type=int, default=None, help="Override stream port for selected serial.")
    parser.add_argument("--serial", default=None, help="Target ADB serial (interactive if omitted).")
    parser.add_argument(
        "--run-id",
        default=None,
        help="Optional deterministic run id for artifact storage (profiles/<name>/runs/<run_id>).",
    )
    parser.add_argument(
        "--profile-name",
        default=None,
        help="Target profile artifact name (stored under config/training/profiles/<name>/).",
    )
    parser.add_argument("--mode", choices=["balanced", "quality", "latency", "custom"], default="quality")
    parser.add_argument("--trials", type=int, default=None)
    parser.add_argument("--generations", type=int, default=2)
    parser.add_argument("--population", type=int, default=24)
    parser.add_argument("--elite-count", type=int, default=6)
    parser.add_argument("--mutation-rate", type=float, default=0.34)
    parser.add_argument("--crossover-rate", type=float, default=0.50)
    parser.add_argument("--encoder-mode", choices=["single", "multi"], default="multi")
    parser.add_argument("--encoders", default="h265,h264", help="CSV encoders: h264,h265,mjpeg,rawpng")
    parser.add_argument("--bitrate-min-kbps", type=int, default=10000)
    parser.add_argument("--bitrate-max-kbps", type=int, default=200000)
    parser.add_argument("--warmup-sec", type=int, default=4)
    parser.add_argument("--sample-sec", type=int, default=12)
    parser.add_argument("--poll-sec", type=float, default=1.0)
    parser.add_argument(
        "--non-interactive",
        action=argparse.BooleanOptionalAction,
        default=False,
        help="Disable prompts and use defaults/flags for all decisions.",
    )
    parser.add_argument(
        "--apply-best",
        action=argparse.BooleanOptionalAction,
        default=None,
        help="Apply best config at end (default: prompt in interactive, true in non-interactive).",
    )
    parser.add_argument(
        "--export-best",
        action=argparse.BooleanOptionalAction,
        default=None,
        help="Export best profile at end (default: prompt in interactive, true in non-interactive).",
    )
    parser.add_argument(
        "--overlay",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Reserved flag for trainer compatibility (no effect in live trainer path).",
    )
    parser.add_argument(
        "--overlay-chart",
        choices=["bars", "line"],
        default="bars",
        help="On-device HUD trend style.",
    )
    parser.add_argument(
        "--overlay-layout",
        choices=["compact", "wide"],
        default="wide",
        help="On-device HUD layout width preset.",
    )
    args = parser.parse_args()
    run_id = sanitize_profile_name(args.run_id or f"run-{datetime.now().strftime('%Y%m%d-%H%M%S')}")
    generations = max(1, min(64, int(args.generations)))
    population = max(2, min(256, int(args.population)))
    elite_count = max(1, min(population - 1, int(args.elite_count)))
    mutation_rate = min(1.0, max(0.0, float(args.mutation_rate)))
    crossover_rate = min(1.0, max(0.0, float(args.crossover_rate)))
    selected_encoders = parse_encoder_list(args.encoders)
    if not selected_encoders:
        selected_encoders = ["h264"]
    if args.encoder_mode == "single":
        selected_encoders = selected_encoders[:1]
    bitrate_min_kbps = max(1000, min(int(args.bitrate_min_kbps), int(args.bitrate_max_kbps)))
    bitrate_max_kbps = max(bitrate_min_kbps, max(int(args.bitrate_min_kbps), int(args.bitrate_max_kbps)))

    print("== WBeam Main Trainer Wizard ==")
    print(f"root: {ROOT}")
    print("engine: trainer_v2")
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
        if args.non_interactive:
            serial = serials[0]
            print(f"target auto-selected: {serial}")
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
    profile_name_raw = args.profile_name or ("baseline" if args.non_interactive else prompt_text("Profile name", "baseline"))
    profile_name = sanitize_profile_name(profile_name_raw)
    print(f"profile: {profile_name}")
    print(
        "tuning: "
        f"enc_mode={args.encoder_mode} encoders={','.join(selected_encoders)} "
        f"gen={generations} pop={population} elite={elite_count} "
        f"mut={mutation_rate:.2f} cross={crossover_rate:.2f} "
        f"bitrate=[{bitrate_min_kbps},{bitrate_max_kbps}]kbps"
    )

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
    mode = args.mode
    preflight = run_preflight(
        serial=serial,
        control_port=args.control_port,
        stream_port=stream_port,
        daemon_reachable=daemon_reachable,
        state=state,
        current_cfg=current_cfg,
        mode=mode,
        sample_sec=sample_sec,
    )
    recommended_bitrate = as_int(preflight.get("recommended_bitrate_kbps"), 0)
    if recommended_bitrate > 0:
        current_cfg["bitrate_kbps"] = min(200000, max(as_int(current_cfg.get("bitrate_kbps"), 10000), recommended_bitrate))
        print(f"[preflight] seeded bitrate_kbps={current_cfg['bitrate_kbps']}")

    requested_trials = args.trials if args.trials is not None else (
        max(1, min(64, generations * population)) if args.non_interactive else prompt_int(
            "How many trials to run",
            default=18,
            min_value=1,
            max_value=64,
        )
    )

    if not daemon_reachable:
        eprint("[error] trainer_v2 requires daemon API to be reachable.")
        return 1

    if state not in {"running", "starting"}:
        should_start = True if args.non_interactive else prompt_yes_no(
            "Session is not running. Try to start stream now?",
            default_yes=True,
        )
        if should_start:
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
            eprint("[error] trainer_v2 needs an active stream session.")
            return 1

    encoders, sizes, fps_values, bitrate_values, cursor_values = build_trial_space(
        mode=mode,
        current=current_cfg,
        device_size=device_size,
        encoder_mode=args.encoder_mode,
        selected_encoders=selected_encoders,
        bitrate_min_kbps=bitrate_min_kbps,
        bitrate_max_kbps=bitrate_max_kbps,
    )

    if mode == "custom" and not args.non_interactive:
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

    marker_path = trainer_active_marker_path(serial, stream_port)
    overlay_path = trainer_overlay_path(serial, stream_port) if args.overlay else None
    marker_payload = {
        "run_id": run_id,
        "profile_name": profile_name,
        "serial": serial,
        "stream_port": stream_port,
        "mode": mode,
        "started_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    }
    marker_path.write_text(json.dumps(marker_payload, ensure_ascii=True) + "\n", encoding="utf-8")
    if overlay_path is not None:
        write_overlay_snapshot(
            overlay_path,
            run_id=run_id,
            profile_name=profile_name,
            trial_id="t00",
            trial_index=0,
            trial_total=len(trials),
            generation_index=0,
            generation_total=generations,
            cfg=None,
            result=None,
            history=[],
            note="initializing",
            chart_mode=args.overlay_chart,
            layout_mode=args.overlay_layout,
        )

    try:
        results: list[TrialResult] = []
        started_at = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
        for idx, cfg in enumerate(trials, start=1):
            trial_id = f"t{idx:02d}"
            generation_idx = min(generations, ((idx - 1) // max(1, population)) + 1)
            if (idx - 1) % max(1, population) == 0:
                print(f"generation {generation_idx}/{generations}: population={population} (start)")
            if overlay_path is not None:
                write_overlay_snapshot(
                    overlay_path,
                    run_id=run_id,
                    profile_name=profile_name,
                    trial_id=trial_id,
                    trial_index=idx,
                    trial_total=len(trials),
                    generation_index=generation_idx,
                    generation_total=generations,
                    cfg=cfg,
                    result=None,
                    history=results,
                    note="apply -> warmup -> sample",
                    chart_mode=args.overlay_chart,
                    layout_mode=args.overlay_layout,
                )
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
                failed = TrialResult(
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
                    bitrate_mbps_mean=0.0,
                    sample_count=0,
                    notes="apply_failed",
                )
                results.append(failed)
                if overlay_path is not None:
                    write_overlay_snapshot(
                        overlay_path,
                        run_id=run_id,
                        profile_name=profile_name,
                        trial_id=trial_id,
                        trial_index=idx,
                        trial_total=len(trials),
                        generation_index=generation_idx,
                        generation_total=generations,
                        cfg=cfg,
                        result=failed,
                        history=results,
                        note="apply failed",
                        chart_mode=args.overlay_chart,
                        layout_mode=args.overlay_layout,
                    )
                continue

            if warmup_sec > 0:
                print(f"[{trial_id}] warmup {warmup_sec}s...")
                time.sleep(warmup_sec)

            print(f"[{trial_id}] sampling {sample_sec}s...")
            try:
                def _on_sample_progress(sample_rows: list[dict[str, Any]], elapsed_sec: float) -> None:
                    if overlay_path is None:
                        return
                    if not sample_rows:
                        return
                    partial = score_trial(
                        cfg,
                        sample_rows,
                        max(1, int(round(max(elapsed_sec, args.poll_sec)))),
                        trial_id,
                        mode,
                    )
                    write_overlay_snapshot(
                        overlay_path,
                        run_id=run_id,
                        profile_name=profile_name,
                        trial_id=trial_id,
                        trial_index=idx,
                        trial_total=len(trials),
                        generation_index=generation_idx,
                        generation_total=generations,
                        cfg=cfg,
                        result=partial,
                        history=results + [partial],
                        note=f"sampling {elapsed_sec:.1f}s",
                        chart_mode=args.overlay_chart,
                        layout_mode=args.overlay_layout,
                    )

                samples = collect_metrics_samples(
                    control_port=args.control_port,
                    serial=serial,
                    stream_port=stream_port,
                    sample_sec=sample_sec,
                    poll_sec=max(0.3, args.poll_sec),
                    on_sample=_on_sample_progress,
                )
                result = score_trial(cfg, samples, sample_sec, trial_id, mode)
                results.append(result)
                if overlay_path is not None:
                    write_overlay_snapshot(
                        overlay_path,
                        run_id=run_id,
                        profile_name=profile_name,
                        trial_id=trial_id,
                        trial_index=idx,
                        trial_total=len(trials),
                        generation_index=generation_idx,
                        generation_total=generations,
                        cfg=cfg,
                        result=result,
                        history=results,
                        note="sample complete",
                        chart_mode=args.overlay_chart,
                        layout_mode=args.overlay_layout,
                    )
                print(
                    f"[{trial_id}] score={result.score:.2f} present={result.present_fps_mean:.1f} "
                    f"recv={result.recv_fps_mean:.1f} e2e95={result.e2e_p95_mean_ms:.1f}ms "
                    f"drops/s={result.drop_rate_per_sec:.3f} mbps={result.bitrate_mbps_mean:.1f}"
                )
                print(
                    "done "
                    f"trial={trial_id} score={result.score:.2f} "
                    f"sender_p50={result.present_fps_mean:.1f} "
                    f"pipe_p50={result.recv_fps_mean:.1f} "
                    f"timeout_mean={result.e2e_p95_mean_ms:.1f} "
                    f"drop={result.drop_rate_per_sec * 100.0:.1f}% "
                    f"mbps={result.bitrate_mbps_mean:.1f}"
                )
            except Exception as exc:
                print(f"[{trial_id}] sample failed: {exc}")
                failed = TrialResult(
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
                    bitrate_mbps_mean=0.0,
                    sample_count=0,
                    notes="sample_failed",
                )
                results.append(failed)
                if overlay_path is not None:
                    write_overlay_snapshot(
                        overlay_path,
                        run_id=run_id,
                        profile_name=profile_name,
                        trial_id=trial_id,
                        trial_index=idx,
                        trial_total=len(trials),
                        generation_index=generation_idx,
                        generation_total=generations,
                        cfg=cfg,
                        result=failed,
                        history=results,
                        note="sample failed",
                        chart_mode=args.overlay_chart,
                        layout_mode=args.overlay_layout,
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
        if overlay_path is not None:
            write_overlay_snapshot(
                overlay_path,
                run_id=run_id,
                profile_name=profile_name,
                trial_id=best.trial_id,
                trial_index=len(trials),
                trial_total=len(trials),
                generation_index=generations,
                generation_total=generations,
                cfg=best.config,
                result=best,
                history=results,
                note="best candidate",
                chart_mode=args.overlay_chart,
                layout_mode=args.overlay_layout,
            )
        should_apply_best = args.apply_best
        if should_apply_best is None:
            should_apply_best = True if args.non_interactive else prompt_yes_no(
                "Apply best config to current running session now?",
                default_yes=True,
            )
        if should_apply_best:
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
        should_export_best = args.export_best
        if should_export_best is None:
            should_export_best = True if args.non_interactive else prompt_yes_no(
                "Export winner as baseline profile for main app path?",
                default_yes=True,
            )
        if should_export_best:
            write_profile_baseline(PROFILE_FILE, best)
            write_desktop_runtime_defaults(DESKTOP_RUNTIME_FILE, best)
            exported = True
            print(f"Updated {PROFILE_FILE}")
            print(f"Updated {DESKTOP_RUNTIME_FILE}")

        profile_doc = trial_to_profile_doc(
            profile_name=profile_name,
            best=best,
            mode=mode,
            serial=serial,
            stream_port=stream_port,
        )

        run_log = {
            "started_at": started_at,
            "run_id": run_id,
            "finished_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
            "control_port": args.control_port,
            "engine": "trainer_v2",
            "serial": serial,
            "stream_port": stream_port,
            "profile_name": profile_name,
            "mode": mode,
            "encoder_mode": args.encoder_mode,
            "encoders": selected_encoders,
            "generations": generations,
            "population": population,
            "elite_count": elite_count,
            "mutation_rate": mutation_rate,
            "crossover_rate": crossover_rate,
            "bitrate_min_kbps": bitrate_min_kbps,
            "bitrate_max_kbps": bitrate_max_kbps,
            "warmup_sec": warmup_sec,
            "sample_sec": sample_sec,
            "poll_sec": args.poll_sec,
            "non_interactive": bool(args.non_interactive),
            "overlay": bool(args.overlay),
            "overlay_chart": args.overlay_chart,
            "overlay_layout": args.overlay_layout,
            "trial_count": len(trials),
            "exported_baseline": exported,
            "preflight": preflight,
            "best": {
                "trial_id": best.trial_id,
                "score": best.score,
                "config": asdict(best.config),
                "notes": best.notes,
                "bitrate_mbps_mean": best.bitrate_mbps_mean,
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
                    "bitrate_mbps_mean": item.bitrate_mbps_mean,
                    "sample_count": item.sample_count,
                    "notes": item.notes,
                }
                for item in ranked
            ],
        }
        profile_path, params_path, preflight_path = write_profile_artifacts(
            profile_name=profile_name,
            profile_doc=profile_doc,
            parameters=run_log,
        )
        log_path = write_run_log(run_log)
        print(f"Run log: {log_path}")
        print(f"Profile artifact: {profile_path}")
        print(f"Parameters: {params_path}")
        if preflight_path is not None:
            print(f"Preflight: {preflight_path}")
        run_doc = {
            "run_id": run_id,
            "engine": "trainer_v2",
            "profile_name": profile_name,
            "serial": serial,
            "stream_port": stream_port,
            "mode": mode,
            "status": "completed",
            "started_at": started_at,
            "finished_at": run_log["finished_at"],
        }
        run_dir = write_run_artifacts(
            profile_name=profile_name,
            run_id=run_id,
            run_doc=run_doc,
            profile_doc=profile_doc,
            parameters_doc=run_log,
            preflight=preflight,
        )
        print(f"Run artifacts: {run_dir}")
        return 0
    finally:
        unlink_if_exists(marker_path)
        if overlay_path is not None:
            unlink_if_exists(overlay_path)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        eprint("\nInterrupted.")
        raise SystemExit(130)
