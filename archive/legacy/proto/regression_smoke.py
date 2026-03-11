#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import signal
import statistics
import subprocess
import threading
import time
from pathlib import Path

PORTAL_LOG = Path("/tmp/proto-portal-streamer.log")
RUNNER_EFFECTIVE = Path("/tmp/proto-effective-config-runner.json")


def log(msg: str) -> None:
    ts = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    print(f"[smoke {ts}] {msg}", flush=True)


def parse_metrics(path: Path) -> tuple[list[float], list[float], list[float], list[float]]:
    sender: list[float] = []
    pipeline: list[float] = []
    timeout_misses: list[float] = []
    stale: list[float] = []
    if not path.exists():
        return sender, pipeline, timeout_misses, stale
    rx = re.compile(
        r"pipeline_fps=(\d+)\s+sender_fps=([0-9.]+)\s+timeout_misses=(\d+)(?:\s+stale_dupe=(\d+))?"
    )
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = rx.search(raw)
        if not m:
            continue
        pipeline.append(float(m.group(1)))
        sender.append(float(m.group(2)))
        timeout_misses.append(float(m.group(3)))
        stale.append(float(m.group(4) or 0.0))
    return sender, pipeline, timeout_misses, stale


def effective_sender(sender: list[float], stale: list[float]) -> list[float]:
    if not sender:
        return []
    if len(stale) < len(sender):
        stale = stale + [0.0] * (len(sender) - len(stale))
    return [max(0.0, sender[i] - stale[i]) for i in range(len(sender))]


def percentile(values: list[float], q: float) -> float:
    if not values:
        return 0.0
    data = sorted(values)
    i = int(round((len(data) - 1) * q))
    return data[i]


def max_low_run(pipeline: list[float], threshold: float) -> int:
    best = 0
    cur = 0
    for v in pipeline:
        if v < threshold:
            cur += 1
            best = max(best, cur)
        else:
            cur = 0
    return best


def main() -> int:
    p = argparse.ArgumentParser(description="Quick regression smoke benchmark for proto runtime profile.")
    p.add_argument("--config", default="config/proto.json")
    p.add_argument("--prepare", action=argparse.BooleanOptionalAction, default=True)
    p.add_argument("--prepare-timeout-secs", type=int, default=240)
    p.add_argument("--warmup-secs", type=int, default=10)
    p.add_argument("--sample-secs", type=int, default=20)
    p.add_argument("--min-pipeline-p50", type=float, default=25.0)
    p.add_argument("--min-effective-sender-p50", type=float, default=25.0)
    p.add_argument("--max-stale-p50", type=float, default=6.0)
    p.add_argument("--max-timeout-mean", type=float, default=45.0)
    p.add_argument("--max-low-fps-run-secs", type=int, default=5)
    p.add_argument("--min-samples", type=int, default=8)
    args = p.parse_args()

    root = Path(__file__).resolve().parent
    config = (root / args.config).resolve()
    warmup_s = max(0, int(args.warmup_secs))
    sample_s = max(5, int(args.sample_secs))

    if args.prepare:
        cmd = [str((root / "run.sh").resolve()), "--config", str(config), "--prepare-only"]
        log("prepare phase: " + " ".join(cmd))
        cp = subprocess.run(
            cmd,
            cwd=str(root),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=max(60, int(args.prepare_timeout_secs)),
            check=False,
        )
        if cp.returncode != 0:
            print(cp.stdout)
            log(f"prepare failed rc={cp.returncode}")
            return 2

    effective_cfg = RUNNER_EFFECTIVE if RUNNER_EFFECTIVE.exists() else config
    log(f"using backend config: {effective_cfg}")
    try:
        PORTAL_LOG.unlink()
    except FileNotFoundError:
        pass

    cmd = ["cargo", "run", "--release", "--", "--config", str(effective_cfg)]
    proc = subprocess.Popen(
        cmd,
        cwd=str((root / "host").resolve()),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
        start_new_session=True,
    )
    log("backend started for smoke sample")
    output_lines: list[str] = []
    output_done = threading.Event()

    def _collect_output() -> None:
        try:
            if proc.stdout is None:
                return
            for line in proc.stdout:
                output_lines.append(line.rstrip("\n"))
        finally:
            output_done.set()

    threading.Thread(target=_collect_output, daemon=True).start()

    try:
        # Warmup phase.
        warmup_start = time.time()
        while time.time() - warmup_start < warmup_s:
            if proc.poll() is not None:
                break
            time.sleep(1.0)

        # Measure only sample window (exclude warmup from metrics).
        if proc.poll() is None:
            PORTAL_LOG.write_text("", encoding="utf-8")
        sample_start = time.time()
        while time.time() - sample_start < sample_s:
            if proc.poll() is not None:
                break
            time.sleep(1.0)
    finally:
        if proc.poll() is None:
            os_killpg(proc.pid, signal.SIGINT)
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os_killpg(proc.pid, signal.SIGKILL)
                proc.wait(timeout=5)
        output_done.wait(timeout=2.0)

    sender_raw, pipeline, timeout_misses, stale = parse_metrics(PORTAL_LOG)
    sender_eff = effective_sender(sender_raw, stale)

    metrics = {
        "samples": len(pipeline),
        "pipeline_p50": statistics.median(pipeline) if pipeline else 0.0,
        "pipeline_p20": percentile(pipeline, 0.20) if pipeline else 0.0,
        "sender_eff_p50": statistics.median(sender_eff) if sender_eff else 0.0,
        "sender_raw_p50": statistics.median(sender_raw) if sender_raw else 0.0,
        "stale_p50": statistics.median(stale) if stale else 0.0,
        "timeout_mean": statistics.fmean(timeout_misses) if timeout_misses else 0.0,
        "low_fps_run_secs": max_low_run(pipeline, threshold=10.0),
        "process_rc": proc.returncode,
        "warmup_secs": warmup_s,
        "sample_secs": sample_s,
    }

    report_path = Path("/tmp") / f"proto-smoke-{time.strftime('%Y%m%d-%H%M%S')}.json"
    report_path.write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8")
    log(f"report: {report_path}")
    log("metrics: " + json.dumps(metrics, separators=(",", ":")))

    ok = True
    if metrics["samples"] < max(1, int(args.min_samples)):
        ok = False
        log(f"FAIL samples={metrics['samples']} < {max(1, int(args.min_samples))}")
    if metrics["process_rc"] not in (0, None):
        ok = False
        tail = "\n".join(output_lines[-20:])
        log(f"FAIL backend exited rc={metrics['process_rc']}")
        if tail:
            print(tail)
    if metrics["pipeline_p50"] < args.min_pipeline_p50:
        ok = False
        log(f"FAIL pipeline_p50={metrics['pipeline_p50']:.1f} < {args.min_pipeline_p50:.1f}")
    if metrics["sender_eff_p50"] < args.min_effective_sender_p50:
        ok = False
        log(f"FAIL sender_eff_p50={metrics['sender_eff_p50']:.1f} < {args.min_effective_sender_p50:.1f}")
    if metrics["stale_p50"] > args.max_stale_p50:
        ok = False
        log(f"FAIL stale_p50={metrics['stale_p50']:.1f} > {args.max_stale_p50:.1f}")
    if metrics["timeout_mean"] > args.max_timeout_mean:
        ok = False
        log(f"FAIL timeout_mean={metrics['timeout_mean']:.1f} > {args.max_timeout_mean:.1f}")
    if metrics["low_fps_run_secs"] > args.max_low_fps_run_secs:
        ok = False
        log(f"FAIL low_fps_run_secs={metrics['low_fps_run_secs']} > {args.max_low_fps_run_secs}")

    if ok:
        log("PASS regression smoke")
        return 0
    return 1


def os_killpg(pid: int, sig: int) -> None:
    import os

    try:
        os.killpg(pid, sig)
    except ProcessLookupError:
        pass


if __name__ == "__main__":
    raise SystemExit(main())
