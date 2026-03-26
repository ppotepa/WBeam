# Development Workflow

## Branch Model

Version-line branches: `v0.1.2/base` is the current integration target.

Working branch format:

```
v0.1.2/e<epic>/i<issue>-<slug>
```

Example: `v0.1.2/e30/i31-hygiene`

## Execution Model

- One issue = one branch = one PR/MR.
- Child issues execute sequentially inside the active epic.
- Close child issue only after merged PR/MR.
- Close epic only after all child issues are closed.

## Commit Messages

```
e<epic>-i<issue>: <short action>
```

## PR/MR Titles

```
[e<epic>-i<issue>] <short title>
```

## CI Pipeline

Stages: `quality` → `build` → `publish`

Quality gates (run on every push):
- `scripts/ci/check-repo-layout.sh`
- `scripts/ci/check-boundaries.sh`
- `scripts/ci/validate-e2e-matrix.sh`
- Sonar scan (on master, if token configured)

Build artifacts:
- `.deb` and `.rpm` packages (`scripts/ci/build_deb.sh`, `scripts/ci/build_rpm.sh`)
- aarch64 tarball (`scripts/ci/build_aarch64_tar.sh`)
- Android APK release (`scripts/ci/build_apk_release.sh`)

## Versioning

Tagged builds: tag name (strip `v` prefix).  
Release branch: `0.1.2.${PIPELINE_IID}.${SHORT_SHA}`.  
Other branches: `0.0.0.${DEFAULT_BRANCH}.${PIPELINE_IID}.${SHORT_SHA}`.

Host/APK compatibility version must match. Desktop UI version is not a compatibility gate.

Verify with: `./wbeam version doctor`

## Local Dev Loop

```bash
./redeploy-local          # full rebuild + deploy + launch
./wbeam host debug        # debug daemon with colored logs
./wbeam android deploy-all # build + push APK to all devices
./wbeam watch streaming   # live stream state per device
```

## Validation Commands

```bash
cd host/rust && cargo check -p wbeamd-core
cd host/rust && cargo check -p wbeamd-server
cd host/rust && cargo test -p wbeamd-streamer --quiet
cd android && ./gradlew :app:compileDebugJavaWithJavac --no-daemon --quiet
cd desktop/apps/desktop-tauri && npm run build
```
