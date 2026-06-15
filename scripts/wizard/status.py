from __future__ import annotations

from .model import StepStatus

TERMINAL_STATUSES = {
    StepStatus.OK,
    StepStatus.WARN,
    StepStatus.FAIL,
    StepStatus.BLOCKED,
    StepStatus.SKIPPED,
    StepStatus.REBOOT_REQUIRED,
}

SUCCESS_STATUSES = {
    StepStatus.OK,
    StepStatus.WARN,
    StepStatus.SKIPPED,
}

HARD_FAILURE_STATUSES = {StepStatus.FAIL}

USER_ACTION_STATUSES = {
    StepStatus.BLOCKED,
    StepStatus.REBOOT_REQUIRED,
}


def is_terminal(status: StepStatus) -> bool:
    return status in TERMINAL_STATUSES


def is_success_for_ci(status: StepStatus) -> bool:
    return status in SUCCESS_STATUSES


def is_blocking(status: StepStatus) -> bool:
    return status in HARD_FAILURE_STATUSES | USER_ACTION_STATUSES


def scenario_status_from_steps(statuses: list[StepStatus]) -> str:
    if any(status == StepStatus.FAIL for status in statuses):
        return "fail"
    if any(status in {StepStatus.BLOCKED, StepStatus.REBOOT_REQUIRED} for status in statuses):
        return "blocked"
    return "pass"
