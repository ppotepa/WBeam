# proto_x11 android

Scripts in this directory build/deploy Android app with separate package id for X11 prototype work.

## commands

```bash
./proto_x11/android/build_x11_apk.sh
./proto_x11/android/deploy_x11.sh
```

## env overrides used

- `WBEAM_ANDROID_PACKAGE=com.wbeam.x11`
- `WBEAM_ANDROID_APP_ID_SUFFIX=.x11`
- `WBEAM_ANDROID_APP_NAME=WBeam X11`

This keeps the default app (`com.wbeam`) untouched while allowing side-by-side install for prototype testing.
