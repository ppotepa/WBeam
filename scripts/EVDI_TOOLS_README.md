# EVDI Scripts

Full documentation: [../docs/EVDI_SETUP_GUIDE.md](../docs/EVDI_SETUP_GUIDE.md)

## Fedora 43

Use the Fedora-specific bootstrap first. It handles DisplayLink RPM fallback,
Secure Boot MOK enrollment state, `libevdi` linker lookup, and Android/GStreamer
host dependencies:

```bash
./redeploy-local
# or directly:
scripts/fedora-setup.sh --yes --with-evdi
```

## evdi-setup.sh

Distro-neutral EVDI install and configuration helper. Detects distro, installs
dependencies, compiles module, configures persistence and permissions. On
Fedora 43 prefer `scripts/fedora-setup.sh --yes --with-evdi`.

```bash
sudo bash scripts/evdi-setup.sh                   # standard install
sudo bash scripts/evdi-setup.sh --dry-run          # preview only
sudo bash scripts/evdi-setup.sh --skip-diagnostic  # headless/CI
```

## evdi-diagnose.sh

Diagnostic tool. Checks module, device node, library, permissions, and
WBeam configuration. On Secure Boot systems it also reports whether the DKMS
MOK key is enrolled or waiting for reboot/firmware enrollment.

```bash
bash scripts/evdi-diagnose.sh              # quick check
bash scripts/evdi-diagnose.sh --verbose    # detailed output
bash scripts/evdi-diagnose.sh --fix        # with fix recommendations
```

Exit 0 = all checks passed; non-zero = number of errors found.
