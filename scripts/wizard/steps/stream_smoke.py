from __future__ import annotations

from pathlib import Path

from ..context import WizardContext
from ..model import StepDefinition, StepPlan, StepResult, StepStatus
from ..shell import run_logged
from ..state import read_json, step_log_path


STREAM_MODES = {
    "benchmark_game": {"capture_backend": None, "display_mode": "benchmark_game", "label": "Headless benchmark smoke"},
    "wayland_portal": {"capture_backend": "wayland_portal", "display_mode": "virtual_monitor", "label": "Wayland portal stream"},
    "evdi": {"capture_backend": "evdi", "display_mode": "duplicate", "label": "VDI/EVDI stream"},
    "x11_gst": {"capture_backend": "x11_gst", "display_mode": "duplicate", "label": "X11 GStreamer fallback"},
}


def health_url(ctx: WizardContext) -> str:
    return f"http://127.0.0.1:{ctx.control_port}/v1/health"


def status_url(ctx: WizardContext) -> str:
    return f"http://127.0.0.1:{ctx.control_port}/v1/status"


def metrics_url(ctx: WizardContext) -> str:
    return f"http://127.0.0.1:{ctx.control_port}/v1/metrics"


def start_url(ctx: WizardContext, mode: dict) -> str:
    query = f"display_mode={mode['display_mode']}"
    if mode["capture_backend"]:
        query += f"&capture_backend={mode['capture_backend']}"
    return f"http://127.0.0.1:{ctx.control_port}/v1/start?{query}"


class StreamSmokeStep:
    definition = StepDefinition(id="stream_smoke", title="Run stream smoke test", requires=("service_setup",), timeout_sec=600)

    def stream_backend(self, ctx: WizardContext) -> str:
        return str(ctx.env.get("WBEAM_E2E_BACKEND") or ctx.backend)

    def plan(self, ctx: WizardContext) -> StepPlan:
        backend = self.stream_backend(ctx)
        return StepPlan(
            id=self.definition.id,
            title=self.definition.title,
            summary="Run the existing guest stream smoke helper.",
            commands=[[str(ctx.root_dir / "e2e" / "scripts" / "guest-stream-smoke.sh"), backend, "60"]],
            expected_artifacts=[
                f"stream/{backend}/summary.json",
                f"stream/{backend}/client.json",
                f"stream/{backend}/status-after.json",
                f"stream/{backend}/metrics-after.json",
                f"stream/{backend}/portal-probe.json",
                f"stream/{backend}/pipewire-probe.json",
                f"stream/{backend}/session-probe.json",
                f"stream/{backend}/virtual-probe.json",
                f"stream/{backend}/ports.txt",
            ],
            risks=["Requires a running daemon and a healthy control API."],
            next_action="Inspect the stream helper log if smoke fails.",
        )

    def probe(self, ctx: WizardContext) -> StepResult:
        return self.run(ctx)

    def run(self, ctx: WizardContext) -> StepResult:
        log_path = step_log_path(ctx.run_dir, self.definition.id)
        backend = self.stream_backend(ctx)
        if ctx.dry_run:
            return StepResult(
                id=self.definition.id,
                title=self.definition.title,
                status=StepStatus.OK,
                summary="DRY-RUN: stream smoke would run.",
                log_path=log_path,
                evidence={"commands": self.plan(ctx).commands},
            )
        if ctx.skip_service:
            return StepResult(
                id=self.definition.id,
                title=self.definition.title,
                status=StepStatus.SKIPPED,
                summary="Stream smoke skipped because service setup is disabled.",
                log_path=log_path,
                evidence={"backend": backend},
            )
        helper = ctx.root_dir / "e2e" / "scripts" / "guest-stream-smoke.sh"
        env = dict(ctx.env)
        env["WBEAM_E2E_GUEST_ROOT"] = str(ctx.root_dir)
        env["WBEAM_E2E_BACKEND"] = backend
        env["WBEAM_E2E_DISPLAY_MODE"] = STREAM_MODES.get(backend, STREAM_MODES["benchmark_game"])["display_mode"]
        env["WBEAM_E2E_CONTROL_PORT"] = str(ctx.control_port)
        env["WBEAM_E2E_STREAM_PORT"] = str(ctx.stream_port)
        env["WBEAM_E2E_REPORT_DIR"] = str(ctx.run_dir / "stream" / backend)
        exit_code = run_logged([str(helper), backend, "60"], cwd=ctx.root_dir, log_path=log_path, env=env, dry_run=False, timeout_sec=self.definition.timeout_sec)
        stream_dir = ctx.run_dir / "stream" / backend
        summary_path = stream_dir / "summary.json"
        stream_summary = read_json(summary_path)
        reason_code = str(stream_summary.get("reason_code") or "")
        blocked = bool(stream_summary.get("blocked")) or reason_code == "portal_consent_required"
        if exit_code == 0 and stream_summary.get("ok") is True:
            status = StepStatus.OK
            summary = "Stream smoke completed."
            next_action = ""
        elif blocked:
            status = StepStatus.BLOCKED
            summary = "Wayland portal consent required."
            next_action = str(
                stream_summary.get("next_action")
                or "Run ./e2e/run prepare-portal-consent --distro fedora-43 --session gnome-wayland --backend wayland_portal --live --promote"
            )
        else:
            status = StepStatus.FAIL
            summary = f"Stream smoke failed with exit code {exit_code}."
            next_action = "" if exit_code == 0 else f"Open {log_path} and rerun --from-step stream_smoke."
        return StepResult(
            id=self.definition.id,
            title=self.definition.title,
            status=status,
            summary=summary,
            log_path=log_path,
            exit_code=exit_code,
            next_action=next_action,
            evidence={
                "backend": backend,
                "display_mode": env["WBEAM_E2E_DISPLAY_MODE"],
                "health_url": health_url(ctx),
                "status_url": status_url(ctx),
                "metrics_url": metrics_url(ctx),
                "start_url": start_url(ctx, STREAM_MODES.get(backend, STREAM_MODES["benchmark_game"])),
                "summary_path": str(summary_path),
                "reason_code": reason_code,
                "blocked": blocked,
                "client": str(stream_dir / "client.json"),
                "status_after": str(stream_dir / "status-after.json"),
                "metrics_after": str(stream_dir / "metrics-after.json"),
                "portal_probe": str(stream_dir / "portal-probe.json"),
                "pipewire_probe": str(stream_dir / "pipewire-probe.json"),
                "session_probe": str(stream_dir / "session-probe.json"),
                "virtual_probe": str(stream_dir / "virtual-probe.json"),
                "ports": str(stream_dir / "ports.txt"),
            },
        )

    def validate(self, ctx: WizardContext) -> StepResult:
        return self.run(ctx)
