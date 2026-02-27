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
    sender_p50: float
    sender_p20: float
    pipe_p50: float
    timeout_mean: float
    samples: int
    process_rc: int | None
    config_path: Path
    run_log_path: Path
    notes: str = ""


def log(msg: str) -> None:
    print(f"[autotune] {msg}", flush=True)


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


def parse_portal_metrics(path: Path) -> tuple[list[float], list[float], list[float]]:
    sender: list[float] = []
    pipe: list[float] = []
    timeout_misses: list[float] = []
    if not path.exists():
        return sender, pipe, timeout_misses

    rx = re.compile(r"pipeline_fps=(\d+)\s+sender_fps=([0-9.]+)\s+timeout_misses=(\d+)")
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = rx.search(raw)
        if not m:
            continue
        pipe.append(float(m.group(1)))
        sender.append(float(m.group(2)))
        timeout_misses.append(float(m.group(3)))
    return sender, pipe, timeout_misses


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
) -> dict[str, Any]:
    out = dict(cfg)
    keys = list(TUNABLE_VALUES.keys())
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


def crossover_config(a: dict[str, Any], b: dict[str, Any], rng: random.Random) -> dict[str, Any]:
    out = dict(a)
    for key in TUNABLE_VALUES:
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
    host_only: bool,
    out_dir: Path,
) -> TrialResult:
    cfg_path = out_dir / f"{name}.json"
    run_log = out_dir / f"{name}.run.log"
    cfg_path.write_text(json.dumps(cfg, indent=2) + "\n", encoding="utf-8")
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
            sender_p50=0.0,
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
    while time.time() - start < target:
        if proc.poll() is not None:
            break
        time.sleep(0.5)

    rc = stop_process(proc)
    log_done.wait(timeout=2.0)

    sender, pipe, timeout_misses = parse_portal_metrics(PORTAL_LOG)
    if not sender:
        wbh1_units, _ = parse_wbh1_metrics(run_log)
        if wbh1_units:
            sender = wbh1_units
            pipe = wbh1_units
            timeout_misses = [0.0 for _ in wbh1_units]
            note = "fallback metrics from WBH1 stats (portal fps lines missing)"
        else:
            note = "no portal fps samples"
            return TrialResult(
                name=name,
                score=-1e9,
                sender_p50=0.0,
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
    sender_p20 = percentile(sender, 0.20)
    pipe_p50 = statistics.median(pipe)
    timeout_mean = statistics.fmean(timeout_misses) if timeout_misses else 0.0
    # Maximize sustained sender fps while penalizing starvation.
    score = (sender_p50 * 0.55) + (sender_p20 * 0.30) + (pipe_p50 * 0.15) - (timeout_mean * 0.05)

    notes = ""
    if not parse_portal_metrics(PORTAL_LOG)[0]:
        notes = "fallback metrics from WBH1 stats (portal fps lines missing)"
    if len(sender) < max(1, min_samples):
        return TrialResult(
            name=name,
            score=-1e8,
            sender_p50=sender_p50,
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
        sender_p50=sender_p50,
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
    p.add_argument("--max-presets", type=int, default=len(PRESETS))
    p.add_argument("--results", default="autotune-results.json")
    p.add_argument("--host-only", action="store_true", help="Benchmark host/backend only (skip APK build/install).")
    p.add_argument("--apply-best", action="store_true")
    return p.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
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

    rng = random.Random(args.seed)
    generations = max(1, int(args.generations))
    population = max(1, int(args.population))
    elite_count = max(1, int(args.elite_count))
    mutation_rate = min(1.0, max(0.0, float(args.mutation_rate)))

    limit = max(1, min(args.max_presets, len(PRESETS)))
    seed_presets = PRESETS[:limit]
    candidates: list[tuple[str, dict[str, Any]]] = [
        (name, merge_config(base_cfg, override))
        for name, override in seed_presets
    ]

    if len(candidates) > population:
        candidates = candidates[:population]
    while len(candidates) < population:
        parent_cfg = rng.choice(candidates)[1] if candidates else dict(base_cfg)
        child_cfg = mutate_config(parent_cfg, rng, mutation_rate)
        candidates.append((f"seed{len(candidates) + 1}", child_cfg))

    all_results: list[TrialResult] = []
    generation_summaries: list[dict[str, Any]] = []

    for gen in range(1, generations + 1):
        log(f"generation {gen}/{generations}: population={len(candidates)}")
        gen_results: list[TrialResult] = []
        for idx, (label, cfg) in enumerate(candidates, start=1):
            trial_name = f"g{gen:02d}_{idx:02d}_{label}"
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
                host_only=bool(args.host_only),
                out_dir=out_dir,
            )
            gen_results.append(res)
            log(
                f"done trial={res.name} score={res.score:.2f} sender_p50={res.sender_p50:.1f} "
                f"pipe_p50={res.pipe_p50:.1f} timeout_mean={res.timeout_mean:.1f} samples={res.samples}"
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
                "best_pipe_p50": gen_best.pipe_p50,
                "best_timeout_mean": gen_best.timeout_mean,
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

        if gen >= generations:
            break

        valid = [r for r in ranked_gen if r.score > -1e7]
        parent_results = (valid if valid else ranked_gen)[: max(1, min(elite_count, len(ranked_gen)))]
        parent_cfgs = [
            json.loads(Path(r.config_path).read_text(encoding="utf-8"))
            for r in parent_results
        ]

        next_candidates: list[tuple[str, dict[str, Any]]] = []
        for i, cfg in enumerate(parent_cfgs, start=1):
            next_candidates.append((f"elite{i}", cfg))
        while len(next_candidates) < population:
            parent_a = rng.choice(parent_cfgs)
            child = dict(parent_a)
            if len(parent_cfgs) >= 2 and rng.random() < 0.50:
                parent_b = rng.choice(parent_cfgs)
                child = crossover_config(parent_a, parent_b, rng)
            child = mutate_config(child, rng, mutation_rate)
            next_candidates.append((f"child{len(next_candidates) + 1}", child))

        candidates = next_candidates

    ranked = sorted(all_results, key=lambda r: r.score, reverse=True)
    valid_ranked = [r for r in ranked if r.score > -1e7]
    if valid_ranked:
        best = valid_ranked[0]
    else:
        best = ranked[0]
        log("warning: no valid trials with enough samples; best is fallback-only")

    report = {
        "timestamp": stamp,
        "base_config": str(base_path),
        "out_dir": str(out_dir),
        "generations": generations,
        "population": population,
        "elite_count": elite_count,
        "mutation_rate": mutation_rate,
        "seed": args.seed,
        "generation_summaries": generation_summaries,
        "results": [
            {
                "name": r.name,
                "score": r.score,
                "sender_p50": r.sender_p50,
                "sender_p20": r.sender_p20,
                "pipe_p50": r.pipe_p50,
                "timeout_mean": r.timeout_mean,
                "samples": r.samples,
                "process_rc": r.process_rc,
                "config_path": str(r.config_path),
                "run_log_path": str(r.run_log_path),
                "notes": r.notes,
            }
            for r in ranked
        ],
        "best": {
            "name": best.name,
            "score": best.score,
            "config_path": str(best.config_path),
        },
    }

    results_path = (proto_dir / args.results).resolve()
    results_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    log(f"wrote report: {results_path}")

    print("")
    print("Trial ranking:")
    for idx, r in enumerate(ranked, start=1):
        print(
            f"{idx:>2}. {r.name:<14} score={r.score:>7.2f} "
            f"sender_p50={r.sender_p50:>5.1f} sender_p20={r.sender_p20:>5.1f} "
            f"pipe_p50={r.pipe_p50:>5.1f} timeout={r.timeout_mean:>6.1f} samples={r.samples:>2}"
        )
    print("")
    print("Generation best:")
    for g in generation_summaries:
        print(
            f"g{g['generation']}: {g['best_name']} score={g['best_score']:.2f} "
            f"sender_p50={g['best_sender_p50']:.1f} pipe_p50={g['best_pipe_p50']:.1f}"
        )
    print("")
    print(f"Best trial: {best.name} (config: {best.config_path})")

    if args.apply_best:
        best_cfg = json.loads(best.config_path.read_text(encoding="utf-8"))
        base_path.write_text(json.dumps(best_cfg, indent=2) + "\n", encoding="utf-8")
        log(f"applied best preset to base config: {base_path}")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
