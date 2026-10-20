#!/usr/bin/env python3
"""Fail CI if Android's merged manifest contains an unapproved permission."""
import pathlib
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
manifest_root = root / "app" / "build" / "intermediates" / "merged_manifest"
candidates = list(manifest_root.rglob("AndroidManifest.xml")) if manifest_root.exists() else []
if not candidates:
    candidates = list((root / "app" / "build" / "intermediates").rglob("AndroidManifest.xml"))
if not candidates:
    raise SystemExit("ERROR: merged AndroidManifest.xml not found after build")
manifest = sorted(candidates, key=lambda p: len(str(p)))[0]
android_name = "{http://schemas.android.com/apk/res/android}name"
permissions = set()
for item in ET.parse(manifest).getroot().iter():
    if item.tag in ("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"):
        name = item.attrib.get(android_name)
        if name:
            permissions.add(name)
allowed = {
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.WAKE_LOCK",
}
extra = permissions - allowed
missing = allowed - permissions
print(f"Merged manifest: {manifest}")
print("Merged permissions:")
for permission in sorted(permissions):
    print(f" - {permission}")
if extra:
    raise SystemExit("ERROR: unapproved permissions present: " + ", ".join(sorted(extra)))
if missing:
    raise SystemExit("ERROR: expected permissions missing: " + ", ".join(sorted(missing)))
print("PASS: merged manifest contains exactly the four approved permissions.")
