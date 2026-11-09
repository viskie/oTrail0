# Installable builds

## 0.2.4 — 2026-11-05

`oTraiL-0.2.4-debug.apk` — source commit `3811a1d`, versionCode 6.

- In-app online driving route preview, destination search/coordinates/map points, written turns, remaining distance/time and rate-limited automatic rerouting. No voice guidance or live traffic.
- Cached active routes can resume after restart and be followed offline; new paths/reroutes still need internet.
- Daily/manual update checks use `releases/latest.json`, not GitHub Releases. APK size/SHA-256/package/version/signing identity checked before opening Android's user-confirmed installer.
- Journal schema stays at 2. Route caches and updater files are separate from GPS fixes, stops, maps and backups. Signing certificate matches 0.2.3.

Validation: 29 passing JVM tests, Android lint without errors, debug app/instrumentation APK compilation. Live synthetic routing API + production parser smoke test: 244 points / 20 written maneuvers. Phone/road/UI/installer testing is pending because no device is connected; uploaded to GitHub as requested. The new journal-invariance instrumentation check is compiled but not executed. Install over the existing app to preserve its data; do not uninstall.

[Usage, integrity, release-manifest maintenance and offline routing options](../docs/0.2.4-navigation-updates.md).

## 0.2.3 — 2026-11-05

`oTraiL-0.2.3-debug.apk` — source commit `b767cec`.

- Additive SQLite v1 → v2 migration, stop address enrichment and historical backfill; enriched backup/GeoJSON compatibility.
- Full collected trail by default, viewport fitted to its extent. Short plausible gaps drawn in amber; inference recorded only in logs, no estimate label on the map or invented GPS samples.
- Sequential popup fix with stable ownership across redraws; historic-place and navigation action lists remain visible in Android dialogs.
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

## 0.2.6 — 2026-11-05

`oTraiL-0.2.6-debug.apk`: stop arrival recovery from saved stationary fixes, green display-only gap connections, history lifecycle/viewport safeguards, embedded BRouter and native Valhalla with settings selection and graph download/import progress. BRouter has a regional public download source; Valhalla requires a configured compatible India graph .tar URL or imported pack. No voice. No map or routing datasets bundled. Same signature and database schema. 34 JVM tests and lint pass; device checks remain pending. See docs/0.2.6-offline-routing-continuity.md.

## 0.2.7 — 2026-11-05

Fixes map repository destruction when switching/reparenting views, the confirmed cause of trail/navigation crashes in phone logs. Stop detail dialogs and a main-screen Stops list provide independent access to saved details. Confirmed stops tolerate GPS accuracy and require sustained departure evidence. Adds a private last-crash report with copy action. Database schema and signature retained. See docs/0.2.7-crash-stop-hotfix.md for device validation and backup notes.

## 0.3.0 — 2026-11-06

31 fixed repeated-pass trail colors with green reserved for inferred continuity; total/selected-trail/current-drive kilometres; historical per-stop leg distance and elapsed average speed; cache-first tapped road references/names and moving-mode roadside landmarks/places of worship from OpenStreetMap. Adds a derived metrics table without replacing source data. Signed in-place upgrade. 45 JVM tests and lint pass; app and instrumentation build. New device/online-source checks remain pending because no phone is connected. Details: docs/0.3.0-trail-metrics-road-context.md.

## 0.3.1 — 2026-11-06

Grey fullscreen UI, purple buttons with magenta text, floating Locate, improved overlapping trail colors. Editable UiConfig.java for fonts, sizes, colors and UI language/copy. Corrects oversized offline city labels. 47 JVM checks and lint pass; installed and checked on POCO. Existing recorded data retained. Source configuration/build instructions: docs/edit-ui-config.md. Native startup/dialog resources are generated from the same configuration. Same signing certificate; upgrade in place. No map datasets bundled.

## 0.3.2 — 2026-11-06

Conservative GPS departure confirmation, stop recovery after stable reception and restart, audited duplicate-stop reconciliation, uncertain-signal status and anchored live dot. Shared journal pool and read-only backups. Configurable native text, dialogs, selectors, popups and offline map labels; manual color choices retained. Raw GPS rows and stop IDs preserved. 52 JVM checks, lint and 24 phone checks pass. Same certificate; upgrade in place. See docs/0.3.2-gps-quality-ui.md and docs/edit-ui-config.md.

## 0.3.3 — 2026-11-06

GPS uncertainty handling and stable-signal stop recovery, including drifted duplicate open stops. Raw GPS, saved stop IDs and labels retained with repair audit logs. Weak-signal status, anchored dot and unreliable-speed suppression. INK now controls native text; offline road/city labels have configurable colors. 52 JVM checks, lint and 24 phone checks pass. Installed and data integrity verified on POCO. Same signing certificate; install in place. See docs/0.3.3-stop-reconciliation.md.

## 0.3.4 — 2026-11-07

More vibrant gradients, magenta-edged cyan buttons, bold labels, colored status/navigation panels and cycling settings-card accents. Existing manual colors, fonts/sizes, grey base, map labels, dots and trail palette retained. Finish controls in UiConfig.java. No journal or tracking changes. 52 JVM checks and lint pass. Phone unavailable over ADB; device installation/visual checks pending. Same signing certificate for in-place upgrade. Details: docs/0.3.4-vibrant-finish.md.

## 0.3.5 — 2026-11-07

Centered 18sp destination, bottom-pinned landscape stats, three-column settings buttons, larger italic fonts and vivid gradients. Recorded stops show distance and average speed since previous stop. Long stops and big offline cities are red italic; overlapping stop/city labels use bold italics. Zoom-scaled offline city names. Installed on POCO with all raw fixes and stop IDs retained. 53 JVM checks and lint pass. Same signing certificate for in-place upgrade. Details: docs/0.3.5-layout-stops.md.

## 0.3.6 — 2026-11-07

Compact destination headings: Bijnor: UP-246701; repeated India suffix omitted. Stored destination data unchanged. Includes centered heading, bottom landscape stats, three-column settings, stop distances and red italic long-stop/city labels from 0.3.5. 57 JVM checks and lint pass. Same signing certificate for in-place upgrade.

## 0.3.7 — 2026-11-07

Map dropdown text increased by 2sp. Total stats footer now neon green bold italic on a dark background. Retains compact Bijnor: UP-246701 heading, centered navigation, bottom landscape stats, three-column settings and recorded-stop distance styling. 57 JVM checks and lint pass. Same signing certificate; upgrade in place.

## 0.3.8 — 2026-11-07

Black navigation panel with purple, magenta, violet, cyan and turquoise spectrum text. Only one automatic long-stop label; it hides when a marker info bubble opens. Stop dots and full recorded-stop details retained. 57 JVM checks and lint pass. Same signing certificate; in-place upgrade.

## 0.3.9 — 2026-11-07

First three navigation lines use solid turquoise text without gradients on the black panel. Single automatic stop label behavior retained. 57 JVM checks and lint pass. Same signing certificate for in-place upgrade.

## 0.3.10 — 2026-11-07

Status/Stopped panel is black with cyan text and a white speed value. Floating Locate text replaced with the current app logo inside a black hexagon with cyan icon and magenta edge. Recenter behavior and accessibility retained. 57 JVM checks and lint pass. Same signing certificate; upgrade in place.

## 0.3.11 — 2026-11-07

Live status speed now uses metres per hour (m/hr), converting metres/second by 3600. A known stop always displays 0 m/hr, including weak or stale GPS. Distance units and stored journal measurements unchanged. 57 JVM checks and lint pass. Same signing certificate for in-place upgrade.

## 0.3.12 — 2026-11-07

No Mod1 editor/build tools included. Adds planning-only modules folder/API and Settings catalog, without executable extensions. Current drive is neon pink; history palette and green inferred gaps retained. Destination heading visibly bold. Locate logo/regular hexagon 40% smaller with 3D bevel, retaining full touch target. 61 JVM checks and lint pass. Same signing certificate; upgrade in place. See docs/0.3.12-modules-ui.md.

## 0.4.0 — 2026-11-07

Signed downloadable module host and separately downloadable GOD 1.0.0 (`.oTrail0`). In-app source editor, on-phone Java compiler toolchain, signing-key import and Android Keystore SSH publishing. No Termux/external builder. First compiler retains resources/native libraries and rejects resource/build-system edits. 64 JVM checks and lint pass; full compiler smoke test passes on the build computer. Host installed over ADB, journal and India map retained. On-phone compilation/publishing validation pending. See docs/0.4.0-god.md.

## 0.4.1 — 2026-11-08

CAM host support with additive photo-index migration. Download CAM separately from Extension modules; no module implementations are bundled in the APK. Camera/hexagonal locate controls sit together at map top right. Photo albums are available from map stop bubbles and recorded-stop details. APK/module upgrades retain installed modules, old versions, settings, workspaces and existing journal. GOD 1.0.2 has three-column landscape buttons and atomic automatic drafts. 70 JVM checks/lint, isolated on-phone photo migration/recovery checks and real POCO camera/photo/stop-link viewing passed. See docs/0.4.1-cam.md.

## 0.4.2 — 2026-11-08

CAM uses a symmetrical regular pentagon, scaled to the hexagonal Locate icon with the same touch target. Both sit in the map's top-right icon bar, which scrolls when future controls exceed available width. Palette aliases and spacing are editable in UiConfig. Version code 25 allows devices on the 0.4.1 validation build to receive this update. Existing module settings/data and prior versions are retained. See docs/0.4.2-map-icons.md.

## 0.4.3 — 2026-11-08

Base-app WTF! `!` toggle centers the live GPS dot and rotates the map so travel stays on the requested upper-right 1/0.8 slope. Existing navigation follows the same locked camera. True-north arrow, short-arc turns, weak/stopped-GPS heading hold, persistent mode/zoom and history suspension. Disabling restores north-up. Modules, data and settings retained. See docs/0.4.3-wtf-camera.md.

## 0.4.4 — 2026-11-08

WTF! `!` becomes a regular octagon, normalized to the navigation/CAM icon scale and 56dp touch target. Enabled/disabled state, bevel and ripple retained. Camera slope/behavior, modules and data unchanged. See docs/0.4.4-wtf-octagon.md.

## 0.4.5 — 2026-11-09

Persistent GPS status popup above the live dot with speed, four-decimal coordinates, locality/road and cached/online nearby worship distances. Strongly bold destination heading. Solid rounded route/trail strokes. WTF! diagonal mirrored toward upper-left. Journal, modules, settings and map downloads retained. See docs/0.4.5-live-gps-bubble.md.

## 0.4.6 — 2026-11-09

Zero-padding compact GPS bubble, uniform 11sp text and available values only; lookup diagnostics move to the side-panel notes box. Fresh accurate GPS gets a subtle smile without changing position/size. Five navigation actions move into a collapsible left drawer with matching gradient shading and a top-left floating menu icon. Place name alone remains bold. See docs/0.4.6-compact-popup-menu.md.

## 0.4.7 — 2026-11-09

GPS popup 40% narrower than measured phone UI with a 3dp thicker border. Named worship sites and nearest police station share the side-panel results list. Clickable places confirm before automatic navigation, preserving the current route if calculation fails. Persistent navigation history can be viewed/reloaded from the left menu. See docs/0.4.7-named-nearby-navigation.md.
