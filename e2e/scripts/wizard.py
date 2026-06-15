#!/usr/bin/env python3
"""
WBeam E2E Wizard.

Flow:
WELCOME -> SELECTION -> BACKEND -> READINESS -> EXECUTION -> REPORT
"""
from __future__ import annotations

import curses
import json
import os
import queue
import subprocess
import sys
import threading
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parents[1]
E2E_DIR = ROOT / "e2e"
MATRIX_PATH = E2E_DIR / "matrix.json"
RUNNER = SCRIPT_DIR / "runner.py"
DOWNLOAD_SCRIPT = SCRIPT_DIR / "download_isos.py"

CP_HEADER = 1
CP_BUTTON = 2
CP_SELECTED = 3
CP_OK = 4
CP_MISSING = 5
CP_DIM = 6
CP_PROGRESS = 7
CP_RATIONALE = 8
CP_BORDER = 9

DISTROS = ["fedora-43", "ubuntu-24.04", "debian-12"]
DISTRO_OPTIONS = DISTROS
TIER_VALUES = ["smoke", "full"]
BACKEND_OPTIONS = ["wayland_portal", "evdi", "x11_gst"]
BACKENDS = BACKEND_OPTIONS
BACKEND_LABELS = {
    "wayland_portal": "Wayland Portal",
    "evdi": "EVDI / VDI",
    "x11_gst": "X11 GStreamer fallback",
}
ADB_DEVICE_STATES = {"device", "unauthorized", "offline", "recovery", "sideload"}
ISO_FILENAMES = {
    "fedora-43": "fedora-43-netinst.iso",
    "ubuntu-24.04": "ubuntu-24.04-desktop.iso",
    "debian-12": "debian-12-netinst.iso",
}
BACKEND_SESSION_DEFAULTS = {
    "wayland_portal": "gnome-wayland",
    "evdi": "gnome-wayland",
    "x11_gst": "gnome-xorg",
}


def init_colors() -> None:
    curses.start_color()
    curses.use_default_colors()
    curses.init_pair(CP_HEADER, curses.COLOR_WHITE, curses.COLOR_BLUE)
    curses.init_pair(CP_BUTTON, curses.COLOR_BLACK, curses.COLOR_WHITE)
    curses.init_pair(CP_SELECTED, curses.COLOR_BLACK, curses.COLOR_CYAN)
    curses.init_pair(CP_OK, curses.COLOR_GREEN, -1)
    curses.init_pair(CP_MISSING, curses.COLOR_RED, -1)
    curses.init_pair(CP_DIM, 8, -1)
    curses.init_pair(CP_PROGRESS, curses.COLOR_CYAN, -1)
    curses.init_pair(CP_RATIONALE, curses.COLOR_YELLOW, -1)
    curses.init_pair(CP_BORDER, 8, -1)


class ScreenId:
    WELCOME = "welcome"
    SELECTION = "selection"
    BACKEND = "backend"
    READINESS = "readiness"
    EXECUTION = "execution"
    REPORT = "report"
    HELP = "help"


@dataclass
class ScenarioChoice:
    distro: str
    session: str
    backend: str
    scenario_id: str
    tier: str
    stability: str
    device_policy: str
    requires_desktop: bool
    requires_evdi: bool
    requires_portal: bool


@dataclass
class AssetReadiness:
    distro: str
    session: str
    iso_status: str
    iso_path: str
    iso_action: str
    l0_status: str
    l0_path: str
    l0_action: str
    l1_status: str
    l1_path: str
    l1_action: str
    portal_required: bool = False
    portal_consented_status: str = "not_required"
    portal_consented_path: str = ""
    portal_consented_action: str = ""
    portal_status: str = "not_required"
    portal_path: str = ""
    portal_action: str = "not_required"
    action: str = ""
    shared_backends: list[str] = field(default_factory=list)
    shared_by_scenarios: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    next_action: str = ""


@dataclass
class PlannedAction:
    id: str
    title: str
    kind: str
    distro: str = ""
    session: str = ""
    backend: str = ""
    scenario_id: str = ""
    scenario_ids: list[str] = field(default_factory=list)
    command: list[str] = field(default_factory=list)
    rationale: str = ""
    expected_artifacts: list[str] = field(default_factory=list)
    log_path: str = ""
    runner_report_dir: str = ""


@dataclass
class UiRow:
    label: str
    status: str
    detail: str = ""
    reason_code: str = ""
    next_action: str = ""


@dataclass
class ScenarioReadinessView:
    status: str
    blocker_reason_code: str
    blocker_summary: str
    next_action: str
    selected_tests: list[UiRow] = field(default_factory=list)
    required_assets: list[UiRow] = field(default_factory=list)
    environment_gates: list[UiRow] = field(default_factory=list)
    manual_gates: list[UiRow] = field(default_factory=list)
    execution_plan: list[UiRow] = field(default_factory=list)
    out_of_scope: list[UiRow] = field(default_factory=list)


@dataclass
class AdbHostStatus:
    status: str = "missing"
    serial: str | None = None
    summary: str = "adb not checked"


@dataclass
class WizardState:
    screen: str = ScreenId.WELCOME
    previous_screen: str = ScreenId.WELCOME
    cursor: int = 0
    run_id: str = ""
    run_dir: Path | None = None
    adb_connected: bool = False
    adb_required: bool = False
    adb_status: AdbHostStatus = field(default_factory=AdbHostStatus)
    adb_last_refresh: float = 0.0
    kvm_ready: bool = False
    selected_distros: list[str] = field(default_factory=lambda: ["fedora-43"])
    selected_tier: str = "smoke"
    selected_backends: list[str] = field(default_factory=lambda: ["wayland_portal"])
    running: bool = False
    overall_progress: float = 0.0
    current_action_index: int = 0
    current_action_total: int = 0
    current_task_progress: float = 0.0
    current_task_phase: str = ""
    current_step: str = ""
    current_command: str = ""
    current_rationale: str = ""
    sub_step: str = ""
    vm_heartbeat: str = ""
    last_activity_at: float = 0.0
    last_command: str = ""
    current_log_path: str = ""
    status_msg: str = ""
    log_lines: list[str] = field(default_factory=list)
    log_queue: queue.Queue = field(default_factory=queue.Queue)
    matrix: dict = field(default_factory=dict)
    scenarios_to_run: list[dict] = field(default_factory=list)
    readiness: list[AssetReadiness] = field(default_factory=list)
    readiness_cache: list[AssetReadiness] = field(default_factory=list)
    readiness_last_refresh: float = 0.0
    detail_view: bool = False
    execution_plan: list[PlannedAction] = field(default_factory=list)
    action_results: list[dict] = field(default_factory=list)


def load_env_local() -> None:
    env_path = E2E_DIR / "env.local"
    if not env_path.exists():
        return
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ[k.strip()] = v.strip()


def check_env(state: WizardState, *, force: bool = False) -> None:
    state.adb_required = selected_requires_device(state)
    now = time.time()
    if force or state.adb_last_refresh == 0.0 or now - state.adb_last_refresh >= 5.0:
        state.adb_status = probe_adb_host()
        state.adb_last_refresh = now
    state.adb_connected = state.adb_status.status == "ready"
    state.kvm_ready = os.path.exists("/dev/kvm")


def probe_adb_host() -> AdbHostStatus:
    try:
        proc = subprocess.run(["adb", "devices", "-l"], capture_output=True, text=True, check=False, timeout=8)
    except FileNotFoundError:
        return AdbHostStatus("missing", None, "adb command not found")
    except (OSError, subprocess.SubprocessError) as exc:
        return AdbHostStatus("missing", None, f"adb check failed: {exc}")
    status = classify_adb_host("\n".join([proc.stdout, proc.stderr]))
    if status.status in {"ready", "unauthorized", "offline", "multiple"}:
        return status
    try:
        subprocess.run(["adb", "start-server"], capture_output=True, text=True, check=False, timeout=8)
        retry = subprocess.run(["adb", "devices", "-l"], capture_output=True, text=True, check=False, timeout=8)
    except (FileNotFoundError, OSError, subprocess.SubprocessError):
        return status
    return classify_adb_host("\n".join([retry.stdout, retry.stderr]))


def parse_adb_devices(output: str) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    for line in output.splitlines():
        line = line.strip()
        if not line or line.lower().startswith("list of devices attached"):
            continue
        if line.startswith("* daemon "):
            continue
        parts = line.strip().split()
        if len(parts) >= 3 and parts[1] == "no" and parts[2] == "permissions":
            rows.append({"serial": parts[0], "state": "no_permissions"})
            continue
        if len(parts) >= 2:
            if parts[1] in ADB_DEVICE_STATES:
                rows.append({"serial": parts[0], "state": parts[1]})
    return rows


def classify_adb_host(output: str) -> AdbHostStatus:
    rows = parse_adb_devices(output)
    ready = [row for row in rows if row["state"] == "device"]
    if len(ready) == 1:
        return AdbHostStatus("ready", ready[0]["serial"], f"ready: {ready[0]['serial']}")
    if len(ready) > 1:
        return AdbHostStatus("multiple", None, "multiple devices; choose android serial")
    if any(row["state"] == "unauthorized" for row in rows):
        return AdbHostStatus("unauthorized", None, "unauthorized; unlock phone and accept USB debugging prompt")
    if any(row["state"] == "offline" for row in rows):
        return AdbHostStatus("offline", None, "offline; reconnect USB or restart adb")
    if any(row["state"] == "no_permissions" for row in rows):
        return AdbHostStatus("no_permissions", None, "no permissions; check udev rules and USB access")
    return AdbHostStatus("no_device", None, "no adb device")


def android_reason_code_for_adb_status(status: str) -> str:
    if status == "unauthorized":
        return "android_device_unauthorized"
    return "android_device_missing"


def load_matrix() -> dict:
    with MATRIX_PATH.open("r", encoding="utf-8") as fh:
        return json.load(fh)


def get_base_path(distro: str, session: str, installed: bool = False) -> Path:
    base = Path(os.environ.get("WBEAM_E2E_BASE_DIR", str(E2E_DIR / "images" / "base"))).expanduser().resolve()
    suffix = "-installed.qcow2" if installed else ".qcow2"
    return base / distro / f"{session}{suffix}"


def get_portal_consented_path(distro: str, session: str) -> Path:
    base = Path(os.environ.get("WBEAM_E2E_BASE_DIR", str(E2E_DIR / "images" / "base"))).expanduser().resolve()
    return base / distro / f"{session}-portal-consented.qcow2"


def portal_consented_image_path(distro: str, session: str) -> Path:
    return get_portal_consented_path(distro, session)


def expected_runner_report_dir(run_id: str) -> Path:
    return E2E_DIR / "reports" / run_id


def expected_l2_overlay_path(run_id: str, scenario_id: str) -> Path:
    return E2E_DIR / "work" / "runs" / run_id / scenario_id / "disk.qcow2"


def expected_scenario_report_dir(run_id: str, scenario_id: str) -> Path:
    return expected_runner_report_dir(run_id) / "scenarios" / scenario_id


def expected_iso_path(distro_id: str) -> Path:
    filename = ISO_FILENAMES.get(distro_id, f"{distro_id}.iso")
    return E2E_DIR / "images" / "iso" / filename


def get_iso_path(distro_id: str, matrix: dict) -> Path | None:
    distro = next((d for d in matrix.get("distros", []) if d.get("id") == distro_id), None)
    if not distro:
        return None
    env_val = os.environ.get(distro["iso_env"])
    if env_val and Path(env_val).expanduser().exists():
        return Path(env_val).expanduser().resolve()
    path = expected_iso_path(distro_id)
    if path.exists():
        os.environ[distro["iso_env"]] = str(path)
        return path
    return None


def iso_min_size(distro_id: str) -> int:
    if distro_id == "ubuntu-24.04":
        return 2_000 * 1024 * 1024
    return 600 * 1024 * 1024


def classify_iso(distro_id: str, path: Path | None) -> tuple[str, list[str]]:
    warnings: list[str] = []
    if path is None or not path.exists():
        return "missing", warnings
    size = path.stat().st_size
    if size < iso_min_size(distro_id):
        warnings.append(f"ISO too small: {size} bytes")
        return "too_small", warnings
    return "ok", warnings


def classify_disk_image(path: Path, manifest_path: Path | None = None, *, expected_kind: str | None = None) -> tuple[str, list[str]]:
    warnings: list[str] = []
    if not path.exists():
        if path.parent.exists():
            partial_candidates = [path.parent / "install.qcow2", path.parent / "work.qcow2"]
            if any(candidate.exists() for candidate in partial_candidates):
                return "partial", ["partial build artifacts exist"]
        return "missing", warnings
    if path.stat().st_size < 10 * 1024 * 1024:
        return "invalid", [f"qcow2 too small: {path.stat().st_size} bytes"]
    if manifest_path is not None:
        if not manifest_path.exists():
            warnings.append(f"manifest missing: {manifest_path}")
            return "partial", warnings
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001
            return "invalid_manifest", [f"manifest unreadable: {manifest_path}: {exc}"]
        if not isinstance(manifest, dict):
            return "invalid_manifest", [f"manifest is not an object: {manifest_path}"]
        if manifest.get("schema") not in {1, 2}:
            return "invalid_manifest", [f"unsupported manifest schema: {manifest.get('schema')!r}"]
        if expected_kind:
            manifest_kind = str(manifest.get("kind", ""))
            if manifest_kind.replace("-", "_") != str(expected_kind).replace("-", "_"):
                return "stale", [f"manifest kind mismatch: expected {expected_kind}, got {manifest.get('kind')!r}"]
    return "ok", warnings


def classify_portal_consented_image(path: Path, manifest_path: Path) -> tuple[str, list[str]]:
    if not path.exists():
        return "missing", []
    if path.stat().st_size < 10 * 1024 * 1024:
        return "corrupt", [f"qcow2 too small: {path.stat().st_size} bytes"]
    try:
        qemu_img = subprocess.run(["qemu-img", "info", str(path)], capture_output=True, text=True, check=False)
    except OSError as exc:
        return "corrupt", [f"qemu-img unavailable or not executable: {exc}"]
    if qemu_img.returncode != 0:
        return "corrupt", [f"qemu-img info failed for {path}"]
    warnings: list[str] = []
    if not manifest_path.exists():
        return "stale", [f"portal-consented manifest missing: {manifest_path}"]
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001
        return "invalid", [f"portal-consented manifest unreadable: {manifest_path}: {exc}"]
    if not isinstance(manifest, dict):
        return "invalid", [f"portal-consented manifest is not an object: {manifest_path}"]
    if manifest.get("schema") != 2:
        return "stale", [f"portal-consented manifest schema mismatch: {manifest.get('schema')!r}"]
    manifest_kind = str(manifest.get("kind", ""))
    if manifest_kind.replace("-", "_") not in {"portal_consented"}:
        return "invalid", [f"portal-consented manifest kind mismatch: {manifest_kind!r}"]
    if manifest.get("stream_smoke_ok") is not True:
        return "stale", ["portal-consented manifest not validated: stream_smoke_ok is not true"]
    return "ok", warnings


def trim_middle(value: str, max_len: int) -> str:
    if max_len <= 0 or len(value) <= max_len:
        return value
    if max_len <= 3:
        return value[:max_len]
    keep = max(2, (max_len - 3) // 2)
    return value[:keep] + "..." + value[-keep:]


def safe_log_name(value: str) -> str:
    return "".join(ch if ch.isalnum() or ch in "._-" else "_" for ch in value)


def wizard_steps_path(state: WizardState) -> Path:
    assert state.run_dir is not None
    return state.run_dir / "steps.jsonl"


def emit_wizard_event(state: WizardState, event_type: str, **payload) -> None:
    if state.run_dir is None:
        return
    event = {
        "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "type": event_type,
        "run_id": state.run_id,
        **payload,
    }
    path = wizard_steps_path(state)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(event, sort_keys=True) + "\n")


def render_frame(stdscr, title: str) -> None:
    draw_header(stdscr, title)
    draw_footer(stdscr, "Enter: Dalej | Space: Zmień | b/q: Wstecz | ?: Pomoc")


def safe_add(stdscr, y, x, txt, attr=0):
    try:
        my, mx = stdscr.getmaxyx()
        if y < my and x < mx:
            stdscr.addstr(y, x, txt[: max(0, mx - x - 1)], attr)
    except Exception:  # noqa: BLE001
        pass


def draw_header(stdscr, title: str) -> None:
    my, mx = stdscr.getmaxyx()
    safe_add(stdscr, 0, 0, " " * max(0, mx - 1), curses.color_pair(CP_HEADER))
    safe_add(stdscr, 0, 2, f"WBeam E2E Wizard :: {title}", curses.color_pair(CP_HEADER) | curses.A_BOLD)


def draw_footer(stdscr, msg: str) -> None:
    my, mx = stdscr.getmaxyx()
    safe_add(stdscr, my - 1, 0, " " * max(0, mx - 1), curses.color_pair(CP_DIM))
    safe_add(stdscr, my - 1, 2, msg, curses.color_pair(CP_DIM))


def render_welcome(stdscr, state: WizardState) -> None:
    render_frame(stdscr, "Witaj")
    safe_add(stdscr, 3, 4, "Sprawdzanie środowiska przed startem:", curses.A_BOLD)
    if state.adb_status.status == "ready":
        suffix = "" if state.adb_required else " (nie wymagane dla aktualnego wyboru)"
        adb_str = f"[ OK ] ADB ready: {state.adb_status.serial}{suffix}"
        adb_col = curses.color_pair(CP_OK)
    elif not state.adb_required:
        adb_str = f"[ .. ] ADB nie jest wymagane dla wybranych scenariuszy ({state.adb_status.summary})"
        adb_col = curses.color_pair(CP_DIM)
    else:
        adb_str = f"[ !! ] ADB {state.adb_status.status}: {state.adb_status.summary}"
        adb_col = curses.color_pair(CP_MISSING)
    safe_add(stdscr, 5, 6, adb_str, adb_col)
    kvm_str = "[ OK ] Akceleracja KVM dostępna" if state.kvm_ready else "[ !! ] BRAK /dev/kvm"
    kvm_col = curses.color_pair(CP_OK if state.kvm_ready else CP_MISSING)
    safe_add(stdscr, 6, 6, kvm_str, kvm_col)
    safe_add(stdscr, 9, 4, "Naciśnij Enter, aby przejść dalej.", curses.color_pair(CP_BUTTON))


def render_selection(stdscr, state: WizardState) -> None:
    render_frame(stdscr, "Krok 1: Wybór dystrybucji i poziomu testów")
    for i, distro in enumerate(DISTRO_OPTIONS):
        prefix = "[X]" if distro in state.selected_distros else "[ ]"
        attr = curses.color_pair(CP_SELECTED) if state.cursor == i else 0
        safe_add(stdscr, 4 + i, 6, f"{prefix} {distro}", attr)
    safe_add(stdscr, 9, 4, "Tier:", curses.A_BOLD)
    for i, tier in enumerate(TIER_VALUES):
        idx = len(DISTRO_OPTIONS) + i
        prefix = "(*)" if tier == state.selected_tier else "( )"
        attr = curses.color_pair(CP_SELECTED) if state.cursor == idx else 0
        safe_add(stdscr, 10 + i, 6, f"{prefix} {tier}", attr)
    if state.status_msg:
        safe_add(stdscr, 14, 4, state.status_msg, curses.color_pair(CP_MISSING))


def render_backend(stdscr, state: WizardState) -> None:
    render_frame(stdscr, "Krok 2: Wybór backendów")
    descriptions = BACKEND_LABELS
    for i, backend in enumerate(BACKEND_OPTIONS):
        prefix = "[X]" if backend in state.selected_backends else "[ ]"
        attr = curses.color_pair(CP_SELECTED) if state.cursor == i else 0
        safe_add(stdscr, 4 + i, 6, f"{prefix} {descriptions[backend]}", attr)
    if state.cursor < len(BACKEND_OPTIONS):
        backend = BACKEND_OPTIONS[state.cursor]
        safe_add(stdscr, 10, 4, "Rationale:", curses.color_pair(CP_RATIONALE))
        rationale = {
            "wayland_portal": "Wayland Portal używa GNOME/PipeWire i wymaga sesji graficznej.",
            "evdi": "EVDI wymaga modułu kernel/DKMS i może wymagać reboot/MOK.",
            "x11_gst": "X11 fallback jest ścieżką diagnostyczną.",
        }[backend]
        safe_add(stdscr, 11, 6, rationale, curses.color_pair(CP_DIM))
    if state.status_msg:
        safe_add(stdscr, 14, 4, state.status_msg, curses.color_pair(CP_MISSING))


def status_attr(status: str) -> int:
    normalized = normalize_ui_status(status)
    if normalized in {"ok", "pass", "ready", "done"}:
        return curses.color_pair(CP_OK)
    if normalized in {"not_required", "skipped", "out_of_scope"}:
        return curses.color_pair(CP_DIM)
    if normalized in {"fail", "invalid", "corrupt", "too_small", "invalid_manifest"}:
        return curses.color_pair(CP_MISSING)
    if normalized in {"blocked", "manual_required", "missing", "partial", "stale", "unknown"}:
        return curses.color_pair(CP_RATIONALE)
    if normalized in {"pending", "running", "next"}:
        return curses.color_pair(CP_PROGRESS)
    return 0


def normalize_ui_status(status: str) -> str:
    value = str(status or "unknown").strip().lower().replace("-", "_")
    aliases = {
        "portal_consent_required": "manual_required",
        "missing_portal_consented_image": "missing",
        "not required": "not_required",
        "out of scope": "out_of_scope",
    }
    return aliases.get(value, value)


def status_badge(status: str) -> str:
    labels = {
        "ok": "OK",
        "pass": "PASS",
        "ready": "READY",
        "done": "DONE",
        "missing": "MISSING",
        "blocked": "BLOCKED",
        "manual_required": "MANUAL REQUIRED",
        "pending": "PENDING",
        "running": "RUNNING",
        "next": "NEXT",
        "skipped": "SKIPPED",
        "out_of_scope": "OUT OF MVP",
        "invalid": "INVALID",
        "stale": "STALE",
        "corrupt": "CORRUPT",
        "fail": "FAIL",
        "not_required": "NOT REQUIRED",
        "partial": "PARTIAL",
        "unknown": "UNKNOWN",
    }
    normalized = normalize_ui_status(status)
    return f"[{labels.get(normalized, normalized.upper())}]"


def row_blocks(row: UiRow) -> bool:
    return normalize_ui_status(row.status) in {
        "missing",
        "blocked",
        "manual_required",
        "invalid",
        "stale",
        "corrupt",
        "fail",
        "partial",
        "invalid_manifest",
    }


def portal_consent_command(distro: str, session: str) -> str:
    return f"./e2e/run prepare-portal-consent --distro {distro} --session {session} --backend wayland_portal --live --promote"


def primary_blocker(view: ScenarioReadinessView) -> tuple[str, str, str]:
    candidates = [
        *view.required_assets,
        *view.manual_gates,
        *view.environment_gates,
    ]
    priority = {
        "fail": 0,
        "invalid": 1,
        "corrupt": 2,
        "invalid_manifest": 3,
        "manual_required": 4,
        "missing": 5,
        "blocked": 6,
        "stale": 7,
        "partial": 8,
    }
    blocking_rows = [row for row in candidates if row_blocks(row)]
    if not blocking_rows:
        return "", "", ""
    row = sorted(blocking_rows, key=lambda item: priority.get(normalize_ui_status(item.status), 99))[0]
    reason_code = row.reason_code or normalize_ui_status(row.status)
    summary = f"{row.label}: {status_badge(row.status)}"
    if row.detail:
        summary = f"{summary} {row.detail}"
    return reason_code, summary, row.next_action


PROGRESS_HINTS: list[tuple[str, float, str]] = [
    ("creating disk", 0.10, "creating disk overlay"),
    ("creating overlay", 0.10, "creating disk overlay"),
    ("starting installer vm", 0.20, "starting installer VM"),
    ("booting vm", 0.25, "booting VM"),
    ("portal consent vm display", 0.25, "visible VM is running"),
    ("waiting for ssh", 0.35, "waiting for SSH"),
    ("waiting for ssh on", 0.35, "waiting for SSH"),
    ("rsync", 0.50, "copying WBeam sources"),
    ("sending incremental file list", 0.50, "copying WBeam sources"),
    ("cargo build", 0.65, "building host binaries"),
    ("starting local daemon", 0.65, "starting daemon"),
    ("daemon already reachable", 0.70, "daemon ready"),
    ("portal consent attempt", 0.72, "triggering portal consent"),
    ("running stream smoke", 0.74, "running stream smoke"),
    ("gnome screencast", 0.80, "waiting for manual GNOME ScreenCast approval"),
    ("waiting for gnome screencast approval", 0.80, "waiting for manual GNOME ScreenCast approval"),
    ("approve the gnome screencast", 0.80, "waiting for manual GNOME ScreenCast approval"),
    ("stream smoke", 0.84, "verifying stream bytes"),
    ("asserting green run", 0.88, "asserting green run"),
    ("validating portal consented asset", 0.90, "validating L1P asset"),
    ("promoted", 1.00, "done"),
    ("pass", 1.00, "done"),
    ("ok action", 1.00, "done"),
]


def progress_hint_from_log(message: str) -> tuple[float, str] | None:
    lower = message.strip().lower()
    if not lower:
        return None
    for needle, progress, phase in PROGRESS_HINTS:
        if needle in lower:
            return progress, phase
    return None


def update_task_progress_from_log(state: WizardState, message: str) -> None:
    hint = progress_hint_from_log(message)
    if hint is None:
        return
    progress, phase = hint
    progress = min(1.0, max(0.0, progress))
    if progress >= state.current_task_progress:
        state.current_task_progress = progress
        state.current_task_phase = phase
    if state.current_action_total > 0:
        state.overall_progress = min(
            1.0,
            ((max(0, state.current_action_index - 1)) + state.current_task_progress) / state.current_action_total,
        )


def resolve_selected_scenarios(state: WizardState) -> list[dict]:
    selected: list[dict] = []
    wanted_distros = set(state.selected_distros)
    wanted_backends = set(state.selected_backends)
    for sc in state.matrix.get("scenarios", []):
        if sc.get("distro") not in wanted_distros:
            continue
        if sc.get("backend") not in wanted_backends:
            continue
        if state.selected_tier == "smoke" and sc.get("tier") not in {"smoke", "backend"}:
            continue
        expected_session = BACKEND_SESSION_DEFAULTS.get(sc.get("backend"))
        if expected_session and sc.get("session") != expected_session:
            continue
        # TODO: optionally filter experimental scenarios behind a user toggle.
        selected.append(sc)
    selected.sort(key=lambda sc: (sc.get("distro", ""), sc.get("session", ""), sc.get("backend", ""), sc.get("id", "")))
    return selected


def selected_requires_device(state: WizardState) -> bool:
    return any(sc.get("device_policy") == "required" for sc in resolve_selected_scenarios(state))


def sync_selected_context(state: WizardState) -> None:
    state.scenarios_to_run = resolve_selected_scenarios(state)
    state.adb_required = selected_requires_device(state)
    state.adb_last_refresh = 0.0


def rebuild_args_for_status(status: str) -> list[str]:
    if status == "missing":
        return ["--missing"]
    if status in {"partial", "invalid", "invalid_manifest", "stale"}:
        return ["--force"]
    return []


def install_backend_for_wizard_item(item: AssetReadiness) -> str:
    backends = set(item.shared_backends)
    if "evdi" in backends:
        return "wayland"
    if "wayland_portal" in backends:
        return "wayland"
    if "x11_gst" in backends:
        return "x11"
    if item.session == "headless":
        return "benchmark_game"
    return "wayland"


def build_readiness(state: WizardState) -> list[AssetReadiness]:
    scenarios = resolve_selected_scenarios(state)
    grouped: dict[tuple[str, str], list[dict]] = {}
    for sc in scenarios:
        grouped.setdefault((sc["distro"], sc["session"]), []).append(sc)
    readiness: list[AssetReadiness] = []
    for (distro_id, session), group in sorted(grouped.items()):
        iso = get_iso_path(distro_id, state.matrix)
        iso_status, iso_warnings = classify_iso(distro_id, iso)
        l0_path = get_base_path(distro_id, session, installed=False)
        l0_status, l0_warnings = classify_disk_image(l0_path, l0_path.with_suffix(".json"), expected_kind="base")
        l1_path = get_base_path(distro_id, session, installed=True)
        l1_status, l1_warnings = classify_disk_image(l1_path, l1_path.with_suffix(".json"), expected_kind="installed")
        requires_portal = any(sc.get("backend") == "wayland_portal" and sc.get("requires_portal") for sc in group)
        portal_path = get_portal_consented_path(distro_id, session)
        portal_consented_status = "not_required"
        portal_consented_action = "not_required"
        portal_warnings: list[str] = []
        if requires_portal:
            portal_consented_status, portal_warnings = classify_portal_consented_image(
                portal_path,
                portal_path.with_suffix(".json"),
            )
            portal_consented_action = "reuse" if portal_consented_status == "ok" else "manual_approve"
        iso_action = "reuse" if iso_status == "ok" else "download"
        l0_action = "reuse" if l0_status == "ok" else ("build" if iso_status == "ok" else "blocked")
        l1_action = "reuse" if l1_status == "ok" else ("build" if l0_status == "ok" else "blocked")
        next_bits: list[str] = []
        if iso_action == "download":
            next_bits.append("download ISO")
        if l0_action == "build":
            next_bits.append("build L0 clean OS" if l0_status == "missing" else "rebuild L0 clean OS")
        elif l0_action == "blocked":
            next_bits.append("build L0 after ISO")
        if l1_action == "build":
            next_bits.append("build L1 installed WBeam" if l1_status == "missing" else "rebuild L1 installed WBeam")
        elif l1_action == "blocked":
            next_bits.append("build L1 after L0")
        if requires_portal:
            if portal_consented_status == "ok":
                next_bits.append("reuse portal-consented image")
            else:
                next_bits.append(
                    f"./e2e/run prepare-portal-consent --distro {distro_id} "
                    f"--session {session} --backend wayland_portal --live --promote"
                )
        next_bits.append(f"run {len(group)} scenario(s) using L2 overlays")
        if requires_portal and portal_consented_status not in {"ok", "not_required"} and iso_status == "ok" and l0_status == "ok" and l1_status == "ok":
            action = "prepare_portal_consent -> run"
        elif requires_portal and portal_consented_status == "ok" and iso_status == "ok" and l0_status == "ok" and l1_status == "ok":
            action = "reuse_portal_consented_l1p -> run_l2_overlays"
        elif iso_status != "ok":
            action = "download_iso -> build_l0 -> build_l1 -> run"
        elif l0_status != "ok":
            action = "build_l0 -> build_l1 -> run"
        elif l1_status != "ok":
            action = "build_l1 -> run"
        else:
            action = "reuse_l1 -> run_l2_overlays"
        next_action = " -> ".join(next_bits)
        readiness.append(
            AssetReadiness(
                distro=distro_id,
                session=session,
                iso_status=iso_status,
                iso_path=str(iso or expected_iso_path(distro_id)),
                iso_action=iso_action,
                l0_status=l0_status,
                l0_path=str(l0_path),
                l0_action=l0_action,
                l1_status=l1_status,
                l1_path=str(l1_path),
                l1_action=l1_action,
                portal_required=requires_portal,
                portal_consented_status=portal_consented_status,
                portal_consented_path=str(portal_path),
                portal_consented_action=portal_consented_action,
                portal_status=portal_consented_status,
                portal_path=str(portal_path),
                portal_action=portal_consented_action,
                action=action,
                shared_backends=sorted({sc["backend"] for sc in group}),
                shared_by_scenarios=[sc["id"] for sc in group],
                warnings=iso_warnings + l0_warnings + l1_warnings + portal_warnings,
                next_action=next_action,
            )
        )
    return readiness


def refresh_readiness(state: WizardState) -> None:
    state.readiness_cache = build_readiness(state)
    state.readiness = list(state.readiness_cache)
    state.readiness_last_refresh = time.time()
    emit_wizard_event(
        state,
        "readiness_refreshed",
        assets=[asdict(item) for item in state.readiness_cache],
    )


def build_execution_plan(state: WizardState) -> list[PlannedAction]:
    readiness = state.readiness or build_readiness(state)
    scenarios = resolve_selected_scenarios(state)
    plan: list[PlannedAction] = []
    for item in readiness:
        if item.iso_action == "download":
            plan.append(
                PlannedAction(
                    id=f"download-iso:{item.distro}",
                    title=f"Download ISO for {item.distro}",
                    kind="download_iso",
                    distro=item.distro,
                    command=[sys.executable, str(DOWNLOAD_SCRIPT), "--distro", item.distro, "--missing"],
                    rationale=f"ISO is required before building L0 clean OS image for {item.distro}.",
                    expected_artifacts=[item.iso_path],
                )
            )
        if item.l0_action != "reuse":
            l0_extra = rebuild_args_for_status(item.l0_status)
            plan.append(
                PlannedAction(
                    id=f"build-l0:{item.distro}:{item.session}",
                    title=f"Build L0 clean OS image for {item.distro}/{item.session}",
                    kind="build_l0",
                    distro=item.distro,
                    session=item.session,
                    command=[
                        sys.executable,
                        str(RUNNER),
                        "prepare-base",
                        "--distro",
                        item.distro,
                        "--session",
                        item.session,
                        *l0_extra,
                        "--live",
                    ],
                    rationale="L0 is a clean OS image reused as base for installed WBeam images.",
                    expected_artifacts=[item.l0_path],
                )
            )
        if item.l1_action != "reuse":
            l1_extra = rebuild_args_for_status(item.l1_status)
            install_backend = install_backend_for_wizard_item(item)
            plan.append(
                PlannedAction(
                    id=f"build-l1:{item.distro}:{item.session}",
                    title=f"Build L1 installed WBeam image for {item.distro}/{item.session}",
                    kind="build_l1",
                    distro=item.distro,
                    session=item.session,
                    log_path=str(E2E_DIR / "work" / "installed" / item.distro / item.session / "guest-prepare-installed.log"),
                    command=[
                        sys.executable,
                        str(RUNNER),
                        "prepare-installed",
                        "--distro",
                        item.distro,
                        "--session",
                        item.session,
                        "--install-backend",
                        install_backend,
                        *l1_extra,
                        "--live",
                    ],
                    rationale="L1 contains WBeam build and system setup and is used read-only as backing image.",
                    expected_artifacts=[item.l1_path],
                )
            )
        if item.portal_required and item.portal_status not in {"ok", "not_required"}:
            portal_manifest = str(Path(item.portal_path).with_suffix(".json")) if item.portal_path else ""
            plan.append(
                PlannedAction(
                    id=f"portal-consent:{item.distro}:{item.session}",
                    title=f"Approve GNOME ScreenCast portal for {item.distro}/{item.session}",
                    kind="portal_consent",
                    distro=item.distro,
                    session=item.session,
                    command=[
                        sys.executable,
                        str(RUNNER),
                        "prepare-portal-consent",
                        "--distro",
                        item.distro,
                        "--session",
                        item.session,
                        "--backend",
                        "wayland_portal",
                        "--live",
                        "--promote",
                    ],
                    rationale="Capture the one-time GNOME ScreenCast approval into a separate portal-consented image.",
                    expected_artifacts=[p for p in [item.portal_path, portal_manifest] if p],
                )
            )
    if scenarios:
        scenario_flags: list[str] = []
        for sc in scenarios:
            scenario_flags.extend(["--scenario", sc["id"]])
        run_id = state.run_id or "wizard-run"
        plan.append(
            PlannedAction(
                id=f"run-matrix:{run_id}",
                title=f"Run {len(scenarios)} selected scenario(s)",
                kind="run_matrix",
                scenario_ids=[sc["id"] for sc in scenarios],
                command=[
                    sys.executable,
                    str(RUNNER),
                    "run",
                    "--run-id",
                    run_id,
                    "--report-dir",
                    str(E2E_DIR / "reports"),
                    "--use-installed",
                    "--live",
                    *scenario_flags,
                ],
                rationale="Final E2E runner call creates L2 overlays from L1 backing images and writes one aggregated report.",
                expected_artifacts=[
                    str(expected_runner_report_dir(run_id) / "summary.json"),
                    str(expected_runner_report_dir(run_id) / "junit.xml"),
                    str(expected_runner_report_dir(run_id) / "report.md"),
                ],
                runner_report_dir=str(expected_runner_report_dir(run_id)),
            )
        )
    return plan


def asset_reason_code(layer: str, status: str) -> str:
    normalized = normalize_ui_status(status)
    if normalized in {"ok", "not_required"}:
        return ""
    if layer == "ISO":
        return "iso_missing" if normalized == "missing" else f"iso_{normalized}"
    if layer == "L0":
        return "distro_image_missing" if normalized == "missing" else f"l0_{normalized}"
    if layer == "L1":
        return "installed_image_missing" if normalized == "missing" else f"l1_{normalized}"
    if layer == "L1P":
        if normalized == "missing":
            return "missing_portal_consented_image"
        return "invalid_portal_consented_image"
    return normalized


def build_readiness_view(state: WizardState) -> ScenarioReadinessView:
    scenarios = resolve_selected_scenarios(state)
    readiness = state.readiness_cache or state.readiness or build_readiness(state)
    original_readiness = state.readiness
    state.readiness = readiness
    try:
        plan = state.execution_plan or build_execution_plan(state)
    finally:
        state.readiness = original_readiness

    selected_tests = [
        UiRow(
            label=str(sc.get("id", "unknown")),
            status="ready",
            detail=(
                f"distro={sc.get('distro')} session={sc.get('session')} "
                f"backend={sc.get('backend')} device_policy={sc.get('device_policy', 'none')}"
            ),
        )
        for sc in scenarios
    ]

    required_assets: list[UiRow] = []
    manual_gates: list[UiRow] = []
    for item in readiness:
        required_assets.extend(
            [
                UiRow("ISO", item.iso_status, item.iso_path, asset_reason_code("ISO", item.iso_status), item.next_action),
                UiRow("L0 clean OS", item.l0_status, item.l0_path, asset_reason_code("L0", item.l0_status), item.next_action),
                UiRow("L1 installed WBeam", item.l1_status, item.l1_path, asset_reason_code("L1", item.l1_status), item.next_action),
            ]
        )
        if item.portal_required:
            portal_reason = asset_reason_code("L1P", item.portal_status)
            portal_next = "" if item.portal_status == "ok" else portal_consent_command(item.distro, item.session)
            required_assets.append(
                UiRow(
                    "L1P portal-consented",
                    item.portal_status,
                    item.portal_path,
                    portal_reason,
                    portal_next,
                )
            )
            manual_gates.append(
                UiRow(
                    "GNOME ScreenCast consent",
                    "ok" if item.portal_status == "ok" else "manual_required",
                    "captured in L1P" if item.portal_status == "ok" else "approve prompt once in the visible VM",
                    "" if item.portal_status == "ok" else portal_reason,
                    portal_next,
                )
            )

    requires_desktop = any(sc.get("requires_desktop") for sc in scenarios)
    requires_portal = any(sc.get("requires_portal") for sc in scenarios)
    requires_evdi = any(sc.get("requires_evdi") for sc in scenarios)
    requires_android = any(sc.get("device_policy") == "required" for sc in scenarios)

    environment_gates: list[UiRow] = []
    if requires_desktop:
        environment_gates.append(UiRow("Graphical session", "pending", "checked inside the scenario VM"))
    if requires_portal:
        environment_gates.extend(
            [
                UiRow("PipeWire / WirePlumber", "pending", "checked by portal stream smoke"),
                UiRow("XDG Desktop Portal", "pending", "checked by portal stream smoke"),
            ]
        )
    if requires_evdi:
        environment_gates.append(UiRow("EVDI kernel module", "pending", "checked by EVDI scenario smoke"))
    if requires_android:
        if state.adb_status.status == "ready":
            environment_gates.append(UiRow("Android ADB device", "ready", state.adb_status.summary))
        else:
            environment_gates.append(
                UiRow(
                    "Android ADB device",
                    "blocked",
                    state.adb_status.summary,
                    android_reason_code_for_adb_status(state.adb_status.status),
                    "Connect/unlock Android device and authorize ADB.",
                )
            )
    else:
        environment_gates.append(UiRow("Android APK deploy", "skipped", "device_policy=none"))

    out_of_scope: list[UiRow] = []
    selected_backends = set(state.selected_backends)
    if "wayland_portal" in selected_backends:
        if "evdi" not in selected_backends:
            out_of_scope.append(UiRow("Fedora EVDI", "out_of_scope", "not part of Fedora Portal MVP run"))
        if "x11_gst" not in selected_backends:
            out_of_scope.append(UiRow("Fedora X11", "out_of_scope", "not part of Fedora Portal MVP run"))
        if not requires_android:
            out_of_scope.append(UiRow("Android hardware", "out_of_scope", "device_policy=none / outside Fedora MVP"))

    execution_plan: list[UiRow] = []
    for idx, action in enumerate(plan, start=1):
        status = "next" if idx == 1 else "pending"
        execution_plan.append(
            UiRow(
                label=f"{idx}. {action.title}",
                status=status,
                detail=" ".join(action.command),
                next_action=" ".join(action.command) if idx == 1 else "",
            )
        )
    if not execution_plan:
        execution_plan.append(UiRow("No action required", "ready", "selected tests are already ready to run"))

    view = ScenarioReadinessView(
        status="ready",
        blocker_reason_code="",
        blocker_summary="",
        next_action=execution_plan[0].next_action or "Enter to run selected scenarios.",
        selected_tests=selected_tests,
        required_assets=required_assets,
        environment_gates=environment_gates,
        manual_gates=manual_gates,
        execution_plan=execution_plan,
        out_of_scope=out_of_scope,
    )
    reason_code, summary, next_action = primary_blocker(view)
    if reason_code:
        view.status = "blocked"
        view.blocker_reason_code = reason_code
        view.blocker_summary = summary
        view.next_action = next_action or "Inspect readiness details and fix the blocking gate."
    elif not scenarios:
        view.status = "blocked"
        view.blocker_reason_code = "no_scenarios_selected"
        view.blocker_summary = "No matching scenarios selected."
        view.next_action = "Select at least one distro/backend combination."
    return view


def write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")


def write_wizard_plan_files(state: WizardState) -> None:
    if state.run_dir is None:
        return
    readiness_view = build_readiness_view(state)
    write_json(state.run_dir / "readiness.json", [asdict(item) for item in state.readiness])
    write_json(state.run_dir / "readiness-view.json", asdict(readiness_view))
    write_json(state.run_dir / "execution-plan.json", [asdict(action) for action in state.execution_plan])
    write_json(state.run_dir / "selected-scenarios.json", {"scenarios": state.scenarios_to_run})
    emit_wizard_event(
        state,
        "plan_written",
        readiness_path=str(state.run_dir / "readiness.json"),
        readiness_view_path=str(state.run_dir / "readiness-view.json"),
        execution_plan_path=str(state.run_dir / "execution-plan.json"),
        actions_total=len(state.execution_plan),
    )


def write_wizard_summary(state: WizardState, *, status: str, failed_action: str = "", reason: str = "") -> None:
    if state.run_dir is None:
        return
    actions_ok = sum(1 for item in state.action_results if item.get("status") == "ok")
    runner_root = expected_runner_report_dir(state.run_id)
    write_json(
        state.run_dir / "summary.json",
        {
            "schema": 1,
            "run_id": state.run_id,
            "status": status,
            "actions_total": len(state.execution_plan),
            "actions_ok": actions_ok,
            "failed_action": failed_action,
            "reason": reason,
            "readiness": "readiness.json",
            "readiness_view": "readiness-view.json",
            "execution_plan": "execution-plan.json",
            "steps": "steps.jsonl",
            "report_md": "report.md",
            "logs_dir": "logs",
            "runner_report_dir": str(runner_root),
            "runner_summary": str(runner_root / "summary.json"),
            "runner_junit": str(runner_root / "junit.xml"),
            "runner_report_md": str(runner_root / "report.md"),
            "actions": state.action_results,
        },
    )
    write_wizard_report(state, status=status, failed_action=failed_action, reason=reason)


def write_wizard_report(state: WizardState, *, status: str, failed_action: str = "", reason: str = "") -> None:
    if state.run_dir is None:
        return
    lines = [
        "# WBeam E2E Wizard Report",
        "",
        f"- Run ID: `{state.run_id}`",
        f"- Status: `{status}`",
        f"- Reason: `{reason}`" if reason else "- Reason: `-`",
        f"- Failed action: `{failed_action}`" if failed_action else "- Failed action: `-`",
        f"- Wizard summary: `{state.run_dir / 'summary.json'}`",
        f"- Wizard report: `{state.run_dir / 'report.md'}`",
        f"- Wizard steps: `{wizard_steps_path(state)}`",
        f"- Readiness: `{state.run_dir / 'readiness.json'}`",
        f"- Readiness view: `{state.run_dir / 'readiness-view.json'}`",
        f"- Execution plan: `{state.run_dir / 'execution-plan.json'}`",
        f"- Logs: `{state.run_dir / 'logs'}`",
        f"- Runner report: `{expected_runner_report_dir(state.run_id)}`",
        "",
        "## Actions",
        "",
    ]
    for result in state.action_results:
        lines.append(f"- `{result.get('id')}`: `{result.get('status')}`")
        if result.get("log_path"):
            lines.append(f"  - Log: `{result['log_path']}`")
        if result.get("exit_code") is not None:
            lines.append(f"  - Exit code: `{result['exit_code']}`")
        if result.get("missing_artifacts"):
            lines.append(f"  - Missing artifacts: `{result['missing_artifacts']}`")
    lines.extend(["", "## Selected scenarios", ""])
    for sc in state.scenarios_to_run:
        lines.append(f"- `{sc['id']}`: distro=`{sc['distro']}` session=`{sc['session']}` backend=`{sc['backend']}`")
        lines.append(f"  - Scenario report: `{expected_scenario_report_dir(state.run_id, sc['id'])}`")
        lines.append(f"  - L2 overlay: `{expected_l2_overlay_path(state.run_id, sc['id'])}`")
    (state.run_dir / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def missing_expected_artifacts(action: PlannedAction) -> list[str]:
    missing: list[str] = []
    for artifact in action.expected_artifacts:
        if not artifact:
            continue
        path = Path(artifact).expanduser()
        if not path.is_absolute():
            # Scenario-relative artifacts are published by the runner report layer.
            # Batch 2 canonicalizes those paths, so the TUI only enforces absolute
            # ISO/L0/L1 artifacts here.
            continue
        if not path.exists():
            missing.append(artifact)
    return missing


def format_ui_row(row: UiRow, *, detail: bool, width: int) -> str:
    prefix = f"{status_badge(row.status):18} {row.label}"
    parts = [prefix]
    if row.reason_code:
        parts.append(f"reason={row.reason_code}")
    if detail and row.detail:
        parts.append(row.detail)
    return trim_middle("  ".join(parts), width)


def render_panel(stdscr, y: int, x: int, width: int, title: str, rows: list[str | UiRow], *, detail: bool = False, max_rows: int = 8) -> int:
    if width < 20:
        return y
    safe_add(stdscr, y, x, "+" + "-" * max(0, width - 2) + "+", curses.color_pair(CP_BORDER))
    safe_add(stdscr, y, x + 2, f" {title} ", curses.color_pair(CP_RATIONALE) | curses.A_BOLD)
    y += 1
    shown = rows[:max_rows]
    for row in shown:
        if isinstance(row, UiRow):
            text = format_ui_row(row, detail=detail, width=max(1, width - 4))
            attr = status_attr(row.status)
        else:
            text = trim_middle(str(row), max(1, width - 4))
            attr = 0
        safe_add(stdscr, y, x, "|", curses.color_pair(CP_BORDER))
        safe_add(stdscr, y, x + 2, text.ljust(max(0, width - 4)), attr)
        safe_add(stdscr, y, x + width - 1, "|", curses.color_pair(CP_BORDER))
        y += 1
    if len(rows) > max_rows:
        more = f"... +{len(rows) - max_rows} more"
        safe_add(stdscr, y, x, "|", curses.color_pair(CP_BORDER))
        safe_add(stdscr, y, x + 2, more.ljust(max(0, width - 4)), curses.color_pair(CP_DIM))
        safe_add(stdscr, y, x + width - 1, "|", curses.color_pair(CP_BORDER))
        y += 1
    safe_add(stdscr, y, x, "+" + "-" * max(0, width - 2) + "+", curses.color_pair(CP_BORDER))
    return y + 1


def render_readiness(stdscr, state: WizardState) -> None:
    render_frame(stdscr, "Krok 3: Gotowość assetów i plan")
    if not state.readiness_cache:
        refresh_readiness(state)
    state.execution_plan = build_execution_plan(state)
    view = build_readiness_view(state)
    my, mx = stdscr.getmaxyx()
    width = max(30, mx - 8)
    y = 2
    status_lines = [
        UiRow("Overall", view.status, view.blocker_summary or "selected tests are ready", view.blocker_reason_code, view.next_action),
        UiRow("Next action", "next" if view.next_action else "ready", view.next_action or "Enter to run selected scenarios."),
    ]
    y = render_panel(stdscr, y, 4, width, "Fedora MVP Status", status_lines, detail=True, max_rows=3)
    if y > my - 8:
        safe_add(stdscr, my - 2, 4, "Terminal too small for readiness panels.", curses.color_pair(CP_MISSING))
        return
    compact = not state.detail_view
    selected_rows = view.selected_tests if state.detail_view else view.selected_tests[:3]
    y = render_panel(stdscr, y, 4, width, "Selected Tests", selected_rows, detail=state.detail_view, max_rows=4)
    if y > my - 8:
        safe_add(stdscr, my - 2, 4, "More panels hidden; resize terminal or press v for compact/detail.", curses.color_pair(CP_DIM))
        return
    y = render_panel(stdscr, y, 4, width, "Required Assets", view.required_assets, detail=state.detail_view, max_rows=8 if state.detail_view else 5)
    if y > my - 8:
        safe_add(stdscr, my - 2, 4, "More panels hidden; resize terminal or press v for compact/detail.", curses.color_pair(CP_DIM))
        return
    gate_rows = [*view.manual_gates, *view.environment_gates, *view.out_of_scope]
    y = render_panel(stdscr, y, 4, width, "Gates", gate_rows, detail=state.detail_view, max_rows=10 if state.detail_view else 6)
    if y <= my - 8:
        y = render_panel(stdscr, y, 4, width, "Execution Plan", view.execution_plan, detail=state.detail_view, max_rows=8 if state.detail_view else 5)
    mode = "detail" if state.detail_view else "compact"
    safe_add(stdscr, my - 5, 4, f"View: {mode} | Status: {status_badge(view.status)} | Reason: {view.blocker_reason_code or '-'}", status_attr(view.status))
    safe_add(stdscr, my - 4, 4, f"Plan actions: {len(state.execution_plan)}", curses.color_pair(CP_RATIONALE))
    safe_add(stdscr, my - 3, 4, f"Runner report: {trim_middle(str(expected_runner_report_dir(state.run_id)), max(20, mx - 20))}", curses.color_pair(CP_DIM))
    safe_add(stdscr, my - 2, 4, f"Wizard state: {trim_middle(str(state.run_dir), max(20, mx - 18))}", curses.color_pair(CP_DIM))
    safe_add(stdscr, my - 1, 4, "Enter: Start | p: Portal consent | r/Space: Refresh | v: Detail | b: Back | q: Quit | ?: Help", curses.color_pair(CP_DIM))


def render_help(stdscr, state: WizardState) -> None:
    render_frame(stdscr, "Pomoc")
    lines = [
        "ISO  = obraz instalacyjny systemu operacyjnego.",
        "L0   = clean OS image: czysty system bez WBeam.",
        "L1   = installed WBeam image: system z zależnościami i buildem WBeam.",
        "L2   = disposable run overlay: tymczasowy dysk testu oparty o L1.",
        "ADB  = wymagane tylko dla hardware Android scenarios.",
        "Wayland Portal = normalny GNOME/PipeWire path.",
        "EVDI = virtual display path; może wymagać DKMS/MOK/reboot.",
        "Portal consent asset = separate L1C image: <session>-portal-consented.qcow2.",
        "prepare-portal-consent = manual approval step for the GNOME ScreenCast prompt.",
        "BLOCKED = brakuje warunku środowiskowego albo manualnej zgody; FAIL = regresja techniczna.",
        "Android APK deploy może być SKIPPED, jeśli wybrane scenariusze mają device_policy=none.",
        "Readiness compact/detail: naciśnij v na ekranie assetów.",
        "PARTIAL = wykryto niedokończony build.",
        "STALE = istnieje asset, ale manifest nie pasuje do oczekiwanego rodzaju/schematu.",
        "INVALID_MANIFEST = manifest jest nieczytelny albo niezgodny.",
        f"Wizard state = {E2E_DIR / 'work' / 'wizard' / state.run_id}.",
        f"Runner report = {expected_runner_report_dir(state.run_id)}.",
        "Runner run uses one aggregated report for all selected scenarios.",
        "L2 overlays live under e2e/work/runs/<run-id>/<scenario>/disk.qcow2.",
        "",
        "Enter/q: wróć",
    ]
    for idx, line in enumerate(lines):
        safe_add(stdscr, 3 + idx, 4, line, curses.color_pair(CP_DIM if idx != len(lines) - 1 else CP_BUTTON))


def render_execution(stdscr, state: WizardState) -> None:
    render_frame(stdscr, "Krok 4: Wykonanie")
    my, mx = stdscr.getmaxyx()
    now = time.time()
    idle_sec = int(now - state.last_activity_at) if state.last_activity_at else 0
    safe_add(stdscr, 2, 4, f"Postęp całkowity: {int(state.overall_progress * 100)}%")
    bar_w = max(10, mx - 10)
    filled = int(bar_w * state.overall_progress)
    safe_add(stdscr, 3, 4, "█" * filled + "░" * (bar_w - filled), curses.color_pair(CP_PROGRESS))
    action_label = f"{state.current_action_index}/{state.current_action_total}" if state.current_action_total else "-"
    safe_add(stdscr, 5, 4, f"Postęp aktualnego kroku ({action_label}): {int(state.current_task_progress * 100)}%")
    task_filled = int(bar_w * state.current_task_progress)
    safe_add(stdscr, 6, 4, "█" * task_filled + "░" * (bar_w - task_filled), curses.color_pair(CP_SELECTED))
    safe_add(stdscr, 8, 4, "AKTUALNIE ROBIĘ:", curses.A_BOLD)
    safe_add(stdscr, 9, 6, state.current_step or "oczekiwanie...", curses.color_pair(CP_SELECTED))
    safe_add(stdscr, 10, 4, f"FAZA: {state.current_task_phase or state.sub_step or '-'}", curses.color_pair(CP_PROGRESS))
    safe_add(stdscr, 12, 4, "DLACZEGO:", curses.color_pair(CP_RATIONALE))
    safe_add(stdscr, 13, 6, state.current_rationale or "-", curses.color_pair(CP_DIM))
    safe_add(stdscr, 14, 4, f"LOG: {state.current_log_path or '-'}", curses.color_pair(CP_DIM))
    safe_add(stdscr, 15, 4, f"Wizard run dir: {trim_middle(str(state.run_dir), max(20, mx - 20))}", curses.color_pair(CP_DIM))
    safe_add(stdscr, 16, 4, f"Runner report: {trim_middle(str(expected_runner_report_dir(state.run_id)), max(20, mx - 20))}", curses.color_pair(CP_DIM))
    if state.current_command or state.last_command:
        safe_add(stdscr, 17, 4, f"CMD: {state.current_command or state.last_command}", curses.color_pair(CP_DIM))
    if state.vm_heartbeat:
        safe_add(stdscr, 18, 4, f"HEARTBEAT: {state.vm_heartbeat}", curses.color_pair(CP_PROGRESS))
    if idle_sec > 0:
        safe_add(stdscr, 19, 4, f"Brak nowych logów od {idle_sec}s", curses.color_pair(CP_DIM))
    if state.status_msg:
        safe_add(stdscr, 20, 4, state.status_msg, curses.color_pair(CP_MISSING))
    log_rows = max(0, my - 24)
    start_row = 21 if idle_sec == 0 and not state.status_msg else 22
    safe_add(stdscr, start_row, 4, "OSTATNIE LOGI:", curses.color_pair(CP_DIM))
    for i, line in enumerate(state.log_lines[-log_rows:]):
        safe_add(stdscr, start_row + 1 + i, 6, line, curses.color_pair(CP_DIM))


def render_report(stdscr, state: WizardState) -> None:
    render_frame(stdscr, "Raport")
    runner_root = expected_runner_report_dir(state.run_id)
    runner_summary = runner_root / "summary.json"
    summary_payload: dict = {}
    if runner_summary.exists():
        try:
            summary_payload = json.loads(runner_summary.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            summary_payload = {}
    final_status = str(summary_payload.get("status") or ("running" if state.running else "done"))
    safe_add(stdscr, 4, 4, f"Final status: {status_badge(final_status)}", status_attr(final_status))
    safe_add(stdscr, 5, 4, state.current_rationale or "-", curses.color_pair(CP_DIM))
    if summary_payload:
        counts = (
            f"pass={summary_payload.get('scenarios_passed', 0)} "
            f"blocked={summary_payload.get('scenarios_blocked', 0)} "
            f"fail={summary_payload.get('scenarios_failed', 0)}"
        )
        safe_add(stdscr, 7, 4, f"Scenario counts: {counts}", curses.color_pair(CP_RATIONALE))
        failures = summary_payload.get("failures") or []
        blocked = [item for item in summary_payload.get("results", []) if isinstance(item, dict) and item.get("status") == "blocked"]
        first_issue = (failures or blocked or [{}])[0]
        if isinstance(first_issue, dict) and first_issue:
            reason_code = first_issue.get("reason_code") or "-"
            next_action = first_issue.get("next_action") or "-"
            safe_add(stdscr, 8, 4, f"Primary reason_code: {reason_code}", curses.color_pair(CP_RATIONALE))
            safe_add(stdscr, 9, 4, f"Next action: {trim_middle(str(next_action), 100)}", curses.color_pair(CP_RATIONALE))
    if state.run_dir is not None:
        safe_add(stdscr, 11, 4, f"Run dir: {state.run_dir}", curses.color_pair(CP_DIM))
        safe_add(stdscr, 12, 4, f"Summary: {state.run_dir / 'summary.json'}", curses.color_pair(CP_DIM))
        report_md = state.run_dir / "report.md"
        steps_path = state.run_dir / "steps.jsonl"
        safe_add(stdscr, 13, 4, f"Wizard report: {report_md}", curses.color_pair(CP_DIM))
        safe_add(stdscr, 14, 4, f"Wizard steps: {steps_path if steps_path.exists() else 'not written'}", curses.color_pair(CP_DIM))
        safe_add(stdscr, 15, 4, f"Runner report: {runner_root}", curses.color_pair(CP_DIM))
        safe_add(stdscr, 16, 4, f"Runner summary: {runner_summary}", curses.color_pair(CP_DIM))
        safe_add(stdscr, 17, 4, f"JUnit: {runner_root / 'junit.xml'}", curses.color_pair(CP_DIM))
        safe_add(stdscr, 18, 4, f"Assert green: {runner_root / 'assert-green.json'}", curses.color_pair(CP_DIM))
        safe_add(stdscr, 19, 4, f"Final close: {runner_root / 'final-close.json'}", curses.color_pair(CP_DIM))
    if state.current_log_path:
        safe_add(stdscr, 21, 4, f"Last log: {state.current_log_path}", curses.color_pair(CP_DIM))
    if state.last_command:
        safe_add(stdscr, 22, 4, f"Last command: {state.last_command}", curses.color_pair(CP_DIM))


class Automator(threading.Thread):
    def __init__(self, state: WizardState):
        super().__init__(daemon=True)
        self.state = state

    def log(self, msg: str) -> None:
        msg = msg.strip()
        if not msg:
            return
        self.state.last_activity_at = time.time()
        self.state.log_lines.append(f"[{time.strftime('%H:%M:%S')}] {msg}")
        if len(self.state.log_lines) > 1000:
            self.state.log_lines = self.state.log_lines[-800:]
        if self.state.current_log_path:
            log_path = Path(self.state.current_log_path)
            log_path.parent.mkdir(parents=True, exist_ok=True)
            with log_path.open("a", encoding="utf-8") as fh:
                fh.write(f"[{time.strftime('%H:%M:%S')}] {msg}\n")
        update_task_progress_from_log(self.state, msg)
        lower = msg.lower()
        if "creating disk" in lower:
            self.state.sub_step = "Tworzenie obrazu dysku QCOW2..."
        elif "starting installer vm" in lower:
            self.state.sub_step = "Uruchamianie maszyny QEMU z instalatorem..."
        elif "waiting for installer to complete" in lower:
            self.state.sub_step = "Instalacja systemu..."
        elif "booting vm" in lower:
            self.state.sub_step = "Uruchamianie systemu z dysku..."
        elif "waiting for ssh" in lower:
            self.state.sub_step = "Oczekiwanie na gotowość SSH..."
        elif "rsync" in lower:
            self.state.sub_step = "Kopiowanie źródeł WBeam do VM..."
        elif "cargo build" in lower:
            self.state.sub_step = "Kompilacja WBeam..."
        elif "streaming smoke test" in lower:
            self.state.sub_step = "Test strumieniowania..."
        if "starting" in lower and "vm" in lower:
            self.state.vm_heartbeat = "ON (QEMU)"
        if "shutting down" in lower:
            self.state.vm_heartbeat = "OFF (Powering down)"
        emit_wizard_event(
            self.state,
            "log",
            action=self.state.current_step,
            message=msg,
            log_path=self.state.current_log_path,
        )

    def run_cmd(self, cmd: list[str], rationale: str, log_path: Path | None = None) -> int:
        self.state.current_step = " ".join(cmd[:3]) + ("..." if len(cmd) > 3 else "")
        self.state.current_command = " ".join(cmd)
        self.state.current_rationale = rationale
        self.state.last_command = " ".join(cmd)
        self.state.current_log_path = str(log_path or "")
        self.state.sub_step = "Startowanie operacji..."
        self.state.current_task_phase = "starting operation"
        self.state.current_task_progress = max(self.state.current_task_progress, 0.02)
        self.state.last_activity_at = time.time()
        try:
            proc = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                cwd=str(ROOT),
                bufsize=1,
            )
        except FileNotFoundError as exc:
            self.log(f"ERROR missing command: {exc}")
            self.state.current_command = ""
            return 127
        assert proc.stdout is not None
        for line in proc.stdout:
            self.log(line)
        rc = proc.wait()
        self.state.current_command = ""
        return rc

    def run(self) -> None:
        self.state.running = True
        load_env_local()
        if self.state.run_dir is None:
            run_id = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
            self.state.run_id = run_id
            self.state.run_dir = E2E_DIR / "work" / "wizard" / run_id
        self.state.run_dir.mkdir(parents=True, exist_ok=True)
        (self.state.run_dir / "logs").mkdir(parents=True, exist_ok=True)
        emit_wizard_event(
            self.state,
            "wizard_started",
            selected_distros=self.state.selected_distros,
            selected_backends=self.state.selected_backends,
            selected_tier=self.state.selected_tier,
        )
        if not self.state.execution_plan:
            self.state.readiness = build_readiness(self.state)
            self.state.execution_plan = build_execution_plan(self.state)
        sync_selected_context(self.state)
        write_wizard_plan_files(self.state)
        self.state.action_results = []
        total = max(1, len(self.state.execution_plan))
        self.state.current_action_total = total
        for readiness in self.state.readiness:
            self.log(f"READINESS {readiness.distro}/{readiness.session}: ISO {readiness.iso_status} | L0 {readiness.l0_status} | L1 {readiness.l1_status} | {readiness.action}")
        for idx, action in enumerate(self.state.execution_plan, start=1):
            self.state.current_action_index = idx
            self.state.current_action_total = total
            self.state.current_task_progress = 0.0
            self.state.current_task_phase = "queued"
            if not action.log_path:
                action.log_path = str(self.state.run_dir / "logs" / f"{safe_log_name(action.id)}.log")
            self.state.current_step = action.title
            self.state.current_command = " ".join(action.command)
            self.state.current_rationale = action.rationale
            self.state.last_command = " ".join(action.command)
            self.state.current_log_path = action.log_path or ""
            self.state.overall_progress = (idx - 1) / total
            self.log(f"PLAN {idx}/{total}: {action.title}")
            self.log(f"CMD: {' '.join(action.command)}")
            emit_wizard_event(self.state, "action_started", index=idx, total=total, action=asdict(action))
            if action.kind == "run_matrix":
                self.log(f"RUN_ID {self.state.run_id}")
                self.log(f"RUNNER_REPORT_DIR {expected_runner_report_dir(self.state.run_id)}")
                for scenario_id in action.scenario_ids:
                    scenario = next((item for item in self.state.scenarios_to_run if item.get("id") == scenario_id), None)
                    if scenario:
                        self.log(
                            f"SCENARIO {scenario_id} distro={scenario.get('distro')} session={scenario.get('session')} backend={scenario.get('backend')}"
                        )
                        self.log(f"L1_BACKING {get_base_path(str(scenario.get('distro')), str(scenario.get('session')), installed=True)}")
                    else:
                        self.log(f"SCENARIO {scenario_id}")
                    self.log(f"SCENARIO_REPORT {expected_scenario_report_dir(self.state.run_id, scenario_id)}")
                    self.log(f"L2_OVERLAY {expected_l2_overlay_path(self.state.run_id, scenario_id)}")
            rc = self.run_cmd(action.command, action.rationale, Path(action.log_path) if action.log_path else None)
            if rc != 0:
                if action.kind == "portal_consent" and rc in {2, 20}:
                    summary_reason = "portal approval needed; approve the GNOME ScreenCast prompt in the VM window and rerun"
                    if rc == 2:
                        summary_reason = "portal consent captured in work overlay; rerun with promote to preserve it"
                    self.log(f"BLOCKED action={action.id} rc={rc}")
                    self.state.action_results.append({"id": action.id, "status": "blocked", "exit_code": rc, "log_path": action.log_path})
                    emit_wizard_event(
                        self.state,
                        "action_finished",
                        action_id=action.id,
                        status="blocked",
                        exit_code=rc,
                        log_path=action.log_path,
                    )
                    emit_wizard_event(
                        self.state,
                        "wizard_finished",
                        status="blocked",
                        failed_action=action.id,
                        reason=summary_reason,
                    )
                    self.state.current_step = f"BLOCKED: {action.title}"
                    self.state.current_rationale = summary_reason
                    self.state.status_msg = "Portal approval needed. Approve GNOME ScreenCast prompt in VM window; command is retryable."
                    write_wizard_summary(self.state, status="blocked", failed_action=action.id, reason=summary_reason)
                    self.state.running = False
                    self.state.screen = ScreenId.REPORT
                    return
                self.log(f"FAIL action={action.id} rc={rc}")
                self.state.action_results.append({"id": action.id, "status": "fail", "exit_code": rc, "log_path": action.log_path})
                emit_wizard_event(self.state, "action_finished", action_id=action.id, status="fail", exit_code=rc, log_path=action.log_path)
                emit_wizard_event(self.state, "wizard_finished", status="fail", failed_action=action.id, reason=f"exit code {rc}")
                self.state.current_step = f"FAILED: {action.title}"
                self.state.current_rationale = f"next_action: sprawdź log i uruchom ponownie krok {action.id}"
                if action.kind == "build_l1":
                    self.state.status_msg = f"Build L1 failed; inspect {E2E_DIR / 'work' / 'installed' / action.distro / action.session / 'guest-report'}"
                    self.log(f"NEXT: tail -n 200 {E2E_DIR / 'work' / 'installed' / action.distro / action.session / 'guest-prepare-installed.log'}")
                    self.log(f"NEXT: ls -la {E2E_DIR / 'work' / 'installed' / action.distro / action.session / 'guest-report'}")
                    self.log(f"NEXT: cat {E2E_DIR / 'work' / 'installed' / action.distro / action.session / 'prepare-installed-failure.json'}")
                write_wizard_summary(self.state, status="fail", failed_action=action.id, reason=f"exit code {rc}")
                self.state.running = False
                self.state.screen = ScreenId.REPORT
                return
            missing_artifacts = missing_expected_artifacts(action)
            if missing_artifacts:
                self.log(f"FAIL action={action.id} missing_artifacts={missing_artifacts}")
                self.state.action_results.append(
                    {"id": action.id, "status": "fail", "missing_artifacts": missing_artifacts, "log_path": action.log_path}
                )
                emit_wizard_event(
                    self.state,
                    "action_finished",
                    action_id=action.id,
                    status="fail",
                    missing_artifacts=missing_artifacts,
                    log_path=action.log_path,
                )
                emit_wizard_event(self.state, "wizard_finished", status="fail", failed_action=action.id, reason="missing expected artifacts")
                self.state.current_step = f"FAILED: {action.title}"
                self.state.current_rationale = f"next_action: expected artifacts missing: {', '.join(missing_artifacts)}"
                if action.kind == "build_l1":
                    self.state.status_msg = f"Build L1 failed; inspect {E2E_DIR / 'work' / 'installed' / action.distro / action.session / 'guest-report'}"
                    self.log(f"NEXT: tail -n 200 {E2E_DIR / 'work' / 'installed' / action.distro / action.session / 'guest-prepare-installed.log'}")
                    self.log(f"NEXT: ls -la {E2E_DIR / 'work' / 'installed' / action.distro / action.session / 'guest-report'}")
                    self.log(f"NEXT: cat {E2E_DIR / 'work' / 'installed' / action.distro / action.session / 'prepare-installed-failure.json'}")
                write_wizard_summary(self.state, status="fail", failed_action=action.id, reason="missing expected artifacts")
                self.state.running = False
                self.state.screen = ScreenId.REPORT
                return
            self.state.overall_progress = idx / total
            self.state.current_task_progress = 1.0
            self.state.current_task_phase = "done"
            self.state.action_results.append({"id": action.id, "status": "ok", "log_path": action.log_path})
            emit_wizard_event(self.state, "action_finished", action_id=action.id, status="ok", log_path=action.log_path)
            self.log(f"OK action={action.id}")
        self.state.current_step = "ZAKOŃCZONO"
        self.state.current_rationale = "Wszystkie zaplanowane akcje zostały wykonane."
        write_wizard_summary(self.state, status="pass")
        emit_wizard_event(self.state, "wizard_finished", status="pass", runner_report_dir=str(expected_runner_report_dir(self.state.run_id)))
        self.state.running = False
        self.state.screen = ScreenId.REPORT


def wizard_main(stdscr) -> None:
    init_colors()
    curses.curs_set(0)
    stdscr.nodelay(True)
    load_env_local()
    matrix = load_matrix()
    run_id = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    state = WizardState(matrix=matrix, run_id=run_id, run_dir=E2E_DIR / "work" / "wizard" / run_id)
    state.run_dir.mkdir(parents=True, exist_ok=True)
    (state.run_dir / "logs").mkdir(parents=True, exist_ok=True)
    sync_selected_context(state)
    while True:
        check_env(state)
        stdscr.erase()
        if state.screen == ScreenId.WELCOME:
            render_welcome(stdscr, state)
        elif state.screen == ScreenId.SELECTION:
            render_selection(stdscr, state)
        elif state.screen == ScreenId.BACKEND:
            render_backend(stdscr, state)
        elif state.screen == ScreenId.READINESS:
            render_readiness(stdscr, state)
        elif state.screen == ScreenId.EXECUTION:
            render_execution(stdscr, state)
        elif state.screen == ScreenId.REPORT:
            render_report(stdscr, state)
        elif state.screen == ScreenId.HELP:
            render_help(stdscr, state)
        stdscr.refresh()
        try:
            k = stdscr.getch()
        except Exception:  # noqa: BLE001
            k = -1
        if k in {ord("q"), ord("b"), ord("B")}:
            if state.screen == ScreenId.WELCOME:
                break
            elif state.screen == ScreenId.HELP:
                state.screen = state.previous_screen
                state.cursor = 0
            elif state.screen == ScreenId.SELECTION:
                state.screen = ScreenId.WELCOME
                state.cursor = 0
            elif state.screen == ScreenId.BACKEND:
                state.screen = ScreenId.SELECTION
                state.cursor = 0
            elif state.screen == ScreenId.READINESS:
                state.screen = ScreenId.BACKEND
                state.cursor = 0
            elif state.screen == ScreenId.EXECUTION and state.running:
                state.status_msg = "Run nadal trwa; poczekaj albo przerwij terminalem Ctrl+C."
            elif state.screen in {ScreenId.EXECUTION, ScreenId.REPORT}:
                break
        elif k in {10, 13}:
            if state.screen == ScreenId.HELP:
                state.screen = state.previous_screen
                state.cursor = 0
            elif state.screen == ScreenId.WELCOME:
                state.screen = ScreenId.SELECTION
                state.cursor = 0
            elif state.screen == ScreenId.SELECTION:
                state.screen = ScreenId.BACKEND
                state.cursor = 0
            elif state.screen == ScreenId.BACKEND:
                sync_selected_context(state)
                if not state.scenarios_to_run:
                    state.status_msg = "Nie znaleziono pasujących scenariuszy."
                else:
                    refresh_readiness(state)
                    state.execution_plan = build_execution_plan(state)
                    state.screen = ScreenId.READINESS
                    state.cursor = 0
            elif state.screen == ScreenId.READINESS:
                sync_selected_context(state)
                refresh_readiness(state)
                state.execution_plan = build_execution_plan(state)
                if not state.execution_plan:
                    state.status_msg = "Brak akcji do wykonania."
                else:
                    write_wizard_plan_files(state)
                    state.screen = ScreenId.EXECUTION
                    Automator(state).start()
        elif k == ord(" "):
            if state.screen == ScreenId.SELECTION:
                if state.cursor < len(DISTRO_OPTIONS):
                    distro = DISTRO_OPTIONS[state.cursor]
                    if distro in state.selected_distros:
                        if len(state.selected_distros) == 1:
                            state.status_msg = "Zostaw co najmniej jedną dystrybucję."
                        else:
                            state.selected_distros.remove(distro)
                    else:
                        state.selected_distros.append(distro)
                else:
                    idx = state.cursor - len(DISTRO_OPTIONS)
                    if idx < len(TIER_VALUES):
                        state.selected_tier = TIER_VALUES[idx]
                sync_selected_context(state)
            elif state.screen == ScreenId.BACKEND:
                backend = BACKEND_OPTIONS[state.cursor]
                if backend in state.selected_backends:
                    if len(state.selected_backends) == 1:
                        state.status_msg = "Zostaw co najmniej jeden backend."
                    else:
                        state.selected_backends.remove(backend)
                else:
                    state.selected_backends.append(backend)
                sync_selected_context(state)
            elif state.screen == ScreenId.READINESS:
                refresh_readiness(state)
                state.execution_plan = build_execution_plan(state)
                state.status_msg = "Odświeżono readiness."
        elif k in {ord("p"), ord("P")} and state.screen == ScreenId.READINESS:
            refresh_readiness(state)
            portal_actions = [action for action in build_execution_plan(state) if action.kind == "portal_consent"]
            if not portal_actions:
                state.status_msg = "Brak kroku portal consent do uruchomienia."
            else:
                state.execution_plan = portal_actions
                write_wizard_plan_files(state)
                state.screen = ScreenId.EXECUTION
                Automator(state).start()
        elif k in {curses.KEY_UP, ord("k")}:
            state.cursor = max(0, state.cursor - 1)
        elif k in {curses.KEY_DOWN, ord("j")}:
            if state.screen == ScreenId.SELECTION:
                state.cursor = min(len(DISTRO_OPTIONS) + len(TIER_VALUES) - 1, state.cursor + 1)
            elif state.screen == ScreenId.BACKEND:
                state.cursor = min(len(BACKEND_OPTIONS) - 1, state.cursor + 1)
            elif state.screen == ScreenId.READINESS:
                state.cursor = min(max(0, len(state.readiness_cache) - 1), state.cursor + 1)
        elif k in {ord("r"), ord("R")} and state.screen == ScreenId.READINESS:
            refresh_readiness(state)
            state.execution_plan = build_execution_plan(state)
            state.status_msg = "Odświeżono readiness."
        elif k in {ord("v"), ord("V")} and state.screen == ScreenId.READINESS:
            state.detail_view = not state.detail_view
            state.status_msg = "Widok szczegółowy." if state.detail_view else "Widok kompaktowy."
        elif k == ord("?"):
            if state.screen != ScreenId.HELP:
                state.previous_screen = state.screen
                state.screen = ScreenId.HELP
                state.cursor = 0
        time.sleep(0.05)


def main() -> int:
    curses.wrapper(wizard_main)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
