# Device Lab 2.0

Device Lab 2.0.1 is the current candidate on `main`. It keeps the original application ID (`il.devicelab`) so it can upgrade the existing Device Lab installation. The redesigned Hebrew-first dashboard includes bottom navigation, light/dark/system themes, the supplied launcher logo, estimate labeling, and local JSON export.

## Included in version 2.0.1
- Device manufacturer, model, Android version, API level, board, hardware and supported ABIs
- System RAM overview
- Internal data partition, emulated user storage and removable storage volumes are reported separately where StorageManager exposes them
- Battery level, charging status and temperature when reported by Android
- Uptime and app-process CPU time
- Estimated whole-device/per-core CPU utilization from two `/proc/stat` snapshots where readable
- CPU frequency files, thermal-zone raw values (negative values flagged invalid), sensor list, media codec types, camera IDs, and system feature list where exposed
- Export a local JSON diagnostic report through Android's document picker
- No Internet permission and no root requirement

## Build an APK
The GitHub Actions workflow in .github/workflows/android-apk.yml builds a debug APK on pushes to main and supports manual runs.

Open Actions → Build Device Lab APK → latest successful run → Artifacts → DeviceLab-debug-apk. Download the ZIP, extract app-debug.apk, and install it on the phone.

## Important limitations
Android restricts access to some hardware telemetry. Whole-device CPU utilization, official battery health percentage and battery cycle count are not claimed unless the operating system exposes reliable supported APIs. The merged build still needs final on-device verification on the target M36 Plus / Android 13 device. Background monitoring is not included as a verified feature; it has not completed a successful overnight test.

## Build verification
GitHub Actions verifies compilation and uploads the APK. Successful compilation does not replace on-device runtime testing. The app should be treated as a candidate until navigation, launcher icon, storage display, and JSON export are verified on the target player.
