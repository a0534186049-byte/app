# Device Lab

Device Lab is a Hebrew-first Android device diagnostics dashboard with a clean, modern interface.

## Included in version 1.0.0
- Device manufacturer, model, Android version, API level, board, hardware and supported ABIs
- System RAM overview
- Available and used internal storage
- Battery level, charging status and temperature when reported by Android
- Uptime and app-process CPU time (explicitly not represented as whole-device CPU usage)
- Export a local JSON diagnostic report through Android's document picker
- No Internet permission and no root requirement

## Build an APK
The GitHub Actions workflow in .github/workflows/android-apk.yml builds a debug APK on pushes to main and supports manual runs.

Open Actions → Build Device Lab APK → latest successful run → Artifacts → DeviceLab-debug-apk. Download the ZIP, extract app-debug.apk, and install it on the phone.

## Important limitations
Android restricts access to some hardware telemetry. Whole-device CPU utilization, official battery health percentage and battery cycle count are not claimed unless the operating system exposes reliable supported APIs. The first build should be tested on the target M36 Plus / Android 13 device.

## Build verification
The APK is not considered ready until GitHub Actions completes successfully and produces the DeviceLab-debug-apk artifact.
