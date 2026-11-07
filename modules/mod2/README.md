# CAM — downloadable camera module

CAM is a separately downloaded `.oTrail0` module; its Camera2 implementation is not bundled in the host APK. Activate CAM in Settings → Extension modules. A camera icon appears beside Navigate; Settings gains Photo folder & camera settings. Recorded stop details gain Photos. All camera and album screens open inside oTraiL.

Requires host 0.4.1 (24), Android 11+. No installation password is required for CAM. Camera permission is requested only when the camera opens. No photos or coordinates are uploaded.

Capture controls include available lenses, JPEG resolution/quality, flash/torch, white balance, zoom, exposure compensation, autofocus/manual focus, grid, timer and manual ISO/shutter where the lens advertises support. Unsupported controls are hidden. Sensor settings vary across devices/lenses.

Default originals: Downloads/oTraiL/Photos. A persisted Storage Access Framework folder can be selected instead. Each JPEG has UTC capture time, logged GPS coordinates/fix timestamp/accuracy, stop ID and stable stop reference in EXIF and a `.jpg.json` recovery sidecar. Filenames use `oTrail;<stop_uid|no-stop>;<UTC time>;<latitude>;<longitude>;<photo UUID>.jpg`. An unrelated old stop is never assigned to a moving photo. A GPS fix older than two minutes is explicitly retained with its old timestamp and does not assign a stop.

A normalized `photos` table in trails.db indexes UUID, stop foreign key, capture time, location and URI. Existing trail/stop tables are preserved by an additive v3→v4 migration. A photo and its sidecar are saved before its row is inserted. Rebuilding the index is idempotent; restored stop IDs are resolved from logged capture time/location. Folder changes and module/app updates do not delete prior photos or settings. On reinstall, restore trail logs, reselect the original folder and rebuild the photo index. Public original photos must be retained/backed up separately from trail-only backups.

Validated on the connected POCO: 4000×3000 preview/capture, JPEG + sidecar persistence, stop #79 index linkage and in-app viewing with reverse stop details. Individual lens/manual control combinations still require device-specific checks. No map/GPS collector changes are included.
