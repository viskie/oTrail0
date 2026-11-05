# Installable builds

## 0.2.3 — 2026-11-05

`oTraiL-0.2.3-debug.apk` — source commit `ebd72cb`.

- Additive SQLite v1 → v2 migration, stop address enrichment and historical backfill; enriched backup/GeoJSON compatibility.
- Full collected trail by default, viewport fitted to its extent. Short plausible gaps drawn in amber; inference recorded only in logs, no estimate label on the map or invented GPS samples.
- Sequential popup fix with stable ownership across redraws.
- Nearby 200–600-year-old places from OSM source dates, gold name highlights and an offline result cache. Provider availability/coverage and age metadata are not guaranteed.
- Keep foreground recording, reboot/update recovery, map packs, cellular downloads and Drive backup.

Validation: 14 JVM tests, 14 on-phone checks in a separate validation app, Android lint without errors, debug assembly. Installed over 0.2.2 without uninstalling. Confirmed foreground GPS recording, full journal display (over 33,000 fixes / 36 stops), and existing Mapsforge India rendering. Pre-upgrade database/WAL/preferences archive retained on the phone in Downloads/oTraiL. Live lookup coverage is separate from deterministic tests.

See [file-by-file change guide](../docs/0.2.3-changes.md).

## 0.2.2 — 2026-11-02

Automatically replaces stalled zero-byte legacy Wi-Fi-only jobs with metered-network requests. Shows specific Android pause/retry reasons instead of guessing network state. Installed on connected phone; legacy request migrated and Android download job active on validated metered Wi-Fi.

## 0.2.1 — 2026-11-02

`oTraiL-0.2.1-debug.apk` enables map downloads over Wi-Fi or cellular (metered networks). Roaming remains disabled. Previously queued Wi-Fi-only jobs retain their original Android download policy; cancel/retry those to use cellular. Debug assembly passed.

## 0.2.0 — 2026-11-02

`oTraiL-0.2.0-debug.apk` — always-on GPS recording, Android 11+.

- Recording automatically starts after location permission; Live / History are display states.
- GPS request interval: 5 seconds, no distance threshold. Persistent notification and START_STICKY service.
- Boot-after-unlock and package-update receivers restart the service with background location permission.
- Configurable Google Drive / Files trail-backup document via Android Save picker. Backup now, automatic snapshots approximately every 15 minutes, and transactional merge restore.
- Snapshot includes normalized trips, fixes, stops, update logs, map/offline preferences and dataset status references. It does not include map datasets.
- Completed new map downloads stay in shared Downloads/oTraiL; import the retained .map after reinstall. Regrant permissions and select the saved trail backup after uninstall/reinstall. In-progress download jobs are not restored after uninstall.

Validation: six JVM stop-detector tests, Android lint (no errors), debug app/test assembly, five backup checks on the connected phone in isolated storage (round trip, merge deduplication, malformed-input rollback, stops and logs). Installed 0.2.0 over 0.1.0. Verified the foreground location service automatically started from the package-replaced receiver while the UI was closed. Background location + notifications granted; Android battery-optimization exemption applied. Full physical reboot and real Google Drive upload remain untested. Drive must be selected by the user in Maps & downloads; no cloud account or folder is preconfigured.

## 0.1.0 — 2026-11-02

`oTraiL-0.1.0-debug.apk` — Android 11+, personal debug build. Built from source commit shown by the preceding source commit in Git history. Install with `adb install -r releases/oTraiL-0.1.0-debug.apk`.

- India map viewport; OpenStreetMap roads, Esri satellite, Mapsforge offline rendering.
- Foreground GPS recorder, normalized SQLite logs, >2-minute stop detector, history and GeoJSON export.
- Phone-only optional Mapsforge India (~1.5 GiB) and Geofabrik India raw PBF (~1.6 GiB) downloads, size/status/progress controls, cancellation/retry and manual update logs.
- External navigation handoff; no embedded turn-by-turn engine. PBF is source data only.
- No map dataset is included in this APK or repository.

Validation: debug assembly, six stop-detector unit tests, Android lint with no errors; installed and launched over Wi-Fi ADB on POCO model 26020PCB4I / Android 15. Long-duration GPS/battery performance and full India pack download remain device tests.

APK snapshots are committed here, not published as GitHub Releases. Debug signing keys are local to the build environment and are not in Git. Save/export trails before uninstalling; builds signed with a different debug key cannot update the existing app in place.
