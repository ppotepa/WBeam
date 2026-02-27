#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import queue
import re
import shlex
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path
from shutil import which
from typing import Any

CANONICAL_JSON_REL = "config/proto.json"
CANONICAL_CONF_REL = "config/proto.conf"
DEFAULT_PROFILES_REL = "config/profiles.json"

ENV_BLOCK = (
    "PROTO_",
    "RUN_",
    "WBEAM_",
    "HOST_IP",
    "SERIAL",
    "GRADLEW",
    "CARGO_BIN",
    "QEMU_",
    "ANDROID_EMULATOR_BIN",
    "ANDROID_LOG_FILE",
)

DEFAULTS: dict[str, str] = {
    "RUN_BACKEND": "rust",
    "RUN_DEVICE": "adb",
    "GRADLEW": "",
    "SERIAL": "",
    "HOST_IP": "",
    "PROTO_ANDROID_BUILD_TYPE": "debug",
    "PROTO_ADB_RESET_ON_START": "1",
    "PROTO_ADB_CMD_TIMEOUT_SECS": "12",
    "PROTO_ADB_SHELL_TIMEOUT_SECS": "8",
    "PROTO_ADB_INSTALL_TIMEOUT_SECS": "90",
    "PROTO_ADB_INSTALL_HEARTBEAT_SECS": "10",
    "PROTO_ADB_PUSH": "1",
    "PROTO_H264": "1",
    "PROTO_H264_REORDER": "0",
    "PROTO_FORCE_JAVA_FALLBACK": "0",
    "PROTO_CAPTURE_SIZE": "",
    "PROTO_PROFILE": "",
    "PROTO_PROFILE_FILE": DEFAULT_PROFILES_REL,
}


class RunError(RuntimeError):
    pass


def log(msg: str) -> None:
    print(f"[proto] {msg}", flush=True)


def as_bool(v: str, default: bool = False) -> bool:
    val = str(v).strip().lower()
    if val in {"1", "true", "yes", "on"}:
        return True
    if val in {"0", "false", "no", "off"}:
        return False
    return default


def as_int(v: str, default: int) -> int:
    try:
        return int(str(v).strip())
    except (TypeError, ValueError):
        return default


def run_cmd(
    cmd: list[str],
    *,
    cwd: Path | None = None,
    timeout: int | None = None,
    capture: bool = False,
    allow_fail: bool = True,
) -> subprocess.CompletedProcess[str]:
    kwargs: dict[str, Any] = {"text": True, "cwd": str(cwd) if cwd else None, "env": os.environ.copy()}
    if capture:
        kwargs["stdout"] = subprocess.PIPE
        kwargs["stderr"] = subprocess.STDOUT

    try:
        cp = subprocess.run(cmd, timeout=timeout, **kwargs)
    except subprocess.TimeoutExpired as exc:
        if allow_fail:
            out = ""
            if exc.stdout is not None:
                out += exc.stdout.decode() if isinstance(exc.stdout, bytes) else str(exc.stdout)
            if exc.stderr is not None:
                out += exc.stderr.decode() if isinstance(exc.stderr, bytes) else str(exc.stderr)
            return subprocess.CompletedProcess(cmd, 124, stdout=out)
        raise RunError(f"command timed out after {timeout}s: {' '.join(cmd)}") from exc

    if not allow_fail and cp.returncode != 0:
        out = (cp.stdout or "").strip()
        suffix = f"\n{out}" if out else ""
        raise RunError(f"command failed ({cp.returncode}): {' '.join(cmd)}{suffix}")

    return cp


def parse_conf_value(v: str) -> str:
    v = v.strip()
    if len(v) >= 2 and ((v[0] == '"' and v[-1] == '"') or (v[0] == "'" and v[-1] == "'")):
        return v[1:-1]
    return v


def normalize_key(key: str) -> str:
    k = re.sub(r"[^A-Za-z0-9_]+", "_", str(key)).strip("_").upper()
    if not k:
        raise RunError(f"invalid config key: {key!r}")
    if k[0].isdigit():
        k = "_" + k
    return k


def scalar_to_str(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return ",".join(scalar_to_str(x) for x in value)
    return json.dumps(value, separators=(",", ":"))


def flatten_json(node: dict[str, Any], out: dict[str, str], prefix: str = "") -> None:
    for raw_k, raw_v in node.items():
        key = normalize_key(raw_k)
        full = f"{prefix}_{key}" if prefix else key
        if isinstance(raw_v, dict):
            flatten_json(raw_v, out, full)
        else:
            out[full] = scalar_to_str(raw_v)


def resolve_config_path(root: Path, raw: str | None) -> Path:
    if raw:
        return Path(raw).resolve()
    return (root / CANONICAL_JSON_REL).resolve()


def render_conf(cfg: dict[str, str], source_json: Path) -> str:
    lines = [
        f"# Generated from {source_json}",
        "# Do not edit manually; edit proto.json or apply profile and re-run.",
    ]
    for key in sorted(cfg):
        val = cfg[key]
        if re.search(r"\s", val) or val == "":
            lines.append(f'{key}="{val}"')
        else:
            lines.append(f"{key}={val}")
    return "\n".join(lines) + "\n"


def sync_conf_from_json(root: Path, json_cfg: dict[str, str]) -> None:
    conf_path = (root / CANONICAL_CONF_REL).resolve()
    json_path = (root / CANONICAL_JSON_REL).resolve()
    rendered = render_conf(json_cfg, json_path)
    old = conf_path.read_text(encoding="utf-8") if conf_path.exists() else ""
    if old != rendered:
        conf_path.write_text(rendered, encoding="utf-8")
        log(f"synced derived conf from canonical json: {conf_path}")


def known_keys_from_canonical(root: Path) -> set[str]:
    known = set(DEFAULTS.keys())
    canonical = (root / CANONICAL_JSON_REL).resolve()
    if canonical.exists():
        raw = load_config(canonical)
        known.update(raw.keys())
    known.update({"PROTO_PROFILE", "PROTO_PROFILE_FILE"})
    return known


def validate_known_keys(cfg: dict[str, str], known: set[str], source: Path) -> None:
    unknown = sorted(k for k in cfg.keys() if k not in known)
    if not unknown:
        return
    preview = ", ".join(unknown[:12])
    suffix = "" if len(unknown) <= 12 else f" (+{len(unknown) - 12} more)"
    raise RunError(
        f"unknown config keys in {source}: {preview}{suffix}. "
        f"Use canonical {CANONICAL_JSON_REL} and keep keys explicit."
    )


def load_profiles(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise RunError(f"profile file not found: {path}")
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise RunError(f"invalid profile JSON: {path} ({exc})") from exc
    if not isinstance(raw, dict):
        raise RunError(f"profile file root must be object: {path}")
    profiles = raw.get("profiles")
    if not isinstance(profiles, dict):
        raise RunError(f"profile file missing object key 'profiles': {path}")
    return raw


def normalize_profile_mapping(raw: Any, *, section: str) -> dict[str, str]:
    if raw is None:
        return {}
    if not isinstance(raw, dict):
        raise RunError(f"profile section '{section}' must be an object")
    out: dict[str, str] = {}
    for k, v in raw.items():
        nk = normalize_key(k)
        out[nk] = scalar_to_str(v)
    return out


def apply_profile(
    cfg: dict[str, str],
    root: Path,
    explicit_profile: str | None = None,
) -> tuple[dict[str, str], dict[str, str], dict[str, str], str]:
    profile_name = (explicit_profile or cfg.get("PROTO_PROFILE", "")).strip()
    if not profile_name:
        return cfg, {}, {}, ""

    profile_file_raw = (cfg.get("PROTO_PROFILE_FILE", DEFAULT_PROFILES_REL) or "").strip()
    profile_file = Path(profile_file_raw)
    if not profile_file.is_absolute():
        profile_file = (root / profile_file).resolve()

    profiles_doc = load_profiles(profile_file)
    profiles = profiles_doc["profiles"]
    profile = profiles.get(profile_name)
    if not isinstance(profile, dict):
        available = ", ".join(sorted(str(k) for k in profiles.keys()))
        raise RunError(f"profile '{profile_name}' not found in {profile_file}; available: {available}")

    values_map = normalize_profile_mapping(profile.get("values"), section="values")
    quality_map = normalize_profile_mapping(profile.get("quality"), section="quality")
    latency_map = normalize_profile_mapping(profile.get("latency"), section="latency")

    merged = dict(cfg)
    # Apply in deterministic order.
    merged.update(values_map)
    merged.update(quality_map)
    merged.update(latency_map)
    merged["PROTO_PROFILE"] = profile_name
    merged["PROTO_PROFILE_FILE"] = str(profile_file)
    return merged, quality_map, latency_map, str(profile_file)


def write_effective_config(path: Path, cfg: dict[str, str], source_path: Path) -> None:
    payload: dict[str, Any] = {
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "source_config": str(source_path),
    }
    for k in sorted(cfg.keys()):
        payload[k] = cfg[k]
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    log(f"effective runtime config: {path}")


def load_config(path: Path) -> dict[str, str]:
    if not path.exists():
        log(f"config file not found: {path} (using built-in defaults)")
        return {}

    if path.suffix.lower() == ".json":
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise RunError(f"invalid JSON config: {path} ({exc})") from exc
        if not isinstance(raw, dict):
            raise RunError("JSON config root must be an object")
        out: dict[str, str] = {}
        flatten_json(raw, out)
        log(f"loaded json config: {path}")
        return out

    out: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        k = k.strip()
        if re.match(r"^[A-Za-z_][A-Za-z0-9_]*$", k):
            out[k] = parse_conf_value(v)
    log(f"loaded config: {path}")
    return out


def block_runtime_env_overrides() -> None:
    bad = [name for name in os.environ if any(name == p or name.startswith(p) for p in ENV_BLOCK)]
    if bad:
        log("runtime environment overrides are not allowed. Move these to config file:")
        for name in sorted(bad):
            print(f"[proto] {name}")
        raise RunError("blocked runtime env overrides")


def ensure_session_env() -> None:
    if not os.environ.get("XDG_RUNTIME_DIR"):
        guess = f"/run/user/{os.getuid()}"
        if Path(guess).is_dir():
            os.environ["XDG_RUNTIME_DIR"] = guess
            log(f"XDG_RUNTIME_DIR not set; using {guess}")

    xdg = os.environ.get("XDG_RUNTIME_DIR", "")
    if not os.environ.get("DBUS_SESSION_BUS_ADDRESS") and xdg and (Path(xdg) / "bus").exists():
        os.environ["DBUS_SESSION_BUS_ADDRESS"] = f"unix:path={xdg}/bus"
        log(f"DBUS_SESSION_BUS_ADDRESS not set; using {os.environ['DBUS_SESSION_BUS_ADDRESS']}")

    if not os.environ.get("WAYLAND_DISPLAY") and xdg and (Path(xdg) / "wayland-0").exists():
        os.environ["WAYLAND_DISPLAY"] = "wayland-0"
        log("WAYLAND_DISPLAY not set; using wayland-0")


def ensure_java_home() -> None:
    if os.environ.get("JAVA_HOME"):
        return
    for path in (Path("/usr/lib/jvm/java-17-openjdk-amd64"), Path("/usr/lib/jvm/java-17-openjdk")):
        if path.is_dir():
            os.environ["JAVA_HOME"] = str(path)
            os.environ["PATH"] = f"{path / 'bin'}{os.pathsep}{os.environ.get('PATH', '')}"
            log(f"JAVA_HOME not set; using {path}")
            return


def cleanup_stale_local_helpers() -> None:
    if which("pkill") is None:
        return
    patterns = [
        "stream_wayland_portal_h264.py",
        "proto-host-image",
    ]
    for pattern in patterns:
        cp = run_cmd(["pkill", "-f", pattern], capture=True, allow_fail=True)
        if cp.returncode == 0:
            log(f"killed stale local helper(s): {pattern}")


def adb_prefix(serial: str) -> list[str]:
    return ["adb", "-s", serial] if serial else ["adb"]


def adb_cmd(
    serial: str,
    *args: str,
    timeout: int | None = None,
    capture: bool = False,
    allow_fail: bool = True,
) -> subprocess.CompletedProcess[str]:
    return run_cmd(adb_prefix(serial) + list(args), timeout=timeout, capture=capture, allow_fail=allow_fail)


def adb_shell(
    serial: str,
    shell_timeout_s: int,
    *args: str,
    timeout: int | None = None,
    capture: bool = False,
    allow_fail: bool = True,
) -> subprocess.CompletedProcess[str]:
    return adb_cmd(
        serial,
        "shell",
        *args,
        timeout=timeout if timeout is not None else shell_timeout_s,
        capture=capture,
        allow_fail=allow_fail,
    )


def adb_devices(cmd_timeout_s: int) -> list[tuple[str, str]]:
    cp = run_cmd(["adb", "devices"], timeout=cmd_timeout_s, capture=True, allow_fail=True)
    out: list[tuple[str, str]] = []
    for line in (cp.stdout or "").splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices"):
            continue
        parts = line.split()
        if len(parts) >= 2:
            out.append((parts[0], parts[1]))
    return out


def pick_serial(cmd_timeout_s: int, shell_timeout_s: int) -> str:
    preferred = ""
    first_physical = ""
    first_any = ""

    for serial, state in adb_devices(cmd_timeout_s):
        if state != "device":
            continue
        if not first_any:
            first_any = serial
        if not serial.startswith("emulator-") and not first_physical:
            first_physical = serial

        model_cp = run_cmd(
            ["adb", "-s", serial, "shell", "getprop", "ro.product.model"],
            timeout=shell_timeout_s,
            capture=True,
            allow_fail=True,
        )
        model = (model_cp.stdout or "").strip()
        if "S6000" in model or "Lenovo" in model:
            preferred = serial
            break

    return preferred or first_physical or first_any


def wait_transport(serial: str, cmd_timeout_s: int, shell_timeout_s: int, timeout_s: int) -> bool:
    deadline = time.time() + timeout_s
    while time.time() <= deadline:
        cp = adb_cmd(serial, "get-state", timeout=cmd_timeout_s, capture=True, allow_fail=True)
        if cp.returncode == 0 and (cp.stdout or "").strip() == "device":
            probe = adb_shell(serial, shell_timeout_s, "true", allow_fail=True)
            if probe.returncode == 0:
                return True
        time.sleep(1)
    return False


def print_adb_snapshot(reason: str, cmd_timeout_s: int) -> None:
    log(reason)
    cp = run_cmd(["adb", "devices"], timeout=cmd_timeout_s, capture=True, allow_fail=True)
    for line in (cp.stdout or "").splitlines():
        print(f"[proto] adb: {line}")


def build_apk(root: Path, cfg: dict[str, str]) -> Path:
    build_type = cfg.get("PROTO_ANDROID_BUILD_TYPE", "debug").strip().lower()
    if build_type not in {"debug", "release"}:
        raise RunError(f"unknown PROTO_ANDROID_BUILD_TYPE={build_type} (expected: debug|release)")

    front_dir = root / "front"
    gradlew_raw = (cfg.get("GRADLEW", "") or str(front_dir / "gradlew")).strip()
    gradlew_path = Path(gradlew_raw)
    if not gradlew_path.is_absolute():
        gradlew_path = (root / gradlew_path).resolve()

    gradlew = str(gradlew_path)
    if not gradlew_path.exists() or not os.access(gradlew, os.X_OK):
        fallback = which("gradle")
        if not fallback:
            raise RunError(f"gradlew not found at {gradlew} and no system gradle in PATH")
        log(f"gradlew not found at {gradlew}; using system gradle")
        gradlew = fallback

    if build_type == "debug":
        log("building APK (debug)…")
        run_cmd([gradlew, ":app:assembleDebug"], cwd=front_dir, allow_fail=False)
        apk = front_dir / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    else:
        log("building APK (release)…")
        run_cmd([gradlew, ":app:assembleRelease"], cwd=front_dir, allow_fail=False)
        apk = front_dir / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"

    if not apk.exists():
        raise RunError(f"APK not found at {apk}")
    return apk


def capture_install(serial: str, args: list[str], timeout_s: int, heartbeat_s: int) -> tuple[int, str]:
    proc = subprocess.Popen(
        adb_prefix(serial) + ["install", *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    start = time.time()
    next_hb = start + max(1, heartbeat_s)
    out_parts: list[str] = []
    lines_q: queue.Queue[str] = queue.Queue()
    reader_done = threading.Event()

    def _reader() -> None:
        try:
            if proc.stdout is None:
                return
            for line in proc.stdout:
                lines_q.put(line)
        finally:
            reader_done.set()

    reader_thread = threading.Thread(target=_reader, daemon=True)
    reader_thread.start()

    success_seen = False

    try:
        while proc.poll() is None:
            while True:
                try:
                    line = lines_q.get_nowait()
                except queue.Empty:
                    break
                out_parts.append(line)
                if "Success" in line:
                    success_seen = True

            if success_seen:
                # adb on some devices can hang after printing "Success".
                # Treat this as successful install and stop waiting.
                # Do not hard-kill immediately: that can transiently break next adb command
                # with "adb: error: closed" on some devices.
                def _reap_later(p: subprocess.Popen[str]) -> None:
                    try:
                        p.wait(timeout=30.0)
                    except subprocess.TimeoutExpired:
                        p.kill()
                        p.wait()

                threading.Thread(target=_reap_later, args=(proc,), daemon=True).start()
                reader_done.wait(timeout=0.5)
                while True:
                    try:
                        out_parts.append(lines_q.get_nowait())
                    except queue.Empty:
                        break
                return 0, "".join(out_parts)

            now = time.time()
            if now - start >= timeout_s:
                proc.kill()
                proc.wait()
                reader_done.wait(timeout=1.0)
                while True:
                    try:
                        out_parts.append(lines_q.get_nowait())
                    except queue.Empty:
                        break
                return 124, "".join(out_parts)
            if heartbeat_s > 0 and now >= next_hb:
                log(f"adb install in progress ({' '.join(args)}), elapsed={int(now - start)}s")
                next_hb = now + heartbeat_s
            time.sleep(0.25)
    except KeyboardInterrupt:
        proc.kill()
        proc.wait()
        reader_done.wait(timeout=1.0)
        raise RunError("interrupted during adb install") from None

    reader_done.wait(timeout=1.0)
    while True:
        try:
            out_parts.append(lines_q.get_nowait())
        except queue.Empty:
            break
    return proc.returncode or 0, "".join(out_parts)


def install_apk(serial: str, apk: Path, cfg: dict[str, str]) -> None:
    timeout_s = as_int(cfg.get("PROTO_ADB_INSTALL_TIMEOUT_SECS", "90"), 90)
    heartbeat_s = as_int(cfg.get("PROTO_ADB_INSTALL_HEARTBEAT_SECS", "10"), 10)
    cmd_timeout_s = as_int(cfg.get("PROTO_ADB_CMD_TIMEOUT_SECS", "12"), 12)
    shell_timeout_s = as_int(cfg.get("PROTO_ADB_SHELL_TIMEOUT_SECS", "8"), 8)

    attempts = [
        ("--no-streaming -r", ["--no-streaming", "-r", str(apk)]),
        ("-r", ["-r", str(apk)]),
        ("plain install", [str(apk)]),
    ]

    log(f"installing APK to device {serial}…")

    for idx, (label, args) in enumerate(attempts, start=1):
        if idx == 2:
            run_cmd(["adb", "kill-server"], allow_fail=True)
            run_cmd(["adb", "start-server"], allow_fail=True)
            if not wait_transport(serial, cmd_timeout_s, shell_timeout_s, 20):
                print_adb_snapshot("adb transport still not ready after restart", cmd_timeout_s)
                continue
        if idx == 3:
            adb_cmd(serial, "uninstall", "com.proto.demo", allow_fail=True)

        log(f"adb install attempt {idx}/3: {label} (timeout={timeout_s}s)")
        rc, out = capture_install(serial, args, timeout_s, heartbeat_s)
        if "Success" in out:
            if rc == 124:
                log(f"adb install output contains Success despite timeout (attempt {idx}); accepting install")
            return

        if rc == 124:
            log(f"adb install timed out after {timeout_s}s (attempt {idx})")
        else:
            log(f"adb install {label} failed (attempt {idx})")
        if out.strip():
            print(out)

    raise RunError("APK install failed")


def detect_host_ip() -> str:
    cp = run_cmd(["ip", "-4", "-o", "addr", "show", "scope", "global"], capture=True, allow_fail=True)
    preferred = ""
    fallback = ""
    for line in (cp.stdout or "").splitlines():
        parts = line.split()
        if len(parts) < 4:
            continue
        ip = parts[3].split("/")[0]
        if ip.startswith("192.168.42.") and not preferred:
            preferred = ip
        if not fallback:
            fallback = ip
    return preferred or fallback or "192.168.42.170"


def prepare_transport(serial: str, cfg: dict[str, str], shell_timeout_s: int) -> str:
    host_ip = cfg.get("HOST_IP", "").strip() or detect_host_ip()
    if not cfg.get("HOST_IP", "").strip():
        log(f"HOST_IP not set; detected {host_ip}")

    app_host_ip = host_ip
    reverse = adb_cmd(serial, "reverse", "tcp:5005", "tcp:5005", capture=True, allow_fail=True)
    reverse_out = (reverse.stdout or "").strip()
    if reverse.returncode != 0 and "error: closed" in reverse_out:
        wait_transport(
            serial,
            as_int(cfg.get("PROTO_ADB_CMD_TIMEOUT_SECS", "12"), 12),
            shell_timeout_s,
            8,
        )
        reverse = adb_cmd(serial, "reverse", "tcp:5005", "tcp:5005", capture=True, allow_fail=True)

    if reverse.returncode == 0:
        app_host_ip = "127.0.0.1"
        log("adb reverse enabled: device tcp:5005 -> host tcp:5005")
    else:
        log(f"adb reverse unavailable; using HOST_IP={app_host_ip}")

    if as_bool(cfg.get("PROTO_ADB_PUSH", "1"), True):
        adb_cmd(serial, "forward", "--remove", "tcp:5006", capture=True, allow_fail=True)
        adb_cmd(serial, "forward", "tcp:5006", "tcp:5006", capture=True, allow_fail=True)
        log("adb forward enabled: host tcp:5006 -> device tcp:5006")

    model = (adb_shell(serial, shell_timeout_s, "getprop", "ro.product.model", capture=True, allow_fail=True).stdout or "").lower()
    if "sdk" in model and app_host_ip != "127.0.0.1":
        app_host_ip = "10.0.2.2"
        log("detected emulator; using host_ip=10.0.2.2")

    return app_host_ip


def start_app(serial: str, cfg: dict[str, str], app_host_ip: str, shell_timeout_s: int) -> None:
    adb_cmd(serial, "logcat", "-c", allow_fail=True)
    log("launching app on device…")

    adb_shell(serial, shell_timeout_s, "am", "force-stop", "com.proto.demo", allow_fail=True)

    adb_push = as_bool(cfg.get("PROTO_ADB_PUSH", "1"), True)
    h264 = as_bool(cfg.get("PROTO_H264", "1"), True)

    am = [
        "am",
        "start",
        "-n",
        "com.proto.demo/.MainActivity",
        "--es",
        "host_ip",
        app_host_ip,
        "--ez",
        "adb_push",
        "true" if adb_push else "false",
        "--ez",
        "h264",
        "true" if h264 else "false",
    ]

    capture_size = cfg.get("PROTO_CAPTURE_SIZE", "").strip()
    if capture_size:
        am += ["--es", "capture_size", capture_size]
    if as_bool(cfg.get("PROTO_FORCE_JAVA_FALLBACK", "0"), False):
        am += ["--ez", "force_java_fallback", "true"]
    if as_bool(cfg.get("PROTO_H264_REORDER", "0"), False):
        am += ["--ez", "h264_reorder", "true"]

    start_timeout = max(12, shell_timeout_s * 2)
    cp = adb_shell(serial, shell_timeout_s, *am, timeout=start_timeout, capture=True, allow_fail=True)
    if cp.returncode != 0:
        out = (cp.stdout or "").strip()
        if "Starting: Intent" in out:
            log("WARNING: am start returned non-zero but Intent launch message is present; continuing")
            return
        raise RunError(f"failed to launch app via am start{': ' + out if out else ''}")


def start_rust_backend(root: Path, config_path: Path) -> int:
    cmd = ["cargo", "run", "--release", "--", "--config", str(config_path)]
    log("starting rust backend (backend loads config itself)…")
    log("backend cmd: " + " ".join(shlex.quote(x) for x in cmd))

    proc = subprocess.Popen(cmd, cwd=root / "host", text=True)

    def forward(signum: int, _frame: Any) -> None:
        if proc.poll() is None:
            proc.send_signal(signum)
        raise SystemExit(130)

    signal.signal(signal.SIGINT, forward)
    signal.signal(signal.SIGTERM, forward)

    try:
        return proc.wait()
    finally:
        if proc.poll() is None:
            proc.kill()


def parse_args(argv: list[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(usage="./run.sh [--config path]")
    p.add_argument("--config", default=None)
    p.add_argument("--profile", default=None, help="Override profile name for this run.")
    p.add_argument(
        "--prepare-only",
        action="store_true",
        help="Build/install/launch app and prepare adb transport, then exit without starting backend.",
    )
    return p.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    root = Path(__file__).resolve().parent
    config_path = resolve_config_path(root, args.config)

    try:
        block_runtime_env_overrides()
        if args.config is None:
            log(f"using canonical config: {config_path}")
        cfg = dict(DEFAULTS)
        loaded_cfg = load_config(config_path)
        cfg.update(loaded_cfg)

        known_keys = known_keys_from_canonical(root)
        validate_known_keys(cfg, known_keys, config_path)

        cfg, profile_quality, profile_latency, profile_file = apply_profile(cfg, root, explicit_profile=args.profile)
        if cfg.get("PROTO_PROFILE", "").strip():
            log(
                f"profile applied: {cfg['PROTO_PROFILE']} "
                f"(quality={len(profile_quality)} keys, latency={len(profile_latency)} keys, file={profile_file})"
            )
            if profile_quality:
                log("profile quality keys: " + ", ".join(sorted(profile_quality.keys())))
            if profile_latency:
                log("profile latency keys: " + ", ".join(sorted(profile_latency.keys())))
            validate_known_keys(cfg, known_keys | set(profile_quality.keys()) | set(profile_latency.keys()), config_path)

        if config_path == (root / CANONICAL_JSON_REL).resolve():
            sync_conf_from_json(root, loaded_cfg)

        effective_path = (Path("/tmp") / "proto-effective-config-runner.json").resolve()
        write_effective_config(effective_path, cfg, config_path)

        if str(cfg.get("RUN_DEVICE", "adb")).strip().lower() != "adb":
            raise RunError("run.py supports RUN_DEVICE=adb only")
        if str(cfg.get("RUN_BACKEND", "rust")).strip().lower() != "rust":
            raise RunError("run.py supports RUN_BACKEND=rust only")

        ensure_session_env()
        ensure_java_home()
        cleanup_stale_local_helpers()

        cmd_timeout_s = as_int(cfg.get("PROTO_ADB_CMD_TIMEOUT_SECS", "12"), 12)
        shell_timeout_s = as_int(cfg.get("PROTO_ADB_SHELL_TIMEOUT_SECS", "8"), 8)

        if as_bool(cfg.get("PROTO_ADB_RESET_ON_START", "1"), True):
            log("startup: resetting adb server")
            run_cmd(["adb", "kill-server"], allow_fail=True)
            run_cmd(["adb", "start-server"], allow_fail=True)

        serial = str(cfg.get("SERIAL", "")).strip()
        if not serial:
            serial = pick_serial(cmd_timeout_s, shell_timeout_s)
            if not serial:
                raise RunError("no adb device detected")
            log(f"SERIAL not set; selected device {serial}")

        if not wait_transport(serial, cmd_timeout_s, shell_timeout_s, 20):
            print_adb_snapshot("adb transport is not ready", cmd_timeout_s)
            raise RunError("adb transport is not ready")

        log(f"preflight: stopping stale app (timeout={shell_timeout_s}s)")
        pre = adb_shell(serial, shell_timeout_s, "am", "force-stop", "com.proto.demo", allow_fail=True)
        if pre.returncode == 124:
            log("WARNING: preflight force-stop timed out, continuing")
        elif pre.returncode != 0:
            log("WARNING: preflight force-stop failed, continuing")

        apk = build_apk(root, cfg)
        install_apk(serial, apk, cfg)
        if not wait_transport(serial, cmd_timeout_s, shell_timeout_s, 12):
            log("WARNING: adb transport is unstable right after install, continuing")
        app_host_ip = prepare_transport(serial, cfg, shell_timeout_s)
        start_app(serial, cfg, app_host_ip, shell_timeout_s)

        if args.prepare_only:
            log("prepare-only: device/app ready; skipping backend start")
            return 0

        return start_rust_backend(root, effective_path)

    except KeyboardInterrupt:
        log("interrupted")
        return 130
    except RunError as exc:
        log(str(exc))
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
