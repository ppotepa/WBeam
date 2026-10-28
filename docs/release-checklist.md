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

See also `docs/release-checklist.md` and epic #101.
