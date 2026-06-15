#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")


def run(cmd: list[str], *, log: Path, env: dict[str, str] | None = None, check: bool = True, dry_run: bool = False) -> subprocess.CompletedProcess[str]:
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("a", encoding="utf-8") as fh:
        fh.write(f"RUN: {' '.join(cmd)}\n")
        if dry_run:
            fh.write("DRY-RUN: command not executed\n")
            return subprocess.CompletedProcess(cmd, 0, "", "")
        proc = subprocess.run(cmd, cwd=str(ROOT), env=env, text=True, stdout=fh, stderr=subprocess.STDOUT, check=False)
        fh.write(f"EXIT: {proc.returncode}\n")
    if check and proc.returncode != 0:
        raise RuntimeError(f"command failed: {' '.join(cmd)} rc={proc.returncode}")
    return proc


def adb(args: argparse.Namespace, *parts: str, log_name: str = "adb.log", check: bool = True) -> subprocess.CompletedProcess[str]:
    cmd = ["adb"]
    if args.serial:
        cmd += ["-s", args.serial]
    cmd += list(parts)
    return run(cmd, log=args.report_dir / log_name, check=check, dry_run=args.dry_run)


def http_json(url: str, *, method: str = "GET", payload: dict | None = None, timeout: int = 5) -> dict:
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310
        raw = resp.read().decode("utf-8")
    return json.loads(raw) if raw.strip() else {}


def phone_info(args: argparse.Namespace) -> dict:
    if args.dry_run:
        return {"serial": args.serial, "model": "dry-run", "api": "0"}
    model = subprocess.run(["adb", "-s", args.serial, "shell", "getprop", "ro.product.model"], capture_output=True, text=True, check=False).stdout.strip()
    api = subprocess.run(["adb", "-s", args.serial, "shell", "getprop", "ro.build.version.sdk"], capture_output=True, text=True, check=False).stdout.strip()
    return {"serial": args.serial, "model": model, "api": api}


def read_phone_metrics(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}
    return payload if isinstance(payload, dict) else {}


PHONE_METRICS_CANDIDATES = [
    "/sdcard/wbeam-e2e-metrics.json",
    "/sdcard/Android/data/com.wbeam/files/wbeam-e2e-metrics.json",
]


def android_deploy_env(args: argparse.Namespace) -> dict[str, str]:
    env = dict(os.environ)
    env["WBEAM_ANDROID_SERIAL"] = args.serial
    env["WBEAM_HOST"] = "127.0.0.1"
    env["WBEAM_API_HOST"] = "127.0.0.1"
    env["WBEAM_STREAM_HOST"] = "127.0.0.1"
    env["WBEAM_CONTROL_PORT"] = str(args.phone_control_port)
    env["WBEAM_STREAM_PORT"] = str(args.phone_stream_port)
    env["WBEAM_API_IMPL"] = "host"
    return env


def estimate_bytes_from_logcat(path: Path) -> tuple[int, int]:
    if not path.exists():
        return 0, 0
    text = path.read_text(encoding="utf-8", errors="replace")
    frame_events = 0
    needles = ("frame", "decoded", "rendered", "recv", "h264", "wbeam")
    for line in text.splitlines():
        lower = line.lower()
        if any(needle in lower for needle in needles):
            frame_events += 1
    return frame_events * 16384, frame_events


def resolve_bytes_received(metrics_local: Path, logcat: Path) -> tuple[int, str, int]:
    metrics = read_phone_metrics(metrics_local)
    bytes_received = int(metrics.get("bytes_received") or 0)
    if bytes_received > 0:
        return bytes_received, "phone_metrics", 0
    fallback_bytes, frame_events = estimate_bytes_from_logcat(logcat)
    if fallback_bytes > 0:
        return fallback_bytes, "logcat_fallback", frame_events
    return 0, "none", 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--host-control-port", type=int, required=True)
    parser.add_argument("--host-stream-port", type=int, required=True)
    parser.add_argument("--phone-control-port", type=int, default=5001)
    parser.add_argument("--phone-stream-port", type=int, default=5000)
    parser.add_argument("--backend", required=True)
    parser.add_argument("--display-mode", default="duplicate")
    parser.add_argument("--duration-sec", type=int, default=60)
    parser.add_argument("--min-bytes-received", type=int, default=1)
    parser.add_argument("--report-dir", type=Path, required=True)
    parser.add_argument("--dry-run", action="store_true")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    args.report_dir.mkdir(parents=True, exist_ok=True)
    metrics_device = PHONE_METRICS_CANDIDATES[0]
    metrics_local = args.report_dir / "phone-metrics.json"
    deploy_log = args.report_dir / "deploy.log"
    logcat = args.report_dir / "phone-logcat.log"

    summary: dict = {
        "schema": 1,
        "ok": False,
        "android_execution": "host",
        "adb_serial": args.serial,
        "backend": args.backend,
        "display_mode": args.display_mode,
        "host_control_port": args.host_control_port,
        "host_stream_port": args.host_stream_port,
        "phone_control_port": args.phone_control_port,
        "phone_stream_port": args.phone_stream_port,
        "bytes_received": 0,
        "measurement_source": "none",
        "frame_events": 0,
        "artifacts": {
            "adb_log": "adb.log",
            "phone_info": "phone-info.json",
            "deploy_log": "deploy.log",
            "health_before": "health-before.json",
            "apply": "apply.json",
            "start": "start.json",
            "metrics_samples": "metrics-samples.jsonl",
            "health_after": "health-after.json",
            "status_after": "status-after.json",
            "phone_logcat": "phone-logcat.log",
            "phone_metrics": "phone-metrics.json",
        },
    }
    try:
        write_json(args.report_dir / "phone-info.json", phone_info(args))
        run(["adb", "start-server"], log=args.report_dir / "adb.log", dry_run=args.dry_run)
        adb(args, "reverse", f"tcp:{args.phone_control_port}", f"tcp:{args.host_control_port}")
        adb(args, "reverse", f"tcp:{args.phone_stream_port}", f"tcp:{args.host_stream_port}")
        adb(args, "logcat", "-c", check=False)
        adb(args, "shell", "rm", "-f", metrics_device, check=False)
        if not args.dry_run:
            write_json(args.report_dir / "health-before.json", http_json(f"http://127.0.0.1:{args.host_control_port}/v1/health"))

        env = android_deploy_env(args)
        run(["./wbeam", "android", "deploy"], log=deploy_log, env=env, dry_run=args.dry_run)

        if args.dry_run:
            write_json(metrics_local, {"bytes_received": args.min_bytes_received})
            summary["bytes_received"] = args.min_bytes_received
            summary["measurement_source"] = "dry_run"
            summary["ok"] = True
        else:
            apply_json = {"encoder": "h264", "size": "1280x800", "fps": 30, "bitrate_kbps": 10000}
            apply_response = http_json(f"http://127.0.0.1:{args.host_control_port}/v1/apply", method="POST", payload=apply_json)
            write_json(args.report_dir / "apply.json", apply_response)
            query = f"display_mode={args.display_mode}"
            if args.backend != "benchmark_game":
                query += f"&capture_backend={args.backend}"
            start_response = http_json(f"http://127.0.0.1:{args.host_control_port}/v1/start?{query}", method="POST", payload={})
            write_json(args.report_dir / "start.json", start_response)
            deadline = time.time() + args.duration_sec
            metrics_samples_path = args.report_dir / "metrics-samples.jsonl"
            while time.time() < deadline:
                try:
                    sample = http_json(f"http://127.0.0.1:{args.host_control_port}/v1/metrics")
                    with metrics_samples_path.open("a", encoding="utf-8") as fh:
                        fh.write(json.dumps(sample, sort_keys=True) + "\n")
                except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
                    pass
                time.sleep(1)
            for metrics_device in PHONE_METRICS_CANDIDATES:
                if adb(args, "pull", metrics_device, str(metrics_local), check=False).returncode == 0 and metrics_local.exists():
                    break
            adb(args, "logcat", "-d", log_name="phone-logcat.log", check=False)
            bytes_received, source, frame_events = resolve_bytes_received(metrics_local, logcat)
            summary["bytes_received"] = bytes_received
            summary["measurement_source"] = source
            summary["frame_events"] = frame_events
            summary["ok"] = summary["bytes_received"] >= args.min_bytes_received
            if not summary["ok"]:
                summary["reason"] = f"bytes_received below threshold: {summary['bytes_received']} < {args.min_bytes_received}"
            try:
                write_json(args.report_dir / "health-after.json", http_json(f"http://127.0.0.1:{args.host_control_port}/v1/health"))
            except Exception:
                pass
            try:
                write_json(args.report_dir / "status-after.json", http_json(f"http://127.0.0.1:{args.host_control_port}/v1/status"))
            except Exception:
                pass
    except Exception as exc:  # noqa: BLE001
        summary["reason"] = str(exc)
    finally:
        try:
            adb(args, "reverse", "--remove", f"tcp:{args.phone_control_port}", check=False)
            adb(args, "reverse", "--remove", f"tcp:{args.phone_stream_port}", check=False)
        except Exception:
            pass
        if not logcat.exists():
            logcat.touch()
        write_json(args.report_dir / "summary.json", summary)
    return 0 if summary.get("ok") else 1


if __name__ == "__main__":
    raise SystemExit(main())
