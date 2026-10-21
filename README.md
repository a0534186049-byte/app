# Device Lab 2.0

Device Lab 2.0 is a Hebrew-first Android diagnostics dashboard with a redesigned interface, bottom navigation, light/dark/system theme selection, measured-versus-estimated labeling, and local JSON export. Development takes place on `device-lab-v2`; `main` remains the stable 1.0.0 app until v2 has been tested on the target device.

## Included in version 2.0.1 (candidate)
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
Android restricts access to some hardware telemetry. Whole-device CPU utilization, official battery health percentage and battery cycle count are not claimed unless the operating system exposes reliable supported APIs. The first build should be tested on the target M36 Plus / Android 13 device.

## Build verification
The APK is not considered ready until GitHub Actions completes successfully and produces the DeviceLab-debug-apk artifact.
