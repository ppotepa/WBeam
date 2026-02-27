#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import random
import re
import signal
import statistics
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


PORTAL_LOG = Path("/tmp/proto-portal-streamer.log")


PRESETS: list[tuple[str, dict[str, Any]]] = [
    (
        "stable_30",
        {
            "PROTO_CAPTURE_FPS": 30,
            "WBEAM_VIDEORATE_DROP_ONLY": 0,
            "WBEAM_PIPEWIRE_KEEPALIVE_MS": 33,
            "WBEAM_FRAMED_PULL_TIMEOUT_MS": 20,
            "WBEAM_QUEUE_MAX_TIME_MS": 12,
            "WBEAM_APPSINK_MAX_BUFFERS": 2,
            "PROTO_ADB_WRITE_TIMEOUT_MS": 35,
            "PROTO_H264_SOURCE_READ_TIMEOUT_MS": 1500,
            "PROTO_PORTAL_DEBUG_FPS": 0,
        },
    ),
    (
        "balanced_45",
        {
            "PROTO_CAPTURE_FPS": 45,
            "PROTO_H264_REORDER": 0,
            "WBEAM_VIDEORATE_DROP_ONLY": 0,
            "WBEAM_PIPEWIRE_KEEPALIVE_MS": 25,
            "WBEAM_FRAMED_PULL_TIMEOUT_MS": 16,
            "WBEAM_QUEUE_MAX_TIME_MS": 10,
            "WBEAM_APPSINK_MAX_BUFFERS": 2,
            "PROTO_ADB_WRITE_TIMEOUT_MS": 30,
            "PROTO_H264_SOURCE_READ_TIMEOUT_MS": 1300,
            "PROTO_PORTAL_DEBUG_FPS": 0,
        },
    ),
    (
        "balanced_45_r1",
        {
            "PROTO_CAPTURE_FPS": 45,
            "PROTO_H264_REORDER": 1,
            "WBEAM_VIDEORATE_DROP_ONLY": 0,
            "WBEAM_PIPEWIRE_KEEPALIVE_MS": 25,
            "WBEAM_FRAMED_PULL_TIMEOUT_MS": 16,
            "WBEAM_QUEUE_MAX_TIME_MS": 10,
            "WBEAM_APPSINK_MAX_BUFFERS": 2,
            "PROTO_ADB_WRITE_TIMEOUT_MS": 30,
            "PROTO_H264_SOURCE_READ_TIMEOUT_MS": 1300,
            "PROTO_PORTAL_DEBUG_FPS": 0,
        },
    ),
    (
        "balanced_60",
        {
            "PROTO_CAPTURE_FPS": 60,
            "PROTO_H264_REORDER": 0,
            "WBEAM_VIDEORATE_DROP_ONLY": 0,
            "WBEAM_PIPEWIRE_KEEPALIVE_MS": 20,
            "WBEAM_FRAMED_PULL_TIMEOUT_MS": 16,
            "WBEAM_QUEUE_MAX_TIME_MS": 8,
            "WBEAM_APPSINK_MAX_BUFFERS": 2,
            "PROTO_ADB_WRITE_TIMEOUT_MS": 30,
            "PROTO_H264_SOURCE_READ_TIMEOUT_MS": 1300,
            "PROTO_PORTAL_DEBUG_FPS": 0,
        },
    ),
    (
        "balanced_60_r1",
        {
            "PROTO_CAPTURE_FPS": 60,
            "PROTO_H264_REORDER": 1,
            "WBEAM_VIDEORATE_DROP_ONLY": 0,
            "WBEAM_PIPEWIRE_KEEPALIVE_MS": 20,
            "WBEAM_FRAMED_PULL_TIMEOUT_MS": 16,
            "WBEAM_QUEUE_MAX_TIME_MS": 8,
            "WBEAM_APPSINK_MAX_BUFFERS": 2,
            "PROTO_ADB_WRITE_TIMEOUT_MS": 30,
            "PROTO_H264_SOURCE_READ_TIMEOUT_MS": 1300,
            "PROTO_PORTAL_DEBUG_FPS": 0,
        },
    ),
    (
        "tight_60",
        {
            "PROTO_CAPTURE_FPS": 60,
            "WBEAM_VIDEORATE_DROP_ONLY": 0,
            "WBEAM_PIPEWIRE_KEEPALIVE_MS": 16,
            "WBEAM_FRAMED_PULL_TIMEOUT_MS": 12,
            "WBEAM_QUEUE_MAX_TIME_MS": 8,
            "WBEAM_APPSINK_MAX_BUFFERS": 1,
            "PROTO_ADB_WRITE_TIMEOUT_MS": 25,
            "PROTO_H264_SOURCE_READ_TIMEOUT_MS": 1200,
            "PROTO_PORTAL_DEBUG_FPS": 0,
        },
    ),
    (
        "aggressive_60",
        {
            "PROTO_CAPTURE_FPS": 60,
            "WBEAM_VIDEORATE_DROP_ONLY": 1,
            "WBEAM_PIPEWIRE_KEEPALIVE_MS": 12,
            "WBEAM_FRAMED_PULL_TIMEOUT_MS": 8,
            "WBEAM_QUEUE_MAX_TIME_MS": 4,
            "WBEAM_APPSINK_MAX_BUFFERS": 1,
            "PROTO_ADB_WRITE_TIMEOUT_MS": 20,
            "PROTO_H264_SOURCE_READ_TIMEOUT_MS": 1000,
            "PROTO_PORTAL_DEBUG_FPS": 0,
        },
    ),
]

TUNABLE_VALUES: dict[str, list[Any]] = {
    "PROTO_CAPTURE_FPS": [30, 45, 60],
    "PROTO_H264_REORDER": [0, 1],
    "WBEAM_VIDEORATE_DROP_ONLY": [0, 1],
    "WBEAM_PIPEWIRE_KEEPALIVE_MS": [8, 12, 16, 20, 25, 33],
    "WBEAM_FRAMED_PULL_TIMEOUT_MS": [6, 8, 10, 12, 16, 20],
    "WBEAM_QUEUE_MAX_TIME_MS": [4, 6, 8, 10, 12],
    "WBEAM_APPSINK_MAX_BUFFERS": [1, 2, 3],
    "PROTO_ADB_WRITE_TIMEOUT_MS": [15, 20, 25, 30, 35, 40],
    "PROTO_H264_SOURCE_READ_TIMEOUT_MS": [800, 1000, 1200, 1500, 1800, 2200],
    "PROTO_CAPTURE_BITRATE_KBPS": [5500, 7000, 8500, 10000],
}


@dataclass
class TrialResult:
    name: str
    score: float
    fps_score: float
    timeout_penalty: float
    jitter: float
    sender_p50: float
    raw_sender_p50: float
    stale_p50: float
    sender_p20: float
    pipe_p50: float
    timeout_mean: float
    samples: int
    process_rc: int | None
    config_path: Path
    run_log_path: Path
    notes: str = ""


def log(msg: str) -> None:
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    print(f"[autotune {ts}] {msg}", flush=True)


def format_secs(total: float) -> str:
    secs = max(0, int(round(total)))
    h, rem = divmod(secs, 3600)
    m, s = divmod(rem, 60)
    if h > 0:
        return f"{h}h{m:02d}m{s:02d}s"
    if m > 0:
        return f"{m}m{s:02d}s"
    return f"{s}s"


def percentile(values: list[float], q: float) -> float:
    if not values:
        return 0.0
    if q <= 0:
        return min(values)
    if q >= 1:
        return max(values)
    data = sorted(values)
    idx = int(round((len(data) - 1) * q))
    return data[idx]


def parse_portal_metrics(path: Path) -> tuple[list[float], list[float], list[float], list[float]]:
    sender: list[float] = []
    pipe: list[float] = []
    timeout_misses: list[float] = []
    stale_dupe: list[float] = []
    if not path.exists():
        return sender, pipe, timeout_misses, stale_dupe

    rx = re.compile(
        r"pipeline_fps=(\d+)\s+sender_fps=([0-9.]+)\s+timeout_misses=(\d+)(?:\s+stale_dupe=(\d+))?"
    )
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = rx.search(raw)
        if not m:
            continue
        pipe.append(float(m.group(1)))
        sender.append(float(m.group(2)))
        timeout_misses.append(float(m.group(3)))
        stale_dupe.append(float(m.group(4) or 0.0))
    return sender, pipe, timeout_misses, stale_dupe


def effective_sender_fps(raw_sender: list[float], stale_dupe: list[float]) -> list[float]:
    if not raw_sender:
        return []
    if len(stale_dupe) < len(raw_sender):
        stale_dupe = stale_dupe + [0.0] * (len(raw_sender) - len(stale_dupe))
    return [max(0.0, raw_sender[i] - stale_dupe[i]) for i in range(len(raw_sender))]


def parse_wbh1_metrics(path: Path) -> tuple[list[float], list[float]]:
    units: list[float] = []
    avg_kb: list[float] = []
    if not path.exists():
        return units, avg_kb
    rx = re.compile(r"WBH1 stats:\s+units=(\d+)\s+avg_kb=(\d+)")
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = rx.search(raw)
        if not m:
            continue
        units.append(float(m.group(1)))
        avg_kb.append(float(m.group(2)))
    return units, avg_kb


def resolve_portal_restore_token_file(base_cfg: dict[str, Any], base_path: Path) -> Path:
    raw = base_cfg.get("PROTO_PORTAL_RESTORE_TOKEN_FILE")
    if isinstance(raw, str) and raw.strip():
        token_path = Path(raw.strip())
        if token_path.is_absolute():
            return token_path
        return (base_path.parent / token_path).resolve()
    return Path("/tmp/proto-portal-restore-token")


def score_trial(sender_p50: float, sender_p20: float, pipe_p50: float, timeout_mean: float) -> tuple[float, float, float, float]:
    # Throughput component.
    fps_score = (sender_p50 * 0.50) + (sender_p20 * 0.35) + (pipe_p50 * 0.15)
    # Jitter proxy: bigger spread between p50 and p20 usually means less stable pacing.
    jitter = max(0.0, sender_p50 - sender_p20)
    # Piecewise timeout penalty: low misses are tolerated, persistent misses are expensive.
    timeout_penalty = (
        (timeout_mean * 0.08)
        + (max(0.0, timeout_mean - 20.0) * 0.20)
        + (max(0.0, timeout_mean - 60.0) * 0.25)
    )
    total_penalty = timeout_penalty + (jitter * 0.40)
    return fps_score - total_penalty, fps_score, total_penalty, jitter


def config_signature(cfg: dict[str, Any]) -> str:
    return json.dumps(cfg, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


def tuned_subset(cfg: dict[str, Any]) -> dict[str, Any]:
    return {k: cfg[k] for k in TUNABLE_VALUES if k in cfg}


def load_history(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        log(f"warning: failed to parse history file {path}: {exc}")
        return []
    if not isinstance(raw, dict):
        return []
    entries = raw.get("entries", [])
    if not isinstance(entries, list):
        return []
    out: list[dict[str, Any]] = []
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        cfg = entry.get("config")
        if not isinstance(cfg, dict):
            continue
        out.append(entry)
    return out


def save_history(path: Path, entries: list[dict[str, Any]], max_entries: int) -> None:
    keep = max(1, max_entries)
    trimmed = entries[-keep:]
    payload = {
        "version": 1,
        "updated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "entries": trimmed,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def merge_config(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
    out = dict(base)
    out.update(override)
    return out


def mutate_config(
    cfg: dict[str, Any],
    rng: random.Random,
    mutation_rate: float,
    min_mutations: int = 1,
    max_mutations: int = 4,
    tunable_keys: list[str] | None = None,
) -> dict[str, Any]:
    out = dict(cfg)
    keys = list(tunable_keys) if tunable_keys else list(TUNABLE_VALUES.keys())
    if not keys:
        return out
    selected = [k for k in keys if rng.random() < mutation_rate]
    if len(selected) < min_mutations:
        missing = [k for k in keys if k not in selected]
        rng.shuffle(missing)
        selected.extend(missing[: min_mutations - len(selected)])
    rng.shuffle(selected)
    selected = selected[: max(1, min(max_mutations, len(selected)))]

    for key in selected:
        values = TUNABLE_VALUES[key]
        current = out.get(key)
        if current in values and len(values) > 1 and rng.random() < 0.7:
            idx = values.index(current)
            step = rng.choice([-1, 1])
            idx = max(0, min(len(values) - 1, idx + step))
            out[key] = values[idx]
        else:
            out[key] = rng.choice(values)
    return out


def crossover_config(
    a: dict[str, Any],
    b: dict[str, Any],
    rng: random.Random,
    tunable_keys: list[str] | None = None,
) -> dict[str, Any]:
    out = dict(a)
    keys = list(tunable_keys) if tunable_keys else list(TUNABLE_VALUES.keys())
    for key in keys:
        if key in b and rng.random() < 0.5:
            out[key] = b[key]
    return out


def stop_process(proc: subprocess.Popen[str], timeout_s: float = 12.0) -> int | None:
    if proc.poll() is not None:
        return proc.returncode
    try:
        os.killpg(proc.pid, signal.SIGINT)
    except ProcessLookupError:
        pass
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if proc.poll() is not None:
            return proc.returncode
        time.sleep(0.2)
    try:
        os.killpg(proc.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    deadline = time.time() + 4.0
    while time.time() < deadline:
        if proc.poll() is not None:
            return proc.returncode
        time.sleep(0.2)
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    return proc.wait(timeout=5)


def prepare_device_once(proto_dir: Path, run_script: Path, config_path: Path, timeout_s: int) -> None:
    cmd = [str(run_script), "--config", str(config_path), "--prepare-only"]
    log("reuse-device: one-time APK deploy/app launch before benchmark loop")
    log("reuse-device cmd: " + " ".join(cmd))
    try:
        cp = subprocess.run(
            cmd,
            cwd=str(proto_dir),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=max(60, int(timeout_s)),
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        out = (exc.stdout or "") if isinstance(exc.stdout, str) else ""
        tail = "\n".join(out.splitlines()[-40:])
        raise RuntimeError(f"reuse-device prepare timed out after {timeout_s}s\n{tail}") from exc
    if cp.returncode != 0:
        out = cp.stdout or ""
        tail = "\n".join(out.splitlines()[-60:])
        raise RuntimeError(f"reuse-device prepare failed rc={cp.returncode}\n{tail}")
    log("reuse-device: prepare-only completed")


def write_overlay_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text.rstrip() + "\n", encoding="utf-8")


def overlay_settings_line(cfg: dict[str, Any]) -> str:
    return (
        f"f={cfg.get('PROTO_CAPTURE_FPS','?')} "
        f"br={cfg.get('PROTO_CAPTURE_BITRATE_KBPS','?')} "
        f"d={cfg.get('WBEAM_VIDEORATE_DROP_ONLY','?')} "
        f"k={cfg.get('WBEAM_PIPEWIRE_KEEPALIVE_MS','?')} "
        f"p={cfg.get('WBEAM_FRAMED_PULL_TIMEOUT_MS','?')} "
        f"q={cfg.get('WBEAM_QUEUE_MAX_TIME_MS','?')} "
        f"a={cfg.get('WBEAM_APPSINK_MAX_BUFFERS','?')}"
    )


def score_trend(values: list[float], width: int = 14) -> str:
    if not values:
        return "-" * width
    data = values[-width:]
    lo = min(data)
    hi = max(data)
    chars = " .:-=+*#%@"
    if hi - lo < 1e-9:
        return chars[len(chars) // 2] * len(data)
    out = []
    for v in data:
        t = (v - lo) / (hi - lo)
        idx = int(round(t * (len(chars) - 1)))
        out.append(chars[max(0, min(len(chars) - 1, idx))])
    return "".join(out)


def build_overlay_text(
    trial_name: str,
    cfg: dict[str, Any],
    phase: str,
    current_fps: float | None = None,
    current_dup_fps: float | None = None,
    live_score: float | None = None,
    sender_p50: float | None = None,
    raw_sender_p50: float | None = None,
    stale_p50: float | None = None,
    pipe_p50: float | None = None,
    timeout_mean: float | None = None,
    samples: int | None = None,
    trend: str = "",
) -> str:
    generation = trial_name.split("_", 1)[0] if "_" in trial_name else trial_name
    fps_text = "--" if current_fps is None else f"{current_fps:.1f}"
    dup_text = "--" if current_dup_fps is None else f"{current_dup_fps:.1f}"
    line1 = f"{generation} {trial_name}"
    line2 = f"{phase} fps={fps_text} dup={dup_text}"
    line3 = f"{overlay_settings_line(cfg)} sc={trend}" if trend else overlay_settings_line(cfg)
    if live_score is None:
        line4 = "score=... s50=... raw=... d50=... p50=... t=... n=..."
    else:
        line4 = (
            f"score={live_score:.1f} "
            f"s50={(sender_p50 or 0.0):.1f} raw={(raw_sender_p50 or 0.0):.1f} "
            f"d50={(stale_p50 or 0.0):.1f} p50={(pipe_p50 or 0.0):.1f} "
            f"t={(timeout_mean or 0.0):.1f} n={samples or 0}"
        )
    return "\n".join([line1, line2, line3, line4])


def run_trial(
    proto_dir: Path,
    run_script: Path,
    host_dir: Path,
    cfg: dict[str, Any],
    name: str,
    warmup_s: int,
    sample_s: int,
    startup_timeout_s: int,
    min_samples: int,
    min_sender_p50: float,
    min_pipe_p50: float,
    max_timeout_mean: float,
    require_portal_metrics: bool,
    host_only: bool,
    out_dir: Path,
    overlay_enabled: bool,
    portal_restore_token_file: Path | None,
) -> TrialResult:
    trial_cfg = dict(cfg)
    if portal_restore_token_file is not None:
        trial_cfg["PROTO_PORTAL_PERSIST_MODE"] = 2
        trial_cfg["PROTO_PORTAL_RESTORE_TOKEN_FILE"] = str(portal_restore_token_file)
    cfg_path = out_dir / f"{name}.json"
    run_log = out_dir / f"{name}.run.log"
    overlay_path = out_dir / f"{name}.overlay.txt"
    if overlay_enabled:
        trial_cfg["PROTO_PORTAL_OVERLAY_ENABLE"] = 1
        trial_cfg["PROTO_PORTAL_OVERLAY_TEXT_FILE"] = str(overlay_path)
        trial_cfg["PROTO_PORTAL_OVERLAY_FONT_DESC"] = "Sans 20"
        write_overlay_text(overlay_path, build_overlay_text(name, trial_cfg, "phase=starting"))
    cfg_path.write_text(json.dumps(trial_cfg, indent=2) + "\n", encoding="utf-8")
    try:
        PORTAL_LOG.unlink()
    except FileNotFoundError:
        pass

    mode = "host-only" if host_only else "full-run"
    log(f"trial={name} mode={mode} warmup={warmup_s}s sample={sample_s}s")
    cmd = (
        ["cargo", "run", "--release", "--", "--config", str(cfg_path)]
        if host_only
        else [str(run_script), "--config", str(cfg_path)]
    )
    cwd = host_dir if host_only else proto_dir
    proc = subprocess.Popen(
        cmd,
        cwd=str(cwd),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
        start_new_session=True,
    )

    log_done = threading.Event()
    backend_ready = threading.Event()

    def _collect() -> None:
        with run_log.open("w", encoding="utf-8") as f:
            if proc.stdout is not None:
                for line in proc.stdout:
                    f.write(line)
                    low = line.lower()
                    if (
                        "starting rust backend" in low
                        or "proto host loaded config" in low
                        or "h264 wbh1 mode enabled" in low
                    ):
                        backend_ready.set()
        log_done.set()

    t = threading.Thread(target=_collect, daemon=True)
    t.start()

    if not backend_ready.wait(timeout=max(5, startup_timeout_s)):
        rc = stop_process(proc)
        log_done.wait(timeout=2.0)
        return TrialResult(
            name=name,
            score=-1e9,
            fps_score=0.0,
            timeout_penalty=0.0,
            jitter=0.0,
            sender_p50=0.0,
            raw_sender_p50=0.0,
            stale_p50=0.0,
            sender_p20=0.0,
            pipe_p50=0.0,
            timeout_mean=0.0,
            samples=0,
            process_rc=rc,
            config_path=cfg_path,
            run_log_path=run_log,
            notes=f"backend did not start within {startup_timeout_s}s",
        )

    target = warmup_s + sample_s
    start = time.time()
    next_overlay_update = start
    live_scores: list[float] = []
    while time.time() - start < target:
        if proc.poll() is not None:
            break
        if overlay_enabled and time.time() >= next_overlay_update:
            elapsed = time.time() - start
            phase = "warmup" if elapsed < warmup_s else "sample"
            sender_live_raw, pipe_live, timeout_live, stale_live = parse_portal_metrics(PORTAL_LOG)
            sender_live = effective_sender_fps(sender_live_raw, stale_live)
            if sender_live:
                sender_p50_live = statistics.median(sender_live)
                raw_sender_p50_live = statistics.median(sender_live_raw)
                stale_p50_live = statistics.median(stale_live) if stale_live else 0.0
                sender_p20_live = percentile(sender_live, 0.20)
                pipe_p50_live = statistics.median(pipe_live)
                timeout_mean_live = statistics.fmean(timeout_live) if timeout_live else 0.0
                live_score, _, _, _ = score_trial(
                    sender_p50=sender_p50_live,
                    sender_p20=sender_p20_live,
                    pipe_p50=pipe_p50_live,
                    timeout_mean=timeout_mean_live,
                )
                live_scores.append(live_score)
                text = build_overlay_text(
                    name,
                    trial_cfg,
                    f"phase={phase} t={int(elapsed)}/{target}s",
                    current_fps=sender_live[-1],
                    current_dup_fps=stale_live[-1] if stale_live else 0.0,
                    live_score=live_score,
                    sender_p50=sender_p50_live,
                    raw_sender_p50=raw_sender_p50_live,
                    stale_p50=stale_p50_live,
                    pipe_p50=pipe_p50_live,
                    timeout_mean=timeout_mean_live,
                    samples=len(sender_live),
                    trend=score_trend(live_scores),
                )
            else:
                text = build_overlay_text(name, trial_cfg, f"phase={phase} t={int(elapsed)}/{target}s")
            write_overlay_text(overlay_path, text)
            next_overlay_update = time.time() + 1.0
        time.sleep(0.5)

    rc = stop_process(proc)
    log_done.wait(timeout=2.0)

    sender_raw, pipe, timeout_misses, stale_dupe = parse_portal_metrics(PORTAL_LOG)
    sender = effective_sender_fps(sender_raw, stale_dupe)
    using_fallback_metrics = False
    if not sender:
        wbh1_units, _ = parse_wbh1_metrics(run_log)
        if wbh1_units:
            sender_raw = wbh1_units
            sender = wbh1_units
            pipe = wbh1_units
            timeout_misses = [0.0 for _ in wbh1_units]
            stale_dupe = [0.0 for _ in wbh1_units]
            using_fallback_metrics = True
            note = "fallback metrics from WBH1 stats (portal fps lines missing)"
        else:
            note = "no portal fps samples"
            return TrialResult(
                name=name,
                score=-1e9,
                fps_score=0.0,
                timeout_penalty=0.0,
                jitter=0.0,
                sender_p50=0.0,
                raw_sender_p50=0.0,
                stale_p50=0.0,
                sender_p20=0.0,
                pipe_p50=0.0,
                timeout_mean=0.0,
                samples=0,
                process_rc=rc,
                config_path=cfg_path,
                run_log_path=run_log,
                notes=note,
            )

    sender_p50 = statistics.median(sender)
    raw_sender_p50 = statistics.median(sender_raw) if sender_raw else sender_p50
    stale_p50 = statistics.median(stale_dupe) if stale_dupe else 0.0
    sender_p20 = percentile(sender, 0.20)
    pipe_p50 = statistics.median(pipe)
    timeout_mean = statistics.fmean(timeout_misses) if timeout_misses else 0.0
    score, fps_score, timeout_penalty, jitter = score_trial(
        sender_p50=sender_p50,
        sender_p20=sender_p20,
        pipe_p50=pipe_p50,
        timeout_mean=timeout_mean,
    )
    if overlay_enabled:
        write_overlay_text(
            overlay_path,
            build_overlay_text(
                name,
                trial_cfg,
                "phase=final",
                current_fps=sender[-1] if sender else None,
                current_dup_fps=stale_dupe[-1] if stale_dupe else 0.0,
                live_score=score,
                sender_p50=sender_p50,
                raw_sender_p50=raw_sender_p50,
                stale_p50=stale_p50,
                pipe_p50=pipe_p50,
                timeout_mean=timeout_mean,
                samples=len(sender),
                trend=score_trend(live_scores + [score]),
            ),
        )

    notes = ""
    if using_fallback_metrics:
        notes = "fallback metrics from WBH1 stats (portal fps lines missing)"
    if require_portal_metrics and using_fallback_metrics:
        return TrialResult(
            name=name,
            score=-1e8,
            fps_score=fps_score,
            timeout_penalty=timeout_penalty,
            jitter=jitter,
            sender_p50=sender_p50,
            raw_sender_p50=raw_sender_p50,
            stale_p50=stale_p50,
            sender_p20=sender_p20,
            pipe_p50=pipe_p50,
            timeout_mean=timeout_mean,
            samples=len(sender),
            process_rc=rc,
            config_path=cfg_path,
            run_log_path=run_log,
            notes="health gate: missing portal metrics (fallback disabled)",
        )
    if sender_p50 < min_sender_p50 or pipe_p50 < min_pipe_p50 or timeout_mean > max_timeout_mean:
        return TrialResult(
            name=name,
            score=-1e8,
            fps_score=fps_score,
            timeout_penalty=timeout_penalty,
            jitter=jitter,
            sender_p50=sender_p50,
            raw_sender_p50=raw_sender_p50,
            stale_p50=stale_p50,
            sender_p20=sender_p20,
            pipe_p50=pipe_p50,
            timeout_mean=timeout_mean,
            samples=len(sender),
            process_rc=rc,
            config_path=cfg_path,
            run_log_path=run_log,
            notes=(
                "health gate: "
                f"sender_p50={sender_p50:.1f} (<{min_sender_p50:.1f}) or "
                f"pipe_p50={pipe_p50:.1f} (<{min_pipe_p50:.1f}) or "
                f"timeout_mean={timeout_mean:.1f} (>{max_timeout_mean:.1f})"
            ),
        )
    if len(sender) < max(1, min_samples):
        return TrialResult(
            name=name,
            score=-1e8,
            fps_score=fps_score,
            timeout_penalty=timeout_penalty,
            jitter=jitter,
            sender_p50=sender_p50,
            raw_sender_p50=raw_sender_p50,
            stale_p50=stale_p50,
            sender_p20=sender_p20,
            pipe_p50=pipe_p50,
            timeout_mean=timeout_mean,
            samples=len(sender),
            process_rc=rc,
            config_path=cfg_path,
            run_log_path=run_log,
            notes=f"insufficient samples: {len(sender)} < {max(1, min_samples)}",
        )

    return TrialResult(
        name=name,
        score=score,
        fps_score=fps_score,
        timeout_penalty=timeout_penalty,
        jitter=jitter,
        sender_p50=sender_p50,
        raw_sender_p50=raw_sender_p50,
        stale_p50=stale_p50,
        sender_p20=sender_p20,
        pipe_p50=pipe_p50,
        timeout_mean=timeout_mean,
        samples=len(sender),
        process_rc=rc,
        config_path=cfg_path,
        run_log_path=run_log,
        notes=notes,
    )


def parse_args(argv: list[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Auto-benchmark proto streaming presets and pick best config")
    p.add_argument("--base-config", default="config/proto.json")
    p.add_argument("--generations", type=int, default=1)
    p.add_argument("--population", type=int, default=7)
    p.add_argument("--elite-count", type=int, default=2)
    p.add_argument("--mutation-rate", type=float, default=0.35)
    p.add_argument("--seed", type=int, default=1337)
    p.add_argument("--warmup-secs", type=int, default=12)
    p.add_argument("--sample-secs", type=int, default=24)
    p.add_argument("--startup-timeout-secs", type=int, default=180)
    p.add_argument("--min-samples", type=int, default=8)
    p.add_argument("--gate-min-sender-p50", type=float, default=20.0)
    p.add_argument("--gate-min-pipe-p50", type=float, default=20.0)
    p.add_argument("--gate-max-timeout-mean", type=float, default=30.0)
    p.add_argument(
        "--require-portal-metrics",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Reject trials when portal_fps metrics are missing (WBH1 fallback only).",
    )
    p.add_argument("--max-presets", type=int, default=len(PRESETS))
    p.add_argument("--results", default="autotune-results.json")
    p.add_argument("--history-file", default="autotune-history.json")
    p.add_argument("--history-seed-count", type=int, default=4)
    p.add_argument("--history-max-entries", type=int, default=300)
    p.add_argument("--best-config-out", default="config/autotune-best.json")
    p.add_argument(
        "--fps",
        type=int,
        choices=sorted(int(v) for v in TUNABLE_VALUES["PROTO_CAPTURE_FPS"]),
        default=None,
        help="Lock capture FPS for the entire run (for example: --fps 60).",
    )
    p.add_argument(
        "--reuse-device",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="If full-run: deploy/launch app once, then benchmark host/backend only per trial.",
    )
    p.add_argument(
        "--overlay",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Show trial HUD overlay on the streamed image (generation/trial/settings/live score).",
    )
    p.add_argument(
        "--single-portal-consent",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Reuse portal restore token so source selection is typically needed only once across runs.",
    )
    p.add_argument("--host-only", action="store_true", help="Benchmark host/backend only (skip APK build/install).")
    p.add_argument("--apply-best", action="store_true")
    return p.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    run_started_utc = datetime.now(timezone.utc)
    run_started_mono = time.monotonic()
    log(f"run_start={run_started_utc.strftime('%Y-%m-%dT%H:%M:%SZ')}")
    proto_dir = Path(__file__).resolve().parent
    host_dir = (proto_dir / "host").resolve()
    base_path = (proto_dir / args.base_config).resolve()
    run_script = (proto_dir / "run.sh").resolve()

    if not run_script.exists():
        log(f"missing run script: {run_script}")
        return 1
    if not base_path.exists():
        log(f"missing base config: {base_path}")
        return 1

    try:
        base_cfg = json.loads(base_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        log(f"invalid json config: {base_path} ({exc})")
        return 1
    if not isinstance(base_cfg, dict):
        log("base config root must be a JSON object")
        return 1

    stamp = time.strftime("%Y%m%d-%H%M%S")
    out_dir = Path("/tmp") / f"proto-autotune-{stamp}"
    out_dir.mkdir(parents=True, exist_ok=True)
    log(f"results dir: {out_dir}")
    history_path = (proto_dir / args.history_file).resolve()
    best_config_out = (proto_dir / args.best_config_out).resolve()

    rng = random.Random(args.seed)
    generations = max(1, int(args.generations))
    population = max(1, int(args.population))
    elite_count = max(1, int(args.elite_count))
    mutation_rate = min(1.0, max(0.0, float(args.mutation_rate)))
    history_seed_count = max(0, int(args.history_seed_count))
    history_max_entries = max(1, int(args.history_max_entries))
    gate_min_sender_p50 = max(0.0, float(args.gate_min_sender_p50))
    gate_min_pipe_p50 = max(0.0, float(args.gate_min_pipe_p50))
    gate_max_timeout_mean = max(0.0, float(args.gate_max_timeout_mean))
    require_portal_metrics = bool(args.require_portal_metrics)
    forced_fps = int(args.fps) if args.fps is not None else None
    if forced_fps is not None:
        base_cfg["PROTO_CAPTURE_FPS"] = forced_fps
    show_overlay = bool(args.overlay)
    single_portal_consent = bool(args.single_portal_consent)
    log(
        "settings: "
        f"gen={generations} pop={population} elite={elite_count} mut={mutation_rate:.2f} "
        f"warmup={max(0, args.warmup_secs)}s sample={max(5, args.sample_secs)}s "
        f"reuse_device={bool(args.reuse_device)} host_only={bool(args.host_only)} "
        f"overlay={show_overlay} single_portal_consent={single_portal_consent} "
        f"forced_fps={forced_fps if forced_fps is not None else 'auto'} "
        f"gate(sender>={gate_min_sender_p50:.1f},pipe>={gate_min_pipe_p50:.1f},timeout<={gate_max_timeout_mean:.1f},portal={require_portal_metrics})"
    )

    benchmark_host_only = bool(args.host_only)
    frozen_keys: set[str] = set()
    if forced_fps is not None:
        frozen_keys.add("PROTO_CAPTURE_FPS")
    if not benchmark_host_only and bool(args.reuse_device):
        try:
            prepare_device_once(
                proto_dir=proto_dir,
                run_script=run_script,
                config_path=base_path,
                timeout_s=max(120, int(args.startup_timeout_secs)),
            )
        except RuntimeError as exc:
            log(str(exc))
            return 1
        benchmark_host_only = True
        frozen_keys.add("PROTO_H264_REORDER")
        log("reuse-device: enabled (trial loop will restart backend only)")
        if frozen_keys:
            log("reuse-device: app-side keys frozen: " + ", ".join(sorted(frozen_keys)))

    history_entries = load_history(history_path)
    if history_entries:
        log(f"loaded history: {history_path} entries={len(history_entries)}")

    portal_restore_token_file: Path | None = None
    if single_portal_consent:
        portal_restore_token_file = resolve_portal_restore_token_file(base_cfg, base_path)
        portal_restore_token_file.parent.mkdir(parents=True, exist_ok=True)
        log(f"portal consent: restore token path {portal_restore_token_file}")
        if portal_restore_token_file.exists():
            log("portal consent: existing token found, attempting auto-restore without chooser prompt")
        else:
            log("portal consent: token missing, first trial may ask for manual source selection")

    limit = max(1, min(args.max_presets, len(PRESETS)))
    tunable_keys = [k for k in TUNABLE_VALUES.keys() if k not in frozen_keys]
    seed_presets = PRESETS[:limit]
    candidates: list[tuple[str, dict[str, Any]]] = []
    seen_sigs: set[str] = set()

    def add_candidate(label: str, cfg: dict[str, Any]) -> bool:
        normalized = dict(cfg)
        for key in frozen_keys:
            if key in base_cfg:
                normalized[key] = base_cfg[key]
        sig = config_signature(normalized)
        if sig in seen_sigs:
            return False
        seen_sigs.add(sig)
        candidates.append((label, normalized))
        return True

    if history_seed_count > 0 and history_entries:
        hist_ranked = sorted(
            history_entries,
            key=lambda e: float(e.get("score", -1e9)),
            reverse=True,
        )
        loaded = 0
        for entry in hist_ranked:
            if loaded >= history_seed_count or len(candidates) >= population:
                break
            hist_cfg = entry.get("config")
            if not isinstance(hist_cfg, dict):
                continue
            merged_hist = merge_config(base_cfg, tuned_subset(hist_cfg))
            label = f"hist{loaded + 1}"
            if add_candidate(label, merged_hist):
                loaded += 1
        if loaded:
            log(f"seeded {loaded} candidate(s) from history")

    for name, override in seed_presets:
        if len(candidates) >= population:
            break
        add_candidate(name, merge_config(base_cfg, override))

    if not candidates:
        add_candidate("base", dict(base_cfg))

    attempts = 0
    while len(candidates) < population:
        attempts += 1
        parent_cfg = rng.choice(candidates)[1]
        child_cfg = mutate_config(parent_cfg, rng, mutation_rate, tunable_keys=tunable_keys)
        if add_candidate(f"seed{len(candidates) + 1}", child_cfg):
            continue
        if attempts > population * 20:
            candidates.append((f"seed{len(candidates) + 1}", child_cfg))
            break

    all_results: list[TrialResult] = []
    generation_summaries: list[dict[str, Any]] = []
    trial_configs: dict[str, dict[str, Any]] = {}

    for gen in range(1, generations + 1):
        gen_t0 = time.monotonic()
        log(f"generation {gen}/{generations}: population={len(candidates)} (start)")
        gen_results: list[TrialResult] = []
        for idx, (label, cfg) in enumerate(candidates, start=1):
            trial_name = f"g{gen:02d}_{idx:02d}_{label}"
            trial_t0 = time.monotonic()
            res = run_trial(
                proto_dir=proto_dir,
                run_script=run_script,
                host_dir=host_dir,
                cfg=cfg,
                name=trial_name,
                warmup_s=max(0, args.warmup_secs),
                sample_s=max(5, args.sample_secs),
                startup_timeout_s=max(30, args.startup_timeout_secs),
                min_samples=max(1, args.min_samples),
                min_sender_p50=gate_min_sender_p50,
                min_pipe_p50=gate_min_pipe_p50,
                max_timeout_mean=gate_max_timeout_mean,
                require_portal_metrics=require_portal_metrics,
                host_only=benchmark_host_only,
                out_dir=out_dir,
                overlay_enabled=show_overlay,
                portal_restore_token_file=portal_restore_token_file,
            )
            trial_elapsed = time.monotonic() - trial_t0
            gen_results.append(res)
            trial_configs[res.name] = dict(cfg)
            log(
                f"done trial={res.name} score={res.score:.2f} sender_p50={res.sender_p50:.1f} "
                f"raw_sender_p50={res.raw_sender_p50:.1f} stale_p50={res.stale_p50:.1f} "
                f"pipe_p50={res.pipe_p50:.1f} timeout_mean={res.timeout_mean:.1f} "
                f"tpen={res.timeout_penalty:.1f} jitter={res.jitter:.1f} samples={res.samples} "
                f"elapsed={format_secs(trial_elapsed)}"
            )
            if res.notes:
                log(f"trial={res.name} note: {res.notes}")

        ranked_gen = sorted(gen_results, key=lambda r: r.score, reverse=True)
        gen_best = ranked_gen[0]
        all_results.extend(ranked_gen)
        generation_summaries.append(
            {
                "generation": gen,
                "best_name": gen_best.name,
                "best_score": gen_best.score,
                "best_sender_p50": gen_best.sender_p50,
                "best_raw_sender_p50": gen_best.raw_sender_p50,
                "best_stale_p50": gen_best.stale_p50,
                "best_pipe_p50": gen_best.pipe_p50,
                "best_timeout_mean": gen_best.timeout_mean,
                "best_timeout_penalty": gen_best.timeout_penalty,
                "valid_trials": len([r for r in ranked_gen if r.score > -1e7]),
                "trials": [
                    {
                        "name": r.name,
                        "score": r.score,
                        "samples": r.samples,
                        "notes": r.notes,
                    }
                    for r in ranked_gen
                ],
            }
        )
        gen_elapsed = time.monotonic() - gen_t0
        log(
            f"generation {gen}/{generations}: done elapsed={format_secs(gen_elapsed)} "
            f"best={gen_best.name} score={gen_best.score:.2f}"
        )

        if gen >= generations:
            break

        valid = [r for r in ranked_gen if r.score > -1e7]
        parent_results = (valid if valid else ranked_gen)[: max(1, min(elite_count, len(ranked_gen)))]
        parent_cfgs = [dict(trial_configs[r.name]) for r in parent_results if r.name in trial_configs]
        if not parent_cfgs:
            parent_cfgs = [dict(base_cfg)]

        next_candidates: list[tuple[str, dict[str, Any]]] = []
        for i, cfg in enumerate(parent_cfgs, start=1):
            next_candidates.append((f"elite{i}", cfg))
        while len(next_candidates) < population:
            parent_a = rng.choice(parent_cfgs)
            child = dict(parent_a)
            if len(parent_cfgs) >= 2 and rng.random() < 0.50:
                parent_b = rng.choice(parent_cfgs)
                child = crossover_config(parent_a, parent_b, rng, tunable_keys=tunable_keys)
            child = mutate_config(child, rng, mutation_rate, tunable_keys=tunable_keys)
            next_candidates.append((f"child{len(next_candidates) + 1}", child))

        candidates = next_candidates

    ranked = sorted(all_results, key=lambda r: r.score, reverse=True)
    valid_ranked = [r for r in ranked if r.score > -1e7]
    if valid_ranked:
        best = valid_ranked[0]
    else:
        best = ranked[0]
        log("warning: no valid trials with enough samples; best is fallback-only")

    best_cfg = trial_configs.get(best.name)
    if best_cfg is None:
        best_cfg = json.loads(best.config_path.read_text(encoding="utf-8"))
    best_config_out.parent.mkdir(parents=True, exist_ok=True)
    best_config_out.write_text(json.dumps(best_cfg, indent=2) + "\n", encoding="utf-8")

    run_entries: list[dict[str, Any]] = []
    for rank, r in enumerate(ranked, start=1):
        run_cfg = trial_configs.get(r.name)
        if run_cfg is None:
            run_cfg = json.loads(r.config_path.read_text(encoding="utf-8"))
        run_entries.append(
            {
                "timestamp": stamp,
                "rank": rank,
                "name": r.name,
                "score": r.score,
                "fps_score": r.fps_score,
                "timeout_penalty": r.timeout_penalty,
                "jitter": r.jitter,
                "sender_p50": r.sender_p50,
                "raw_sender_p50": r.raw_sender_p50,
                "stale_p50": r.stale_p50,
                "sender_p20": r.sender_p20,
                "pipe_p50": r.pipe_p50,
                "timeout_mean": r.timeout_mean,
                "samples": r.samples,
                "process_rc": r.process_rc,
                "notes": r.notes,
                "config": run_cfg,
            }
        )
    merged_history = history_entries + run_entries
    save_history(history_path, merged_history, history_max_entries)

    run_ended_utc = datetime.now(timezone.utc)
    run_duration_sec = time.monotonic() - run_started_mono

    report = {
        "timestamp": stamp,
        "run_started_utc": run_started_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "run_ended_utc": run_ended_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "run_duration_sec": round(run_duration_sec, 3),
        "base_config": str(base_path),
        "out_dir": str(out_dir),
        "history_file": str(history_path),
        "best_config_out": str(best_config_out),
        "generations": generations,
        "population": population,
        "elite_count": elite_count,
        "mutation_rate": mutation_rate,
        "gate_min_sender_p50": gate_min_sender_p50,
        "gate_min_pipe_p50": gate_min_pipe_p50,
        "gate_max_timeout_mean": gate_max_timeout_mean,
        "require_portal_metrics": require_portal_metrics,
        "forced_fps": forced_fps,
        "seed": args.seed,
        "reuse_device": bool(args.reuse_device),
        "benchmark_host_only": benchmark_host_only,
        "overlay": show_overlay,
        "single_portal_consent": single_portal_consent,
        "portal_restore_token_file": str(portal_restore_token_file) if portal_restore_token_file else "",
        "history_seed_count": history_seed_count,
        "history_entries_loaded": len(history_entries),
        "history_entries_written": min(len(merged_history), history_max_entries),
        "generation_summaries": generation_summaries,
        "results": [
            {
                "name": r.name,
                "score": r.score,
                "fps_score": r.fps_score,
                "timeout_penalty": r.timeout_penalty,
                "jitter": r.jitter,
                "sender_p50": r.sender_p50,
                "raw_sender_p50": r.raw_sender_p50,
                "stale_p50": r.stale_p50,
                "sender_p20": r.sender_p20,
                "pipe_p50": r.pipe_p50,
                "timeout_mean": r.timeout_mean,
                "samples": r.samples,
                "process_rc": r.process_rc,
                "config_path": str(r.config_path),
                "run_log_path": str(r.run_log_path),
                "notes": r.notes,
                "config": trial_configs.get(r.name, {}),
            }
            for r in ranked
        ],
        "best": {
            "name": best.name,
            "score": best.score,
            "sender_p50": best.sender_p50,
            "raw_sender_p50": best.raw_sender_p50,
            "stale_p50": best.stale_p50,
            "sender_p20": best.sender_p20,
            "pipe_p50": best.pipe_p50,
            "timeout_mean": best.timeout_mean,
            "config_path": str(best.config_path),
            "best_config_out": str(best_config_out),
            "config": best_cfg,
        },
    }

    results_rel = args.results
    if args.results == "autotune-results.json" and forced_fps is not None:
        results_rel = f"autotune-results-fps{forced_fps}-{stamp}.json"
    results_path = (proto_dir / results_rel).resolve()
    results_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    log(f"wrote report: {results_path}")
    log(f"wrote history: {history_path}")
    log(f"wrote best config snapshot: {best_config_out}")
    log(
        f"run_end={run_ended_utc.strftime('%Y-%m-%dT%H:%M:%SZ')} "
        f"duration={format_secs(run_duration_sec)}"
    )

    print("")
    print(
        "Run timing: "
        f"start={run_started_utc.strftime('%Y-%m-%dT%H:%M:%SZ')} "
        f"end={run_ended_utc.strftime('%Y-%m-%dT%H:%M:%SZ')} "
        f"duration={format_secs(run_duration_sec)}"
    )
    print("")
    print("Trial ranking:")
    for idx, r in enumerate(ranked, start=1):
        print(
            f"{idx:>2}. {r.name:<14} score={r.score:>7.2f} "
            f"sender_p50={r.sender_p50:>5.1f} raw_s50={r.raw_sender_p50:>5.1f} "
            f"dup_s50={r.stale_p50:>5.1f} sender_p20={r.sender_p20:>5.1f} "
            f"pipe_p50={r.pipe_p50:>5.1f} timeout={r.timeout_mean:>6.1f} "
            f"tpen={r.timeout_penalty:>6.1f} jitter={r.jitter:>4.1f} samples={r.samples:>2}"
        )
    print("")
    print("Generation best:")
    for g in generation_summaries:
        print(
            f"g{g['generation']}: {g['best_name']} score={g['best_score']:.2f} "
            f"sender_p50={g['best_sender_p50']:.1f} raw_s50={g['best_raw_sender_p50']:.1f} "
            f"dup_s50={g['best_stale_p50']:.1f} pipe_p50={g['best_pipe_p50']:.1f}"
        )
    print("")
    print(f"Best trial: {best.name} (trial config: {best.config_path})")
    print(f"Best config snapshot: {best_config_out}")

    if args.apply_best:
        base_path.write_text(json.dumps(best_cfg, indent=2) + "\n", encoding="utf-8")
        log(f"applied best preset to base config: {base_path}")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
