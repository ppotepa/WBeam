# Agents Workflow (Canonical)

This file is the canonical workflow for agents and automation in WBeam.

## 1) Repository Roles

- `origin` (GitHub) is the source of truth for code and documentation.
- `home` (GitLab) is used for CI/build/release execution.
- Direct day-to-day development goes through GitHub branches/PRs.

## 2) Branch Model

Long-lived branches:
- `master`: integration and stable development line.
- `release`: release/build branch used to cut and publish release artifacts.
- `v0.1.1` (example): version-scoped branch for a release line.

Branch hygiene rules:
- No long-lived ad-hoc branches like `trainerv2`.
- After work is merged or moved into version branch, delete temporary branches.
- Keep branch set minimal and readable.

## 3) Development Flow

1. Create feature branch from version branch (`v0.1.1`) or from `master`.
2. Open PR in GitHub.
3. Merge into target branch via review.
4. Promote to `master` when ready.
5. Fast-forward or merge into `release` when preparing artifacts.

## 4) Release Flow

- Release artifacts are built from `release` and/or version tags.
- Release notes must follow `release.template.md`.
- Do not publish fragile/sensitive information in release notes or assets.

## 5) Versioning Rules

- One pipeline => one shared version for all artifacts.
- Artifact set must be coherent (`deb`, `rpm`, `apk`, `aarch64`, `VERSION.txt`).
- Rolling release assets must be replaced so old-version duplicates do not accumulate.

## 6) Security and Privacy

Never place in public release assets/notes:
- tokens, passwords, keys, private certificates
- private infrastructure credentials or sensitive identifiers
- confidential internal operational details

## 7) GitHub -> GitLab Sync

- GitHub remains authoritative.
- GitLab is synchronized from GitHub by local sync automation.
- If drift appears, align GitLab branches to GitHub state.

## 8) Operational Guardrails

- Avoid force-push on protected branches (`master`, `release`) except emergency alignment.
- Keep branch protection enabled.
- Keep docs under `docs/` and update workflow docs together with process changes.
- Legacy archive paths are removed from canonical runtime and CI flows.

## 9) Repository Structure Source Of Truth

- Canonical repository structure and migration state are defined in:
  - `docs/repo-structure.md`
- Domain boundaries are anchored by:
  - `android/README.md`
  - `host/README.md`
  - `desktop/README.md`
  - `shared/README.md`
- Layout sanity check:
  - `scripts/ci/check-repo-layout.sh`
