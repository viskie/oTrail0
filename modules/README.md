# Downloadable oTraiL modules

Modules do not ship inside the app APK. The APK contains only the signed catalog manager, loader, public API and in-app worker service. Module packages use the exact extension `.oTrail0`.

- `api/`: API v1 shared by the host, editor, compiler and publisher.
- `src/main/java/in/otrail/`: catalog verification, password gate, package checks, version activation/rollback and isolated app-process lifecycle.
- `mod1/`: GOD source studio implementation, compiled and downloaded separately.
- `releases/`: signed catalog, versioned packages, source archives and compiler packs. No maps, journal data, credentials or private signing keys.
- `plans/`: future module proposals.

A `.oTrail0` file is a ZIP with `module.json`, `classes.dex` and module-specific assets. GitHub `catalog.json` is a signed envelope containing repository/version lists and separately versioned toolchains. The host pins the publisher public key. Every module package is checked against the signed size/SHA256/identity/API before activation and again before loading. Code is read-only before loading, including Android 14+ handling. A previous installed version remains available for rollback.

Settings → Extension modules lets the user refresh, download with progress, enter the install password, activate/open, deactivate or roll back. GOD's first version requires the owner's install password. It generates its own SSH key on the phone; private keys are not embedded in public packages.

Build host first with `. /src/android-build-env/environment.sh` and `./gradlew assembleDebug testDebugUnitTest lintDebug exportGodRuntime`. `scripts/build-god.py prepare` creates tool packs from pinned public dependencies; `package` compiles the separate module, packages source and signs the catalog. Publisher private material is read from `~/.local/share/otrail/` and must stay outside Git. Preserve old version files and catalog entries; bump versions for changes to published packages.

Read mod1/README.md for first-toolchain limits and setup. The first engine supports app Java rebuilds, and explicitly rejects unsupported resource/build-system changes. No external app or remote compiler is used.
