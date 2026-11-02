# Installable builds

## 0.1.0 — 2026-11-02

`oTraiL-0.1.0-debug.apk` — Android 11+, personal debug build. Built from source commit shown by the preceding source commit in Git history. Install with `adb install -r releases/oTraiL-0.1.0-debug.apk`.

- India map viewport; OpenStreetMap roads, Esri satellite, Mapsforge offline rendering.
- Foreground GPS recorder, normalized SQLite logs, >2-minute stop detector, history and GeoJSON export.
- Phone-only optional Mapsforge India (~1.5 GiB) and Geofabrik India raw PBF (~1.6 GiB) downloads, size/status/progress controls, cancellation/retry and manual update logs.
- External navigation handoff; no embedded turn-by-turn engine. PBF is source data only.
- No map dataset is included in this APK or repository.

Validation: debug assembly, six stop-detector unit tests, Android lint with no errors; installed and launched over Wi-Fi ADB on POCO model 26020PCB4I / Android 15. Long-duration GPS/battery performance and full India pack download remain device tests.

APK snapshots are committed here, not published as GitHub Releases. Debug signing keys are local to the build environment and are not in Git. Save/export trails before uninstalling; builds signed with a different debug key cannot update the existing app in place.
