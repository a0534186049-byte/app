# Device Lab 2.1.0

Device Lab 2.1.0 is the current version on `main`. It keeps the original application ID (`il.devicelab`) so it can upgrade the existing Device Lab installation. The redesigned Hebrew-first dashboard includes bottom navigation, light/dark/system themes, the supplied launcher logo, estimate labeling, and local JSON export.

## Included in version 2.1.0
- Device manufacturer, model, Android version, API level, board, hardware and supported ABIs
- System RAM overview
- Internal data partition, emulated user storage and removable storage volumes are reported separately where StorageManager exposes them
- Battery level, charging status and temperature when reported by Android
- Live whole-device battery current from BatteryManager, refreshed every two seconds
- Shortcut to Android system battery-usage breakdown by app when the device firmware exposes that screen; this is Android-attributed estimated usage, not instantaneous per-app current
- Uptime and app-process CPU time
- Estimated whole-device/per-core CPU utilization from two `/proc/stat` snapshots where readable
- CPU frequency files, thermal-zone raw values (negative values flagged invalid), sensor list, media codec types, camera IDs, and system feature list where exposed
- Export a local JSON diagnostic report through Android's document picker
- No Internet permission and no root requirement

## Build an APK
The GitHub Actions workflow in .github/workflows/android-apk.yml builds a debug APK on pushes to main and supports manual runs.

Open Actions → Build Device Lab APK → latest successful run → Artifacts → DeviceLab-debug-apk. Download the ZIP, extract app-debug.apk, and install it on the phone.

## Important limitations
Android restricts access to some hardware telemetry. Per-app battery attribution is available through Android’s own system battery-usage screen where the firmware exposes it. A regular third-party app cannot read BatteryStats per-UID data directly because `BATTERY_STATS` is a signature/privileged/development permission and the relevant `BatteryStatsManager` query is hidden from the public SDK. Device Lab therefore shows live whole-device current separately and never invents per-app milliamp readings. Whole-device CPU utilization, official battery health percentage and battery cycle count are not claimed unless the operating system exposes reliable supported APIs. The merged build still needs final on-device verification on the target M36 Plus / Android 13 device. Background monitoring is not included as a verified feature; it has not completed a successful overnight test.

## Build verification
GitHub Actions verifies compilation and uploads the APK. Successful compilation does not replace on-device runtime testing. The app should be treated as a candidate until navigation, launcher icon, storage display, and JSON export are verified on the target player.
