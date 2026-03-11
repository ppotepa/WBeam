# GitLab Runners and Release Pipeline for WBeam

This document describes how the local GitLab environment for `WBeam` is configured, how CI runners execute jobs, and how build artifacts are published to GitHub Releases.

No secrets are included here.

## 1. Overview

The setup uses:

- Local GitLab CE in Docker (LAN-accessible)
- GitLab Runner in Docker (Docker executor)
- Project: `root/wbeam`
- Default branch: `master` (or whichever branch is configured as default in GitLab)
- Pipeline config: `.gitlab-ci.yml`
- Build/release scripts in `scripts/ci/`

Main objective:

- On push to default branch: build artifacts and publish them to GitHub release `<default-branch>-latest` (pre-release)
- On tag `vX.Y.Z`: build artifacts and publish them to GitHub release `vX.Y.Z`
- All artifacts in one pipeline share one common `WBEAM_VERSION`

## 2. Services

Expected container names:

- `devlab-gitlab`
- `devlab-gitlab-runner`

Typical port exposure on the host:

- `80` -> GitLab HTTP (redirects to HTTPS)
- `443` -> GitLab HTTPS
- `2224` -> Git over SSH

GitLab URL (LAN):

- `https://192.168.100.208`

## 3. Repository and Branch Model

Remote repository in local GitLab:

- `ssh://git@192.168.100.208:2224/root/wbeam.git`

Branch behavior:

- CI release logic follows `CI_DEFAULT_BRANCH`
- In your current setup, default branch is `master`

## 4. CI/CD Variables (Project-level)

The pipeline expects these GitLab CI variables:

- `GH_TOKEN` (masked): GitHub token with permissions to create/update releases and upload assets
- `GH_OWNER`: GitHub owner/org (for example `ppotepa`)
- `GH_REPO`: GitHub repo name (for example `WBeam`)

Notes:

- Do not hardcode token values in repository files
- Keep `GH_TOKEN` masked and project-scoped

## 5. Pipeline Files

### 5.1 `.gitlab-ci.yml`

Defines stages:

- `build`
- `publish`

Jobs:

1. `build_deb`
- Image: `rust:bookworm`
- Builds binaries with Cargo
- Packs `.deb`
- Artifacts: `dist/*.deb`

2. `build_rpm`
- Image: `fedora:41`
- Builds binaries with Cargo
- Packs `.rpm`
- Artifacts: `dist/*.rpm`

3. `build_aarch64_tar`
- Image: `rust:bookworm`
- Cross-builds `aarch64-unknown-linux-gnu`
- Produces `.tar.gz`
- Artifacts: `dist/*aarch64*.tar.gz`
- `allow_failure: true` (optional artifact)

4. `build_apk_release`
- Image: `eclipse-temurin:17-jdk-jammy`
- Installs Android SDK command-line tools in CI
- Builds Android `release` APK via Gradle
- Passes `WBEAM_BUILD_REV=$WBEAM_VERSION` so APK version matches all other artifacts
- Artifacts: `dist/*.apk`

5. `publish_github_release`
- Image: `alpine`
- Publishes artifacts from `dist/` to GitHub release

Rules:

- Runs on default branch and tags

### 5.2 CI scripts in `scripts/ci/`

- `common.sh`: version helpers and shared paths
- `build_deb.sh`: creates Debian package
- `build_rpm.sh`: creates RPM package
- `build_aarch64_tar.sh`: creates aarch64 tarball
- `build_apk_release.sh`: creates Android release APK
- `publish_github_release.sh`: GitHub API release create/update + asset upload

Versioning:

- Each job uses one shared `WBEAM_VERSION`
- default branch example format: `0.0.0.<default-branch>.<pipeline_iid>.<short_sha>`
- tag pipelines use the tag value directly (for example `v1.2.3` -> `1.2.3` for package versions where needed)

## 6. GitHub Release Strategy

Implemented behavior:

- For default branch pipelines:
  - Release tag: `<default-branch>-latest` (for example `master-latest`)
  - Type: pre-release
  - Existing assets with same names are replaced

- For tag pipelines (`vX.Y.Z`):
  - Release tag: exact Git tag
  - Type: stable release
  - Assets are uploaded to that release

## 7. Runner Configuration

Runner type:

- Docker executor

Runner registration target:

- GitLab URL over HTTPS (`https://192.168.100.208`)

TLS trust for self-signed cert:

- Runner CA file path inside container:
  - `/etc/gitlab-runner/certs/192.168.100.208.crt`

Config file inside runner container:

- `/etc/gitlab-runner/config.toml`

Important:

- If `config.toml` is missing, jobs stay `pending`
- Registering the runner creates/updates this file

## 8. Expected Flow on Push to Default Branch

1. Developer pushes commit to default branch (currently `master`)
2. GitLab creates a pipeline
3. Runner picks jobs in order:
   - `build_deb`
   - `build_rpm`
   - `build_aarch64_tar` (optional)
   - `build_apk_release`
4. Build artifacts are collected in `dist/`
5. `publish_github_release` updates/creates `<default-branch>-latest` and uploads files
6. GitHub release page shows latest artifacts

## 9. Expected Flow on Tag `vX.Y.Z`

1. Tag is pushed to GitLab
2. Tag pipeline starts
3. Build jobs produce artifacts
4. Publish job creates/updates release `vX.Y.Z` on GitHub
5. Versioned assets are attached to that release

## 10. Troubleshooting

### 10.1 Jobs stuck in `pending`

Check:

- Runner registered and online
- `config.toml` exists in runner container
- Runner assigned to project / not paused
- Runner has compatible tags (if tags are used)

Useful checks:

- `docker logs devlab-gitlab-runner`
- `docker exec devlab-gitlab-runner gitlab-runner list`

### 10.2 TLS/Certificate errors between runner and GitLab

Symptoms:

- x509/self-signed errors

Fix:

- Ensure GitLab cert is copied to runner certs path
- Register runner with `--tls-ca-file` pointing to that cert
- Restart runner container if needed

### 10.3 Publish step fails to GitHub

Check:

- `GH_TOKEN` exists and is valid
- Token has repository release write permissions
- `GH_OWNER` and `GH_REPO` match target repository

### 10.4 Build dependency failures

The build jobs install required dependencies at runtime. If packages move or are renamed in base images, update package lists in `.gitlab-ci.yml`.

## 11. Security Notes

- Never commit tokens, passwords, or private keys
- Keep `GH_TOKEN` masked in GitLab variables
- Prefer least-privilege GitHub token scope
- Rotate tokens periodically

## 12. Operational Notes

- This setup is optimized for LAN/local build infrastructure
- GitHub is used as artifact distribution endpoint
- `<default-branch>-latest` is intentionally mutable (rolling latest)
- Tagged releases are immutable version references in practice

## 13. File Reference Summary

- CI definition: `/home/ppotepa/git/WBeam/.gitlab-ci.yml`
- CI scripts directory: `/home/ppotepa/git/WBeam/scripts/ci`
- This document: `/home/ppotepa/git/WBeam/gitlab.runners.md`
