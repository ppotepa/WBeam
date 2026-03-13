#!/usr/bin/env python3
import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


def http_json(url: str, method: str = "GET", timeout: float = 2.0):
    req = urllib.request.Request(url=url, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            if not body.strip():
                return {}
            return json.loads(body)
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"http {e.code} for {url}: {body}") from e
    except Exception as e:
        raise RuntimeError(f"request failed for {url}: {e}") from e


def endpoint(base: str, path: str, **query) -> str:
    q = {k: v for k, v in query.items() if v is not None}
    encoded = urllib.parse.urlencode(q)
    if encoded:
        return f"{base}{path}?{encoded}"
    return f"{base}{path}"


def adb_launch_main(serial: str) -> None:
    cmd = [
        "adb",
        "-s",
        serial,
        "shell",
        "am",
        "start",
        "-n",
        "com.wbeam/.MainActivity",
    ]
    out = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if out.returncode != 0:
        raise RuntimeError(f"adb launch failed: {out.stderr.strip()}")


def adb_reverse(serial: str, device_port: int, host_port: int) -> None:
    cmd = [
        "adb",
        "-s",
        serial,
        "reverse",
        f"tcp:{device_port}",
        f"tcp:{host_port}",
    ]
    out = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if out.returncode != 0:
        raise RuntimeError(f"adb reverse failed: {out.stderr.strip()}")


def adb_reverse_list(serial: str) -> list[tuple[int, int]]:
    cmd = ["adb", "-s", serial, "reverse", "--list"]
    last_err = ""
    for _ in range(6):
        out = subprocess.run(cmd, capture_output=True, text=True, check=False)
        if out.returncode == 0:
            break
        last_err = out.stderr.strip() or out.stdout.strip() or f"exit={out.returncode}"
        if "offline" in last_err.lower() or "not found" in last_err.lower():
            time.sleep(0.5)
            continue
        raise RuntimeError(f"adb reverse --list failed: {last_err}")
    else:
        raise RuntimeError(f"adb reverse --list failed after retries: {last_err}")

    mappings: list[tuple[int, int]] = []
    for raw in out.stdout.splitlines():
        line = raw.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) < 3:
            continue
        dev = parts[-2]
        host = parts[-1]
        if not dev.startswith("tcp:") or not host.startswith("tcp:"):
            continue
        try:
            dport = int(dev.split(":", 1)[1])
            hport = int(host.split(":", 1)[1])
        except Exception:
            continue
        mappings.append((dport, hport))
    return mappings


def ensure_reverse_sanity(serial: str, stream_port: int, control_port: int) -> None:
    mappings = set(adb_reverse_list(serial))
    expected = {(5000, stream_port), (5001, control_port)}
    if stream_port != 5000:
        expected.add((stream_port, stream_port))
    missing = sorted(expected - mappings)
    if missing:
        raise RuntimeError(
            f"reverse sanity failed; missing mappings={missing} actual={sorted(mappings)}"
        )


def create_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(
        description="Smoke test for WBeam X11 virtual monitor path"
    )
    ap.add_argument("--control-port", type=int, default=5001)
    ap.add_argument("--serial", required=True)
    ap.add_argument("--stream-port", type=int, default=5002)
    ap.add_argument("--display-mode", default="virtual_monitor")
    ap.add_argument("--timeout-sec", type=int, default=15)
    ap.add_argument(
        "--flow-timeout-sec",
        type=int,
        default=8,
        help="Time budget to observe non-zero frame/client flow after STREAMING",
    )
    ap.add_argument(
        "--require-client-present",
        action="store_true",
        help="Fail unless client metrics report present_fps >= 1.0",
    )
    ap.add_argument(
        "--require-min-recv-bps",
        type=int,
        default=0,
        help="Fail unless latest client recv_bps reaches at least this threshold",
    )
    ap.add_argument(
        "--launch-android-app",
        action="store_true",
        help="Launch com.wbeam/.MainActivity before start (ADB)",
    )
    ap.add_argument(
        "--adb-reverse",
        action="store_true",
        help="Prepare adb reverse device:5000 -> host:<stream_port> and device:5001 -> host:<control_port>",
    )
    ap.add_argument(
        "--require-reverse-sanity",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Require expected adb reverse mappings during run",
    )
    return ap


def prepare_android_side(args) -> None:
    if args.launch_android_app:
        adb_launch_main(args.serial)
        print("[smoke] android app launch requested")
        time.sleep(1.0)
    if args.adb_reverse:
        adb_reverse(args.serial, 5000, args.stream_port)
        adb_reverse(args.serial, 5001, args.control_port)
        if args.stream_port != 5000:
            adb_reverse(args.serial, args.stream_port, args.stream_port)
        print(
            f"[smoke] adb reverse configured: device 5000->{args.stream_port}, 5001->{args.control_port}"
        )


def wait_for_streaming(status_url: str, timeout_sec: int) -> str:
    deadline = time.time() + timeout_sec
    last_state = "unknown"
    while time.time() < deadline:
        st = http_json(status_url)
        last_state = str(st.get("state", "unknown")).upper()
        print(f"[smoke] state={last_state} run_id={st.get('run_id', 0)}")
        if last_state == "STREAMING":
            break
        time.sleep(0.7)
    return last_state


def parse_flow_metrics(metrics_payload: dict) -> tuple[int, float, int]:
    m = metrics_payload.get("metrics", {}) if isinstance(metrics_payload, dict) else {}
    frame_out = int(m.get("frame_out", 0) or 0)
    latest = m.get("latest_client_metrics")
    present_fps = 0.0
    recv_bps = 0
    if isinstance(latest, dict):
        try:
            present_fps = float(latest.get("present_fps", 0.0) or 0.0)
        except Exception:
            present_fps = 0.0
        try:
            recv_bps = int(latest.get("recv_bps", 0) or 0)
        except Exception:
            recv_bps = 0
    return frame_out, present_fps, recv_bps


def wait_for_flow(base: str, args) -> tuple[bool, bool, bool, dict]:
    flow_ok = False
    client_present_ok = False
    recv_bps_ok = args.require_min_recv_bps <= 0
    flow_deadline = time.time() + args.flow_timeout_sec
    metrics = {}
    while time.time() < flow_deadline:
        metrics = http_json(
            endpoint(base, "/metrics", serial=args.serial, stream_port=args.stream_port)
        )
        frame_out, present_fps, recv_bps = parse_flow_metrics(metrics)
        if present_fps >= 1.0:
            client_present_ok = True
        if recv_bps >= args.require_min_recv_bps:
            recv_bps_ok = True
        if args.require_client_present:
            if client_present_ok and recv_bps_ok:
                flow_ok = True
                break
        elif (frame_out > 0 or client_present_ok) and recv_bps_ok:
            flow_ok = True
            break
        time.sleep(0.7)
    return flow_ok, client_present_ok, recv_bps_ok, metrics


def validate_results(last_state: str, flow_ok: bool, client_present_ok: bool, recv_bps_ok: bool, args) -> int:
    if last_state != "STREAMING":
        print("[smoke] FAIL: did not reach STREAMING state within timeout")
        return 3
    if not flow_ok:
        print("[smoke] FAIL: reached STREAMING but observed no frame/client flow")
        return 4
    if args.require_client_present and not client_present_ok:
        print("[smoke] FAIL: host streams, but no client present_fps evidence")
        return 5
    if not recv_bps_ok:
        print(
            f"[smoke] FAIL: recv_bps did not reach threshold {args.require_min_recv_bps}"
        )
        return 6
    return 0


def main() -> int:
    args = create_parser().parse_args()

    base = f"http://127.0.0.1:{args.control_port}/v1"
    print(f"[smoke] base={base} serial={args.serial} stream_port={args.stream_port}")

    doctor_url = endpoint(
        base,
        "/virtual/doctor",
        serial=args.serial,
        stream_port=args.stream_port,
    )
    doctor = http_json(doctor_url)
    print("[smoke] virtual doctor:")
    print(json.dumps(doctor, indent=2, sort_keys=True))
    if not doctor.get("ok", False):
        print("[smoke] FAIL: virtual doctor is not ready")
        return 2

    prepare_android_side(args)

    start_url = endpoint(
        base,
        "/start",
        serial=args.serial,
        stream_port=args.stream_port,
        display_mode=args.display_mode,
    )
    _ = http_json(start_url, method="POST", timeout=3.0)
    print("[smoke] start requested")

    status_url = endpoint(
        base,
        "/status",
        serial=args.serial,
        stream_port=args.stream_port,
    )
    last_state = wait_for_streaming(status_url, args.timeout_sec)

    if args.require_reverse_sanity:
        ensure_reverse_sanity(args.serial, args.stream_port, args.control_port)

    flow_ok, client_present_ok, recv_bps_ok, metrics = wait_for_flow(base, args)

    print("[smoke] metrics snapshot:")
    print(json.dumps(metrics, indent=2, sort_keys=True))

    _ = http_json(
        endpoint(base, "/stop", serial=args.serial, stream_port=args.stream_port),
        method="POST",
        timeout=3.0,
    )
    print("[smoke] stop requested")

    result = validate_results(last_state, flow_ok, client_present_ok, recv_bps_ok, args)
    if result != 0:
        return result
    print("[smoke] OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
