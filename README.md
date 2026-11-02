# oTraiL · India

Personal Android GPS journal for life on the road. Android 11+, targeting Android 15. Map data is never bundled in the APK or committed. All dataset download options run on the phone through Android DownloadManager.

## First run

Open oTraiL once and grant **precise location**, **Allow all the time**, and notifications. Recording starts automatically; there are no Record/Stop mode controls. Live and History affect only the map display. A persistent location foreground service requests GPS every **5 seconds**, with zero minimum movement; Android/GPS delivery can vary. START_STICKY handles ordinary process reclamation. Boot completion (after first unlock) and app replacement both restart tracking when background-location permission is granted. No account or telemetry.

On POCO/HyperOS, enable Background autostart and set App info → Battery → No restrictions. Force-stop, revoked permission, power-off and manufacturer restrictions cannot be overridden by an ordinary app. Reopen oTraiL after force-stop. Boot auto-start is enabled by default; no separate record-mode setting is needed.

### Persistence and Google Drive backup

Updates installed over the existing app and phone restarts preserve the trail database and download state. **Uninstall deletes app-private storage and permissions.** Configure backup before uninstalling.

In Maps & downloads → Choose backup location, use Android's Save picker to select **Google Drive**, open your desired folder and save `otrail-backup.json`; a persistent Documents folder on the phone is also supported. This uses the installed Drive document provider, not embedded account credentials or a backend. If Drive is not offered, enable/install its document provider. The app retains document access and writes a normalized trail snapshot approximately every 15 minutes while tracking; **Back up trail now** writes immediately. The provider controls upload/offline sync, so a successful file write is not proof of completed cloud upload. Verify Drive sync before uninstalling.

Backup includes trips, fixes, stops, update logs, map-provider/offline preferences and completed dataset status references. Coordinates retain WGS84, UTC timestamps and original units. Restore from Google Drive / Files merges the snapshot into current history, remaps trip IDs, skips already-present fixes/stops, preserves new fixes, and rolls back malformed/incomplete backups. Restored journeys are historical; current recording continues. Backup writes to the selected file replace its content: do not manually edit it during a backup.

After reinstall, open the app, regrant permissions, choose **Restore trail from Google Drive / Files**, and select the existing snapshot. Then configure a **new backup target file** for subsequent automatic backups (file permissions cannot silently survive uninstall). Data since the last successful backup is not recoverable after uninstall.

New completed dataset downloads are retained in shared **Downloads/oTraiL** outside app-private storage. After reinstall, import the retained India `.map` through the file picker to rebuild the private rendering copy; no redownload is necessary. Raw PBF also stays in Downloads. Active in-progress download jobs cannot be guaranteed across uninstall; retry those after reinstall. Retained dataset versions can be managed in the phone Files app. The backup is for trail/log/settings data and does not upload multi-GB map files to Drive.

## Providers

| Provider | Use | Phone download |
| --- | --- | --- |
| OpenStreetMap | Online roads + normal viewed-tile cache | No bulk downloads on public tile server |
| Mapsforge / OSM | Full India offline vector map, rendered on-device | Manual Wi-Fi or cellular download/update, about 1.5 GiB |
| Geofabrik / OSM | Optional India raw PBF source data | Manual Wi-Fi or cellular download/update, about 1.6 GiB; **not rendered or used for routing yet** |
| Esri World Imagery | Online satellite + common trail/stop overlays | No offline pack |
| Google Maps / OsmAnd | External navigation handoff | Installed app manages its own data |

Maps & downloads shows estimated size before download, server-reported size/progress during download, queued/paused/failure/ready status, cancellation, retry, manual updates and timestamped logs. No automatic map updates. Downloads survive closing oTraiL. A failed candidate retains the installed map. India `.map` import is also supported. Allow enough free phone storage for the old map plus downloaded candidate plus validation copy (roughly 4.6 GiB during an update, plus retained older versions in Downloads). The offline-only switch blocks online map requests, but explicit dataset downloads are separate actions.

Full India map: https://download.mapsforge.org/maps/v5/asia/india.map
Raw India data: https://download.geofabrik.de/asia/india-latest.osm.pbf
Attribution: © OpenStreetMap contributors, ODbL. Satellite tiles © Esri / Maxar / Earthstar Geographics and contributors. Third-party territorial representations are preserved as supplied; viewport is bounded approximately to India (6–38°N, 68–98°E), not a boundary definition.

## Tracking and data

`trips`, `fixes`, `stops`, `sync_logs` are separate normalized tables with foreign keys and a trip/time index. Schema version 1. UTC Unix milliseconds, WGS84 decimal latitude/longitude, meters, m/s, degrees. Optional altitude and bearing remain null when unavailable. Source provider is retained. GeoJSON export streams Point features, `[longitude, latitude]`, with fix and stop properties plus trip IDs. Consumers can group fixes by trip/time into lines. Existing 12,000 km of journeys cannot be reconstructed without previous GPS logs; import of historical journeys is a future feature.

A stop requires >120 seconds within a 35 m anchored radius with speed ≤1.5 m/s and accuracy ≤50 m. Gaps >60 seconds break dwell inference. Departure is the last observed stationary fix; signal loss is never counted as additional stopped time. Fixes older than 30 seconds or worse than 100 m accuracy are discarded; >30-second-old location is marked stale on screen. Live state colors: green recording/ready, blue moving, turquoise stopped, cyan historical. Tappable stop markers show arrival and observed duration on every basemap. No fake/demo coordinates.

## Build and deploy

JDK 21 and Android SDK platform 35 required. Use `./gradlew testDebugUnitTest lintDebug assembleDebug`, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Open `in.otrail/.MainActivity`. Wi-Fi ADB must already be paired and connected. Gradle wrapper 9.6.0; AGP 9.4.0. Debug signing is for local testing. APK snapshots and checksums are committed under `releases/`. No signing keys or provider credentials in this repository.

## Scaffold limits

Navigation is handed to Google Maps or OsmAnd; there is no embedded routing engine, rerouting, spoken guidance or offline route graph. Commercial providers such as Mappls/Google/Mapbox require separate SDK credentials and terms before integration. Raw PBF is an optional source archive only. No GPX importer, map delta updates, or validated all-day battery guarantee yet. Drive provider upload and full physical reboot behavior require device/account testing. osmdroid 6.1.20 is a legacy renderer; evaluate a maintained renderer before production. UI currently loads a trip's fixes before thinning the displayed line; multi-year datasets will need paginated queries and simplification.

Dependencies: osmdroid (Apache 2.0); osmdroid-mapsforge bridge and Mapsforge (LGPL family—retain notices and applicable source/relinking obligations when distributing). See upstream licenses. No release-store readiness is claimed.

## Validation

`./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` builds the app and isolated backup test APK. On a connected phone, install both debug APKs and run `adb shell am instrument -w in.otrail.test/in.otrail.BackupInstrumentation`. The five backup checks use device-protected test storage, separate from the user's credential-protected trail database. Six JVM stop-detector tests cover dwell threshold, signal gaps, accuracy rejection, moving fixes, duplicate timestamps and drift.
