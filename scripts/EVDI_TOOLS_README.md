# EVDI Scripts

Full documentation: [../docs/EVDI_SETUP_GUIDE.md](../docs/EVDI_SETUP_GUIDE.md)

## evdi-setup.sh

Automated EVDI install and configuration. Detects distro, installs
dependencies, compiles module, configures persistence and permissions.

```bash
sudo bash scripts/evdi-setup.sh                   # standard install
sudo bash scripts/evdi-setup.sh --dry-run          # preview only
sudo bash scripts/evdi-setup.sh --skip-diagnostic  # headless/CI
```

## evdi-diagnose.sh

Diagnostic tool. Checks module, device node, library, permissions, and
WBeam configuration.

```bash
bash scripts/evdi-diagnose.sh              # quick check
bash scripts/evdi-diagnose.sh --verbose    # detailed output
bash scripts/evdi-diagnose.sh --fix        # with fix recommendations
```

Exit 0 = all checks passed; non-zero = number of errors found.
