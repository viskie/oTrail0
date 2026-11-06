# Edit the UI and build

Edit `app/src/main/java/in/otrail/UiConfig.java`.

- Colors: ARGB `0xAARRGGBB`. `BUTTON` is the background and `BUTTON_TEXT` is the label. Primary text uses `ACCENT_TEXT`, `HEADING`, `SECONDARY`; surfaces use `BACKGROUND` and `SURFACE`.
- Fonts: `BODY_FONT`, `BUTTON_FONT`, `STATUS_FONT`, `MAP_LABEL_FONT` use Android family names, such as `sans-serif`, `sans-serif-condensed`, `sans-serif-medium`, `serif`, or `monospace`.
- Sizes: edit the named `*_SIZE_SP` fields. `TEXT_SCALE` scales general labels; navigation title/instruction/summary and the status have their own sizes. `MAP_LABEL_SIZE_SP` controls app-drawn historic-place labels. `OFFLINE_MAP_TEXT_SCALE` controls offline basemap captions (default 1). The offline tile cache gets a separate identity when this scale changes.
- Trail/map colors: `TRAIL_COLORS` contains all 31 pass colors, `CONTINUITY` controls inferred connections, `NAV_ROUTE_COLOR` controls the route. App marker backgrounds/text have separate `MAP_LABEL_*` tokens. Existing overlay aliases continue to work.
- Copy: `ENGLISH` holds main screen, navigation, settings, road/landmark and dialog labels, including formatted fragments. Keep formatting placeholders such as `%.2f` intact. It does not rename stored database values, source data or routing-engine instructions.
- Languages: `UI_LANGUAGE=""` follows the phone; set `"en"` or `"hi"` to pin it. Add translations to `TRANSLATIONS`, for example `Map.of("hi", Map.of("locate", "मेरी जगह"))`. Missing keys use English. No translations are fabricated or included by default.

Native startup/dialog colors and fonts are generated from the same file during every build; do not edit generated resources. `styles.xml` references those values.

Provider-rendered OSM/Esri raster tiles contain their own road/city typography. Their font, language and color cannot be changed by these UI tokens. Offline Mapsforge uses its zoom-dependent map theme, with density normalized for the fixed tile bridge; app-drawn place labels use the separate UI tokens above.

Build on this workstation:

```bash
cd /src/oTraiL
. /src/android-build-env/environment.sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Install over the existing app, preserving its data:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Do not uninstall or clear storage. Signing keys must match the installed app.
