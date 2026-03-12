# Workload Plan (Epics)

Date: 2026-03-12
Branch: v0.1.1/base

## 1. Issue Strategy (Execution Model)

Decision:
- We do not use one giant issue for all work.
- We use one epic issue per area, then small execution issues under each epic.

Why:
- Better review scope and lower merge risk.
- Easier rollback and cleaner history.
- Clear ownership and progress tracking.

Execution format:
- Epic issue: goal, scope, DoD, dependencies.
- Child issues: 1 concrete change set each (small PR/MR).
- Order is strict: finish one epic before moving to the next.

Current status:
- Epic #30 exists: Repo/Runtime Hygiene.
- Next step: create child issues for #30 and execute them one-by-one.

## 2. Epic #30 Breakdown (Step-by-step issues)

Parent Epic:
- #30 Repo/Runtime Hygiene

Child issues in execution order:
1. #31 Hygiene inventory and cleanup rules
2. #32 Harden `.gitignore` for runtime/build artifacts
3. #33 Remove stale helper/leftover assets
4. #34 Stabilize CI hygiene checks after cleanup
5. #35 Final cleanup report and close Epic #30

## Execution Order

1. Repo/Runtime Hygiene
2. Single Source of Truth for Profiles/Config
3. Legacy Decoupling (`archive/legacy`)
4. Wrapper & Lane Policy Unification
5. Public API Contract + Docs Alignment
6. SRP Refactor of Hotspots

## Epic Details

## 1) Repo/Runtime Hygiene
Goal:
- Remove runtime/build leftovers and stale helper artifacts.
- Tighten `.gitignore` and repository hygiene checks.
- Establish a clean baseline before deeper refactors.

Why first:
- Lowest risk, immediate readability improvement.
- Reduces accidental regressions in later epics.

Definition of Done:
- Unnecessary artifacts removed or ignored.
- CI hygiene checks pass.
- No behavior change in runtime paths.

## 2) Single Source of Truth for Profiles/Config
Goal:
- Define one canonical source for training/runtime profile data.
- Remove conflicting fallbacks and duplicated profile semantics.

Why now:
- This is foundational for daemon, trainer, and UI consistency.

Definition of Done:
- One canonical profile/config path documented and enforced.
- Runtime and trainer read the same authoritative data model.

## 3) Legacy Decoupling (`archive/legacy`)
Goal:
- Stop active runtime/CI dependencies on `archive/legacy`,
  or explicitly reclassify it as supported legacy lane.

Why after #2:
- Canonical profile/config handling must be stable first.

Definition of Done:
- Clear boundary: true archive or supported lane.
- No hidden production dependency on archive paths.

## 4) Wrapper & Lane Policy Unification
Goal:
- Reduce duplicated policy logic across shell wrappers.
- Keep daemon as authority for runtime decisions.

Why after #3:
- Legacy exceptions reduced, easier consolidation.

Definition of Done:
- Shared policy utilities or normalized flow in wrappers.
- Less duplicated session/env/probe logic.

## 5) Public API Contract + Docs Alignment
Goal:
- Align OpenAPI and docs with real runtime behavior.
- Remove drift between implementation and documented contracts.

Why now:
- Should document stable target behavior, not transition state.

Definition of Done:
- API docs match implemented endpoints and payloads.
- Key operational docs updated and consistent.

## 6) SRP Refactor of Hotspots
Goal:
- Split large orchestration files into focused modules.
- Improve maintainability and lower change risk.

Why last:
- Largest scope and highest risk; best done on stable architecture.

Definition of Done:
- Target files reduced in scope and complexity.
- Behavior preserved with smoke/integration checks.

## Notes
- Epics are executed one-by-one in listed order.
- Epic #1 starts immediately.
- Tracking rule: each child issue links to its epic and is closed by its own PR/MR.
