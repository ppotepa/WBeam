#!/usr/bin/env python3
"""
Cross-platform host fingerprint probe for WBeam.

Outputs JSON describing:
- OS/kernel/session/desktop environment
- key tool availability
- display stack details (X11/Wayland on Linux, desktop session info on Windows)
- XRandR providers/outputs snapshot when available
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple


def run_cmd(
    args: List[str], timeout: float = 3.0, env: Optional[Dict[str, str]] = None
) -> Tuple[int, str, str]:
    try:
        proc = subprocess.run(
            args,
            capture_output=True,
            text=True,
            timeout=timeout,
            env=env,
            check=False,
        )
    except Exception as exc:
        return 255, "", str(exc)
    return proc.returncode, proc.stdout.strip(), proc.stderr.strip()


def tool_exists(name: str) -> bool:
    return shutil.which(name) is not None


def detect_linux_display() -> str:
    display = os.environ.get("DISPLAY", "").strip()
    if display:
        return display
    x11_sock = Path("/tmp/.X11-unix")
    if not x11_sock.exists():
        return ""
    best = None
    for child in x11_sock.iterdir():
        m = re.match(r"^X(\d+)$", child.name)
        if not m:
            continue
        num = int(m.group(1))
        if best is None or num > best:
            best = num
    return f":{best}" if best is not None else ""


def resolve_xauthority(uid: int) -> str:
    env_xauth = os.environ.get("XAUTHORITY", "").strip()
    if env_xauth and Path(env_xauth).exists():
        return env_xauth

    home = Path.home() / ".Xauthority"
    if home.exists():
        return str(home)

    run_user = Path(f"/run/user/{uid}")
    if run_user.exists():
        candidates = sorted(p for p in run_user.glob("xauth_*") if p.is_file())
        if candidates:
            return str(candidates[-1])
    return ""


def parse_xrandr_providers(raw: str) -> List[Dict[str, object]]:
    providers: List[Dict[str, object]] = []
    for line in raw.splitlines():
        if "Provider" not in line or XRANDR_PROVIDER_NAME_TOKEN not in line:
            continue
        pid = ""
        for tok in line.replace(",", " ").split():
            if tok.startswith("0x"):
                pid = tok
                break
        name = (
            line.split(XRANDR_PROVIDER_NAME_TOKEN, 1)[1].strip()
            if XRANDR_PROVIDER_NAME_TOKEN in line
            else "unknown"
        )
        providers.append({"id": pid or "0x0", "name": name, "raw": line.strip()})
    return providers


def parse_xrandr_outputs(raw: str) -> List[Dict[str, object]]:
    outs: List[Dict[str, object]] = []
    for line in raw.splitlines():
        if not line or line.startswith((" ", "\t")):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        if parts[1] not in ("connected", "disconnected"):
            continue
        outs.append(
            {
                "name": parts[0],
                "connected": parts[1] == "connected",
                "raw": line.strip(),
            }
        )
    return outs


def linux_probe() -> Dict[str, object]:  # noqa: S3776
    uid = os.getuid()
    session_type = os.environ.get("XDG_SESSION_TYPE", "").strip().lower()
    wayland_display = os.environ.get("WAYLAND_DISPLAY", "").strip()
    display = detect_linux_display()

    desktop = (
        os.environ.get("XDG_CURRENT_DESKTOP", "").strip()
        or os.environ.get("DESKTOP_SESSION", "").strip()
        or "unknown"
    )
    remote = bool(
        os.environ.get("SSH_CONNECTION")
        or os.environ.get("SSH_TTY")
        or os.environ.get("SSH_CLIENT")
    )

    loginctl_info = collect_loginctl_session_info(uid)
    if loginctl_info.get("Remote", "").lower() == "yes":
        remote = True
    if desktop == "unknown":
        desktop = loginctl_info.get("Desktop", "") or desktop

    if not session_type:
        if wayland_display:
            session_type = "wayland"
        elif display:
            session_type = "x11"
        else:
            session_type = "unknown"

    if desktop == "unknown":
        desktop = detect_desktop_from_processes(desktop)

    xrandr: Dict[str, object] = {
        "attempted": False,
        "ok": False,
        "providers": [],
        "outputs": [],
        "error": "",
    }
    if tool_exists("xrandr") and display:
        xrandr = collect_xrandr_probe(uid, display)

    providers = xrandr.get("providers", []) if isinstance(xrandr, dict) else []
    outputs = xrandr.get("outputs", []) if isinstance(xrandr, dict) else []
    has_virtual_provider = any(
        isinstance(p, dict)
        and any(
            kw in str(p.get("name", "")).lower()
            for kw in ("evdi", "displaylink", "vkms", "virtual")
        )
        for p in providers
    )
    disconnected_outputs = [
        o for o in outputs if isinstance(o, dict) and not bool(o.get("connected"))
    ]
    likely_virtual_outputs = [
        o
        for o in disconnected_outputs
        if any(
            kw in str(o.get("name", "")).lower()
            for kw in ("virtual", "evdi", "vkms", "dummy", "dvi-i-", "dvi-d-")
        )
    ]

    evdi_module_path = Path("/lib/modules") / platform.release() / "updates/dkms/evdi.ko.zst"
    rc_modinfo, _, _ = run_cmd(["modinfo", "evdi"]) if tool_exists("modinfo") else (1, "", "")
    evdi_available = evdi_module_path.exists() or rc_modinfo == 0
    evdi_loaded = False
    if Path("/proc/modules").exists():
        try:
            evdi_loaded = any(line.startswith("evdi ") for line in Path("/proc/modules").read_text().splitlines())
        except Exception:
            evdi_loaded = False
    evdi_nodes: List[str] = []
    drm_sys = Path("/sys/class/drm")
    if drm_sys.exists() and Path("/dev/dri").exists():
        for entry in sorted(drm_sys.iterdir()):
            # Keep only cardN entries (exclude connectors like card0-DP-1).
            if not re.match(r"^card\d+$", entry.name):
                continue
            module_link = entry / "device/driver/module"
            module_name = ""
            try:
                if module_link.exists():
                    module_name = module_link.resolve().name.lower()
            except Exception:
                module_name = ""
            if module_name != "evdi":
                continue
            dev_node = Path("/dev/dri") / entry.name
            if dev_node.exists():
                evdi_nodes.append(str(dev_node))

    virtual_real_output_ready = bool(
        xrandr.get("ok")
        and len(providers) >= 2
        and has_virtual_provider
        and len(likely_virtual_outputs) > 0
    )
    if virtual_real_output_ready:
        virtual_reason = "ready"
    elif not xrandr.get("ok"):
        virtual_reason = "xrandr unavailable or unauthorized in current session"
    elif not evdi_available:
        virtual_reason = "evdi module is not installed (real-output backend unavailable)"
    elif not evdi_loaded:
        virtual_reason = "evdi module is installed but not loaded"
    elif len(providers) < 2:
        virtual_reason = "no virtual RandR provider is exposed to X11 (only primary provider visible)"
    elif not has_virtual_provider:
        virtual_reason = "no provider looks virtual/evdi/displaylink/vkms"
    elif len(likely_virtual_outputs) == 0:
        virtual_reason = "no candidate disconnected virtual output detected"
    else:
        virtual_reason = "missing virtual provider/output topology for real RandR output backend"

    return {
        "session": {
            "type": session_type,
            "desktop": desktop,
            "display": display or None,
            "wayland_display": wayland_display or None,
            "remote": remote,
            "xauthority": resolve_xauthority(uid) or None,
            "loginctl": loginctl_info,
        },
        "xrandr": xrandr,
        "evdi": {
            "module_available": evdi_available,
            "module_loaded": evdi_loaded,
            "drm_nodes": evdi_nodes,
        },
        "virtual_real_output_ready": virtual_real_output_ready,
        "virtual_real_output_reason": virtual_reason,
    }


def collect_loginctl_session_info(uid: int) -> Dict[str, str]:
    info: Dict[str, str] = {}
    if not tool_exists("loginctl"):
        return info
    sid = os.environ.get("XDG_SESSION_ID", "").strip() or find_loginctl_session_id(uid)
    if not sid:
        return info
    rc, out, _ = run_cmd(
        [
            "loginctl",
            "show-session",
            sid,
            "-p",
            "Type",
            "-p",
            "Name",
            "-p",
            "State",
            "-p",
            "Remote",
            "-p",
            "Display",
            "-p",
            "Desktop",
        ]
    )
    if rc != 0:
        return info
    for row in out.splitlines():
        if "=" in row:
            k, v = row.split("=", 1)
            info[k] = v
    return info


def find_loginctl_session_id(uid: int) -> str:
    rc_ls, out_ls, _ = run_cmd(["loginctl", "list-sessions", "--no-legend"])
    if rc_ls != 0:
        return ""
    uid_s = str(uid)
    for row in out_ls.splitlines():
        cols = row.split()
        if len(cols) >= 2 and cols[1] == uid_s:
            return cols[0]
    return ""


def detect_desktop_from_processes(default: str) -> str:
    # Best-effort process-based fallback for common desktop stacks.
    rc, _, _ = run_cmd(["pgrep", "-x", "plasmashell"])
    if rc == 0:
        return "KDE"
    rc_g, _, _ = run_cmd(["pgrep", "-x", "gnome-shell"])
    if rc_g == 0:
        return "GNOME"
    return default


def collect_xrandr_probe(uid: int, display: str) -> Dict[str, object]:
    probe: Dict[str, object] = {
        "attempted": True,
        "ok": False,
        "providers": [],
        "outputs": [],
        "error": "",
    }
    env = os.environ.copy()
    env["DISPLAY"] = display
    xauth = resolve_xauthority(uid)
    if xauth:
        env["XAUTHORITY"] = xauth
    rc_p, out_p, err_p = run_cmd(["xrandr", "--listproviders"], env=env, timeout=4.0)
    rc_q, out_q, err_q = run_cmd(["xrandr", "--query"], env=env, timeout=4.0)
    if rc_p == 0 and rc_q == 0:
        probe["ok"] = True
        probe["providers"] = parse_xrandr_providers(out_p)
        probe["outputs"] = parse_xrandr_outputs(out_q)
    else:
        probe["error"] = "; ".join(part for part in [err_p or out_p, err_q or out_q] if part)[:1200]
    return probe


def windows_probe() -> Dict[str, object]:
    details: Dict[str, object] = {
        "session": {
            "type": "windows",
            "desktop": os.environ.get("SESSIONNAME", "") or "unknown",
            "remote": bool(os.environ.get("SSH_CONNECTION")),
        },
        "powershell": {"attempted": False, "ok": False, "error": ""},
        "video_controllers": [],
    }
    if not tool_exists("powershell.exe") and not tool_exists("powershell"):
        details["powershell"]["error"] = "powershell not found"
        return details

    pwsh = "powershell.exe" if tool_exists("powershell.exe") else "powershell"
    details["powershell"]["attempted"] = True

    script = (
        "Get-CimInstance Win32_VideoController | "
        "Select-Object Name,AdapterRAM,DriverVersion,PNPDeviceID | "
        "ConvertTo-Json -Depth 3 -Compress"
    )
    rc, out, err = run_cmd([pwsh, "-NoProfile", "-Command", script], timeout=6.0)
    if rc == 0 and out:
        details["powershell"]["ok"] = True
        try:
            data = json.loads(out)
            details["video_controllers"] = data if isinstance(data, list) else [data]
        except Exception:
            details["powershell"]["error"] = "invalid json from powershell"
    else:
        details["powershell"]["error"] = err or out or f"exit={rc}"
    return details


def main() -> int:
    parser = argparse.ArgumentParser(description="WBeam host fingerprint probe")
    parser.add_argument("--pretty", action="store_true", help="Pretty-print JSON")
    args = parser.parse_args()

    system = platform.system().lower()
    payload: Dict[str, object] = {
        "host": {
            "hostname": socket.gethostname(),
            "platform_system": platform.system(),
            "platform_release": platform.release(),
            "platform_version": platform.version(),
            "machine": platform.machine(),
            "python": platform.python_version(),
        },
        "tools": {
            "adb": tool_exists("adb"),
            "xrandr": tool_exists("xrandr"),
            "cvt": tool_exists("cvt"),
            "Xvfb": tool_exists("Xvfb"),
            "systemctl": tool_exists("systemctl"),
            "loginctl": tool_exists("loginctl"),
            "powershell": tool_exists("powershell.exe") or tool_exists("powershell"),
        },
        "fingerprint_version": "1",
    }

    if system == "linux":
        payload["os"] = "linux"
        payload["details"] = linux_probe()
    elif system == "windows":
        payload["os"] = "windows"
        payload["details"] = windows_probe()
    else:
        payload["os"] = system or "unknown"
        payload["details"] = {"session": {"type": "unknown"}}

    if args.pretty:
        print(json.dumps(payload, indent=2, sort_keys=True))
    else:
        print(json.dumps(payload, separators=(",", ":"), sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
XRANDR_PROVIDER_NAME_TOKEN = "name:"
