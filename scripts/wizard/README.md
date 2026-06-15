# Wizard contract

This package holds the shared step contract for WBeam installation and E2E
orchestration.

It is intentionally neutral:

- no direct dependency on `e2e/scripts/runner.py`
- no shell command execution
- no UI assumptions

The contract is expressed through:

- `StepDefinition` for stable step identity and metadata
- `StepPlan` for intended work
- `StepResult` for observed outcome
- `WizardContext` for run-time inputs and paths

The initial migration keeps `scripts/install-wizard.sh` intact. This package is
the compatibility layer that future wizard, CLI, and E2E implementations can
share.
