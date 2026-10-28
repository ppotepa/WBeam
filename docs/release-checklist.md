# Physical Android release acceptance — WBeam 0.1.3

**VM/TCP smoke does not prove that the Android application renders frames.**
The existing `e2e/matrix.json` retains legacy X11 scenarios for historical
debugging, but **only EVDI and Wayland Portal** qualify for release support.
Release acceptance is collected **separately** and never automatically marked
passing by a simulated VM client.

## Required release matrix

| Linux host | EVDI | Wayland Portal |
| --- | --- | --- |
| Fedora 43 / GNOME Wayland | physical Android + EVDI soak | physical Android + Portal consent |
| Ubuntu 24.04 / GNOME Wayland | physical Android + EVDI soak | physical Android + Portal consent |

For each run, record `uname -r`, distro, Android API level, SHA-256 of the
ADB serial (not the raw serial), SHA-256 of the **installed package artifact**,
full tested commit and actual build revision. Use a tablet and a phone across
the matrix; enumerate device form factors in the QA notes.

Use the **built DEB/RPM/APK**, not a source checkout. Collect evidence in a
private/controlled directory. Each check must be marked `pass` only after
completing the operation and saving a nonempty log, screenshot or report file.

The required checks are: clean package install; signed APK install and upgrade;
60 seconds with **real rendered frames on Android**; 1800 seconds streaming;
20 connect/disconnect cycles; USB unplug/replug; host daemon restart and
recovery; plus missing EVDI diagnostics (EVDI) or denied ScreenCast permission
(Portal). Any failure/skip blocks the claimed support for that matrix pair.

## Collection and validation

The existing USB soak helper can provide telemetry during 30-minute tests:

```bash
WBEAM_SOAK_LOG_DIR=/path/to/private/reports host/scripts/soak_usb_30m.sh 1800
```

Capture ADB logcat, host status, daemon metrics, actual screen evidence, and
package details. **Review and redact private data before attaching evidence**.
Do not commit APK signing secrets, raw USB serials, device properties or
sensitive logcat dumps to a public repository.

Start with `e2e/release-acceptance.example.json`, create four matrix entries,
and replace every `pending` with evidence-backed `pass`. Keep all evidence
paths relative to the acceptance JSON file and within its directory.

```bash
python3 e2e/scripts/validate_hardware_acceptance.py \
  /path/to/qa/hardware-acceptance.json --release 0.1.3-rc.1
python3 -m unittest discover -s e2e/tests -v
```

The validator checks required fields, physical/VM identification, 20 cycles,
1800-second soak, SHA-256 metadata, four environment/backend combinations
and presence of evidence files. It cannot independently prove that a person
actually used physical hardware: a named QA signoff and review remain
mandatory. A missing report must block stable release approval.

See epic #101 for the full task graph and Definition of Done.

## Publication gate — RC versus stable

The release publisher is **manual** and requires protected CI approval and a
completed QA report. It does not automatically create a stable release from
pushing to the `release` branch.

1. Merge and run all mandatory quality jobs, including native build, Android
   unit tests, and the package asset checks. The source version and
   `VERSION.txt` revision must match the commit being released.
2. Configure protected GitLab CI variables `GH_TOKEN`, `GH_OWNER`,
   `GH_REPO`, and release signing variables documented in
   `docs/ANDROID_RELEASE_SIGNING.md`. Keep signing keys out of git and logs.
3. Have a named QA reviewer complete the physical matrix with real Linux
   package/APK artifacts. Place `hardware-acceptance.json` and its relative
   evidence directory in a private CI job workspace/artifact mount. Set
   `WBEAM_HARDWARE_ACCEPTANCE_FILE` to the accessible report path. A lone JSON
   file without the referenced evidence is rejected.
4. Obtain a maintainer decision on the declared MIT licensing and add the
   appropriate root `LICENSE` file. The publisher refuses to run without it.
5. Set protected `WBEAM_RELEASE_APPROVED=1` for the manual publication job
   **after** reviewing CI results and the hardware evidence.
6. Run `scripts/ci/check-release-artifacts.sh`: exactly one signed APK,
   at least one DEB and RPM, matching `VERSION.txt` and Android
   `versionCode`. Both distro-native DEBs are mandatory: **Bookworm**
   (linked with libevdi0) and **Ubuntu Noble 24.04** (linked with libevdi1),
   plus RPM and APK. Verify `dpkg-deb -f`, `ldd`, clean installs and
   generated `SHA256SUMS.txt`.
7. Trigger **manual** GitLab `publish_github_release` for the tested commit.
   Version tags `v0.1.3-rc.1` / `v0.1.3-beta.1` publish with
   `prerelease=true`. Only `v0.1.3` and similar stable semantic version tags
   publish with `prerelease=false`. A release-branch build updates
   `release-latest` as a prerelease, never an automatic stable tag.
8. Tagged releases are immutable: publishing an existing version/tag or
   replacing an existing asset fails. Produce a **new** tag for the next
   release, and record its checksums and known limitations.

After publishing, install the downloaded DEB/RPM/APK on a **clean** machine,
compare the published SHA256 checksums and validate USB capture/reconnection.
Do not fast-forward `master` automatically as part of an unreviewed branch
push. The optional manual GitLab fast-forward job must be used only after
stable signoff and branch review.

**Known unmet gates as of this draft:** no signed APK and clean-package test
artifacts were built in this session, no physical QA report exists, and a root
`LICENSE` file is not present in the audited repository. These are blockers,
not completed tasks. Refer to epic #101 and tasks #114–#120.


## Lokalna weryfikacja, gdy GitHub Actions nie przydziela runnerów

Nie zastępuje to odbioru na sprzęcie ani podpisywania APK, ale pozwala
**wykonać kod**, a nie polegać na pustych statusach GitHub Actions (#142).

Z katalogu głównego WBeam:

```bash
# Bez instalowania narzędzi ani instalacji kernela:
bash scripts/ci/run-local-verification.sh --list

# Bash, Python/E2E unit i desktop Node/TypeScript:
bash scripts/ci/run-local-verification.sh --quick

# Dodatkowo host/Tauri Cargo i Android Gradle (Rust/SDK wymagane):
bash scripts/ci/run-local-verification.sh --full

# Własne miejsce zapisu:
bash scripts/ci/run-local-verification.sh --full --report-dir ~/wbeam-qa
```

Raport trafia domyślnie do
`$XDG_STATE_HOME/wbeam/verification/<UTC timestamp>/`
(lub `~/.local/state/wbeam/verification/...`), w plikach
`summary.tsv`, `metadata.txt` (w tym commit SHA) i oddzielnych logach.

`PASS` oznacza wykonany test, `FAIL` błąd wykonania, `BLOCKED` brak
wymaganego narzędzia/katalogu. Skrypt zwraca kod `0` tylko gdy wszystkie
wybrane testy przeszły; `1` gdy któryś nie przeszedł i `2` przy samych
brakujących prerekwizytach. Nie podaje fałszywego „PASS” na brak `cargo`,
Android SDK albo nieskuteczne `npm ci`.

**Przed umieszczeniem logów w publicznym issue:** usuń numery seryjne ADB,
identyfikatory urządzeń, adresy sieciowe, dane użytkowników oraz wszelkie
sekrety. W #116 i #101 podaj wynik `summary.tsv`, commit SHA oraz
niesensytywne fragmenty logów. Po zaliczeniu tych testów nadal wykonaj
physical hardware matrix i sprawdzenie podpisanych artefaktów.

## Bramka kompilacji EVDI streamera

Jakość hosta Rust nie może być oceniana wyłącznie przez testy
`wbeamd-api` i `wbeamd-core`. Natywny streamer ma zależności
`gstreamer` i `libevdi.so` oraz oddzielne FFI/linker, dlatego
GitLab wymaga jobu **`quality_rust_streamer`** przed
`build_apk_release` i pakietami. GitHub Actions wykonuje oba polecenia
w jobie Rust host; lokalny `run-local-verification.sh --full` zapisuje
oddzielny `host_streamer.log`.

```bash
cargo check --locked --manifest-path host/rust/Cargo.toml \
  -p wbeamd-streamer --no-default-features --features evdi
cargo test --locked --manifest-path host/rust/Cargo.toml \
  -p wbeamd-streamer --no-default-features --features evdi
```

Na Debianie potrzebne są kompatybilne pakiety deweloperskie GStreamera
i `libevdi0-dev`. Na Ubuntu Noble budować przeciwko właściwemu ABI
`libevdi1`/`libevdi-dev` (zob. #144). Zielony
`cargo check` nie potwierdza jeszcze działania DKMS/Fedora kernel
6.19 ani faktycznej transmisji EVDI do Androida: sprzętowe DoD
#117/#120 pozostają obowiązkowe.


## Installed daemon log storage (issue #154)

Package installations place read-only files in `/usr/share/wbeam`.
The **Rust daemon**, not only the Tauri desktop, must write logs outside
the package tree. With `<WBEAM_ROOT>/VERSION.txt` present (DEB/RPM),
`wbeamd-server` resolves its default log directory to:

- `$XDG_STATE_HOME/wbeam/logs` when `XDG_STATE_HOME` is an absolute path;
- otherwise `$HOME/.local/state/wbeam/logs`.

The `--log-dir PATH` CLI argument always takes precedence. Source-tree
runs retain their historical `<repo>/host/rust/logs` default.
If neither state-home path is available for an installed daemon, startup
must fail with an actionable error; it must not attempt to write under
`/usr/share/wbeam`.

Validate `bash scripts/ci/test-daemon-log-location.sh`, the Rust
`daemon_log_dir_tests` and a **clean package install under an ordinary
non-root user**. Confirm `systemctl --user status wbeamd-rust`,
`ss -ltnp | grep ':5001'`, and `ls -l ~/.local/state/wbeam/logs`.
The port should bind on loopback by default as in issue #151. Record
any failed startup with a redacted journal excerpt under issue #154.
