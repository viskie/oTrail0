# oTraiL extension modules

Settings → Extension modules lists planned modules. This release provides the host/interface scaffold only; no executable extension is included. Once a future bundled module is implemented, its Enable/Disable choice will apply on returning to the map and survive restarts and in-place updates (normal app preferences; not guaranteed after uninstall).

## Layout

- `src/main/java/in/otrail/TrailModule.java`: extension API v1 and immutable read-only snapshots.
- `ModuleCatalog.java`: stable module IDs and bundled/planned status.
- `ModuleHost.java`: view mounting, lifecycle, preferences and error reporting.
- `plans/`: proposals for future UI and functionality.

## Add a module

1. Copy the plan template and define scope, controls, data, lifecycle and verification.
2. Implement `TrailModule` in a new Java file. `createView` mounts an optional panel; `update` receives snapshots. Keep both fast: main thread only. Own any worker executor and cancel it in `close`. Do not retain Activity references after close.
3. Register a stable ID in `ModuleCatalog.ENTRIES` and its factory in `create`. Put user-facing copy/colors/fonts in `UiConfig`.
4. Build with `. /src/android-build-env/environment.sh` then `./gradlew assembleDebug testDebugUnitTest lintDebug`.
5. Deliver the code through a signed app update; users enable it in Settings. This first version loads trusted bundled Java classes, rather than downloaded executable plugins. New module updates use the app's existing updater/signature path.

The optional host supplies read-only data and display hooks; it does not modify the recorder, schema, stored GPS data, routing engines or map downloads. Bundled Java code runs in the app process and is trusted, not sandboxed. Runtime callback failures are recorded and the failing module stops updating for that map session. Process-level failures (for example out-of-memory) are not isolated. Future capabilities such as actions, map overlays, background work and downloaded declarative layouts need explicit API extensions and data-integrity review.
