from __future__ import annotations

from ..context import WizardContext
from ..model import StepDefinition, StepPlan, StepResult, StepStatus
from ..providers.apt import AptProvider
from ..providers.base import DistroInfo
from ..providers.detect import detect_distro
from ..providers.fedora import FedoraProvider
from ..state import step_log_path


class UnsupportedProvider:
    name = "unsupported"

    def probe(self, ctx: WizardContext) -> StepResult:
        return StepResult(
            id="system_deps",
            title="Install system dependencies",
            status=StepStatus.WARN,
            summary="No system dependency provider is implemented for this distro yet.",
            evidence={"provider": self.name},
            next_action="Use a supported distro or wait for the provider to be implemented.",
        )

    def plan_install(self, ctx: WizardContext, distro: DistroInfo) -> StepPlan:
        return StepPlan(
            id="system_deps",
            title="Install system dependencies",
            summary="No supported system dependency provider is available yet.",
            commands=[],
            expected_artifacts=[],
            risks=["The current distro is not supported by the wizard yet."],
            next_action="Use Fedora, Ubuntu, or Debian once a provider is available.",
        )

    def install(self, ctx: WizardContext, distro: DistroInfo, log_path: Path) -> StepResult:
        return StepResult(
            id="system_deps",
            title="Install system dependencies",
            status=StepStatus.WARN if ctx.dry_run else StepStatus.FAIL,
            summary="No system dependency provider is implemented for this distro yet.",
            log_path=log_path,
            evidence={"provider": self.name, "distro": distro.id, "package_manager": distro.package_manager},
            next_action="Use a supported distro or add a provider implementation.",
        )

    def validate(self, ctx: WizardContext, distro: DistroInfo) -> StepResult:
        return self.probe(ctx)


def provider_for_distro(distro: DistroInfo):
    if distro.id.startswith("fedora"):
        return FedoraProvider()
    if distro.id.startswith("ubuntu") or distro.id.startswith("debian") or distro.package_manager == "apt":
        return AptProvider()
    return UnsupportedProvider()


class SystemDepsStep:
    definition = StepDefinition(id="system_deps", title="Install system dependencies", requires=("host_preflight",), timeout_sec=3600)

    def _provider(self, ctx: WizardContext) -> tuple[object, DistroInfo]:
        distro = detect_distro()
        return provider_for_distro(distro), distro

    def probe(self, ctx: WizardContext) -> StepResult:
        provider, distro = self._provider(ctx)
        return provider.probe(ctx)

    def plan(self, ctx: WizardContext) -> StepPlan:
        provider, distro = self._provider(ctx)
        return provider.plan_install(ctx, distro)

    def run(self, ctx: WizardContext) -> StepResult:
        provider, distro = self._provider(ctx)
        log_path = step_log_path(ctx.run_dir, self.definition.id)
        result = provider.install(ctx, distro, log_path)
        result = StepResult(
            id=self.definition.id,
            title=self.definition.title,
            status=result.status,
            summary=result.summary,
            log_path=result.log_path or log_path,
            started_at=result.started_at,
            ended_at=result.ended_at,
            duration_sec=result.duration_sec,
            exit_code=result.exit_code,
            next_action=result.next_action,
            evidence={**result.evidence, "distro": distro.id, "package_manager": distro.package_manager},
        )
        return result

    def validate(self, ctx: WizardContext) -> StepResult:
        provider, distro = self._provider(ctx)
        result = provider.validate(ctx, distro)
        return StepResult(
            id=self.definition.id,
            title=self.definition.title,
            status=result.status,
            summary=result.summary,
            log_path=result.log_path,
            started_at=result.started_at,
            ended_at=result.ended_at,
            duration_sec=result.duration_sec,
            exit_code=result.exit_code,
            next_action=result.next_action,
            evidence={**result.evidence, "distro": distro.id, "package_manager": distro.package_manager},
        )
