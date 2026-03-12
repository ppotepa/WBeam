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
- Epic #30 completed: Repo/Runtime Hygiene.
- Child issues #31-#35 completed and closed.
- Epic #37 completed: Single Source of Truth for Profiles/Config.
- Child issues #38-#42 completed.
- Next step: execute Epic #3 child issues one-by-one.

## 2. Epic #30 Breakdown (Step-by-step issues)

Parent Epic:
- #30 Repo/Runtime Hygiene
  - https://github.com/ppotepa/WBeam/issues/30

Child issues in execution order:
1. #31 Hygiene inventory and cleanup rules
   - https://github.com/ppotepa/WBeam/issues/31
   - Status: completed (inventory file: `docs/hygiene-inventory-2026-03-12.md`)
2. #32 Harden `.gitignore` for runtime/build artifacts
   - https://github.com/ppotepa/WBeam/issues/32
   - Status: completed (`.gitignore` cache/coverage/profiler hardening)
3. #33 Remove stale helper/leftover assets
   - https://github.com/ppotepa/WBeam/issues/33
   - Status: completed (removed `scripts/diagnostics/audodaignose`, kept dir placeholder)
4. #34 Stabilize CI hygiene checks after cleanup
   - https://github.com/ppotepa/WBeam/issues/34
   - Status: completed (repo-layout, boundaries, e2e-matrix all green)
5. #35 Final cleanup report and close Epic #30
   - https://github.com/ppotepa/WBeam/issues/35
   - Status: completed (`docs/hygiene-report-2026-03-12.md`)

## 3. Epic #37 Breakdown (Step-by-step issues)

Parent Epic:
- #37 Single Source of Truth for Profiles/Config
  - https://github.com/ppotepa/WBeam/issues/37

Child issues in execution order:
1. #38 Profile/config source inventory and mapping
   - https://github.com/ppotepa/WBeam/issues/38
   - Status: completed (`docs/profile-config-inventory-2026-03-12.md`)
2. #39 Define canonical profile/config contract
   - https://github.com/ppotepa/WBeam/issues/39
   - Status: completed (`docs/profile-config-canonical-contract-2026-03-12.md`)
3. #40 Implement canonical loading and remove fallback drift
   - https://github.com/ppotepa/WBeam/issues/40
   - Status: completed (core loader canonicalized + wizard export path fixed)
4. #41 Align API profile surface with canonical model
   - https://github.com/ppotepa/WBeam/issues/41
   - Status: completed (`docs/openapi.yaml` profile/config alignment)
5. #42 Final report and close Epic #37
   - https://github.com/ppotepa/WBeam/issues/42
   - Status: completed (`docs/epic-37-final-report-2026-03-12.md`)

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

Current state (as-is):
- `archive/legacy/` is still part of expected repository layout and checks.
- Legacy lane assets are present and can be confused with active runtime paths.
- Canonical runtime profile source was already moved away from archive fallback in Epic #37.
- Boundary between "historical archive" and "supported compatibility lane" is still not formalized.

Epic 3 scope (to-do):
1. Decide policy: `archive/legacy` is either:
   - true archive (no active runtime/CI dependencies), or
   - explicitly supported compatibility lane (renamed/documented as such).
2. Apply policy to CI/layout checks and docs:
   - align `docs/repo-structure.md`, `AGENTS.md`, workflow notes.
3. Remove or isolate any remaining runtime/operational references to archive paths.
4. Add explicit compatibility guardrails:
   - if compatibility lane remains, gate it with explicit opt-in and documentation.
5. Add final verification pass:
   - repo layout checks, boundary checks, e2e matrix checks.

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
