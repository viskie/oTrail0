# oTraiL · India

Personal Android GPS journal for life on the road. Android 11+, targeting Android 15. Map data is never bundled in the APK or committed. All dataset download options run on the phone through Android DownloadManager.

## First run

Open oTraiL, tap Record and grant **precise location** and notifications. A visible foreground-service notification keeps recording when the screen is off and includes Stop. No account or telemetry. Logs remain in app-private SQLite; backups are disabled. Uninstalling removes local data: export GeoJSON first. Reopen and tap Record after a reboot or force-stop. On POCO/HyperOS, App info → Battery → No restrictions may be necessary for long recordings.

## Providers

| Provider | Use | Phone download |
| --- | --- | --- |
| OpenStreetMap | Online roads + normal viewed-tile cache | No bulk downloads on public tile server |
| Mapsforge / OSM | Full India offline vector map, rendered on-device | Manual Wi-Fi download/update, about 1.5 GiB |
| Geofabrik / OSM | Optional India raw PBF source data | Manual Wi-Fi download/update, about 1.6 GiB; **not rendered or used for routing yet** |
| Esri World Imagery | Online satellite + common trail/stop overlays | No offline pack |
| Google Maps / OsmAnd | External navigation handoff | Installed app manages its own data |

Maps & downloads shows estimated size before download, server-reported size/progress during download, queued/paused/failure/ready status, cancellation, retry, manual updates and timestamped logs. No automatic map updates. Downloads survive closing oTraiL. A failed candidate retains the installed map. India `.map` import is also supported. Allow enough free phone storage for the old map plus downloaded candidate plus validation copy (roughly 4.6 GiB during an update). The offline-only switch blocks online map requests, but explicit dataset downloads are separate actions.

Full India map: https://download.mapsforge.org/maps/v5/asia/india.map
Raw India data: https://download.geofabrik.de/asia/india-latest.osm.pbf
Attribution: © OpenStreetMap contributors, ODbL. Satellite tiles © Esri / Maxar / Earthstar Geographics and contributors. Third-party territorial representations are preserved as supplied; viewport is bounded approximately to India (6–38°N, 68–98°E), not a boundary definition.

## Tracking and data

`trips`, `fixes`, `stops`, `sync_logs` are separate normalized tables with foreign keys and a trip/time index. Schema version 1. UTC Unix milliseconds, WGS84 decimal latitude/longitude, meters, m/s, degrees. Optional altitude and bearing remain null when unavailable. Source provider is retained. GeoJSON export streams Point features, `[longitude, latitude]`, with fix and stop properties plus trip IDs. Consumers can group fixes by trip/time into lines. Existing 12,000 km of journeys cannot be reconstructed without previous GPS logs; import of historical journeys is a future feature.

A stop requires >120 seconds within a 35 m anchored radius with speed ≤1.5 m/s and accuracy ≤50 m. Gaps >60 seconds break dwell inference. Departure is the last observed stationary fix; signal loss is never counted as additional stopped time. Fixes older than 30 seconds or worse than 100 m accuracy are discarded; >30-second-old location is marked stale on screen. Live state colors: green recording/ready, blue moving, turquoise stopped, cyan historical. Tappable stop markers show arrival and observed duration on every basemap. No fake/demo coordinates.

## Build and deploy

JDK 21 and Android SDK platform 35 required. Use `./gradlew testDebugUnitTest lintDebug assembleDebug`, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Open `in.otrail/.MainActivity`. Wi-Fi ADB must already be paired and connected. Gradle wrapper 9.6.0; AGP 9.4.0. Debug signing is for local testing. No signing keys or provider credentials in this repository.

## Scaffold limits

Navigation is handed to Google Maps or OsmAnd; there is no embedded routing engine, rerouting, spoken guidance or offline route graph. Commercial providers such as Mappls/Google/Mapbox require separate SDK credentials and terms before integration. Raw PBF is an optional source archive only. No GPX importer, automatic backup, background boot receiver, map delta updates, or validated all-day battery guarantee yet. osmdroid 6.1.20 is a legacy renderer; evaluate a maintained renderer before production. UI currently loads a trip's fixes before thinning the displayed line; multi-year datasets will need paginated queries and simplification.

Dependencies: osmdroid (Apache 2.0); osmdroid-mapsforge bridge and Mapsforge (LGPL family—retain notices and applicable source/relinking obligations when distributing). See upstream licenses. No release-store readiness is claimed.
