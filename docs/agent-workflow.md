# Agent Workflow (Master + Release + Version Branch)

This document defines the Git workflow for agents and automation.

## Goals

- Keep `home` (GitLab, CI/build triggers) and `origin` (GitHub public) in sync.
- Keep release process predictable and reproducible.
- Keep feature work isolated and version-scoped.

## Branch Model

- `master`:
  - main integration branch (current active product line).
  - should always be push-synced to both remotes.
- `release`:
  - release branch used only for release prep/cut.
  - no direct feature development.
- `0.1.1` (example version branch):
  - version-scoped integration branch for the next line.
  - created from `master` when starting the version cycle.
- `0.1.1/feature/<name>`:
  - short-lived feature branches for that version.

## Recommended Flow

1. Start version line:
   - create `0.1.1` from `master`.
2. Feature work:
   - branch from `0.1.1` as `0.1.1/feature/<slug>`.
   - merge back into `0.1.1` (PR/MR).
3. Promote to main:
   - merge `0.1.1` -> `master` when ready.
4. Release cut:
   - fast-forward `release` from `master` at selected commit.
   - tag release on `release` (for example `v0.1.1` or `v0.1.1-rc1`).
5. Mirror:
   - push `master`, `release`, and tags to both `home` and `origin`.

## Sync Rules (Critical)

- After each merge to `master`, run mirror push to both remotes.
- Do not let `origin/master` and `home/master` drift.
- Do not let `origin/release` and `home/release` drift.
- If drift happens, fix immediately before next feature merge.

## Operational Commands

Create version branch:

```bash
git fetch --all --prune
git branch -f 0.1.1 origin/master
git push origin 0.1.1
git push home 0.1.1
```

Feature branch:

```bash
git checkout 0.1.1
git pull --ff-only origin 0.1.1
git checkout -b 0.1.1/feature/my-feature
```

Mirror after merge to master:

```bash
git checkout master
git pull --ff-only origin master
git push origin master
git push home master
```

Release cut:

```bash
git checkout release
git merge --ff-only master
git tag v0.1.1
git push origin release --tags
git push home release --tags
```

## Guardrails

- Prefer `--ff-only` for promotion branches.
- Avoid force-push on `master` and `release` (protect these branches).
- Use PR/MR reviews for merges into `0.1.1`, `master`, and `release`.
- Version branch names should follow SemVer lanes (`0.1.1`, `0.1.2`, ...), not shorthand (`1.11`).
