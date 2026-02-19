# WBeam

kde first on ubuntu
android app like superdisplay for windows
usb cable is first must

mvp in progress

## debug mode (no systemd)

Run daemon in foreground with full debug logging to file:

```bash
./host/scripts/run_wbeamd_debug.sh 5001 5000
```

Logs are written to:

```bash
host/rust/logs/wbeamd-debug-YYYYMMDD-HHMMSS.log
```
