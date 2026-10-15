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
The GitHub Actions workflow in .github/workflows/android-apk.yml builds a debug APK on every push to main and can be run manually.

Open Actions, choose Build Device Lab APK, open the latest successful run, and download the DeviceLab-debug-apk artifact. Extract app-debug.apk and install it on the phone.

## Important limitations
Android restricts access to some hardware telemetry. Whole-device CPU utilization, official battery health percentage and battery cycle count are not claimed unless the operating system exposes reliable supported APIs. The first build should be tested on the target M36 Plus / Android 13 device.
