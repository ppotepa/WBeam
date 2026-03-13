# Archive/Legacy Policy Boundary

Date: 2026-03-12
Epic: #48 Legacy Decoupling (`archive/legacy`)
Issue: #49

## Final decision
`archive/legacy` is a **true archive**, not an active runtime lane.

## Policy rules

1. Runtime code paths (daemon/streamer/UI wrappers) must not depend on `archive/legacy/*`.
2. CI structure checks may verify archive presence as historical assets, but must not require archive files as runtime prerequisites.
3. Any legacy execution path must be explicit opt-in compatibility tooling outside default runtime flow.
4. Default user workflows (`./wbeam`, desktop apps, trainer flow) must operate without archive dependencies.

## Why this model

- Matches domain-first layout and current canonical ownership.
- Reduces hidden dependencies and maintenance cost.
- Makes repository intent clear for open-source contributors.

## Transition plan

### #50 (checks + docs alignment)
- Update docs to mark `archive/legacy` as historical-only.
- Align CI/layout/boundary wording and expectations with true-archive model.

### #51 (reference cleanup)
- Remove/isolate remaining active references to `archive/legacy` in scripts/runtime paths.
- Keep behavior unchanged for canonical flow.

### #52 (compat guardrails)
- If any compatibility path remains, require explicit opt-in flag and clear warning.
- No silent fallback to archive resources.

## Acceptance outcome for #49
- Policy is explicit, stable, and actionable for implementation issues (#50-#52).
