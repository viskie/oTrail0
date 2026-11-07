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

Offline city/town names (e.g. Bilaspur): `CITY_LABEL_FONT`, `CITY_LABEL_STYLE`, `CITY_LABEL_COLOR`, `CITY_LABEL_OUTLINE_COLOR`, `CITY_LABEL_SIZE_SCALE`, `CITY_LABEL_OUTLINE_WIDTH`. Font family values: `default`, `sans_serif`, `serif`, `monospace`. Style values: `normal`, `bold`, `italic`, `bold_italic`. Size scale preserves the underlying zoom visibility rules. Changes automatically use a new tile cache identity. These controls apply to India offline; online raster labels remain provider-controlled.

Dark text: `INK` controls native selectors, switches, inputs, dialog/list text, stop popups and attribution. Main text helpers now honor their explicitly supplied color (`INK`, `MUTED`, etc.). `ACCENT_TEXT` still controls body accents; `HEADING` controls status/headings. Offline road text uses `MAP_ROAD_LABEL_COLOR`, other POI captions use `MAP_OTHER_LABEL_COLOR` (both default to `INK`); their font uses `MAP_TEXT_FONT` and outlines use `MAP_TEXT_OUTLINE_COLOR`. Cities use the independent `CITY_LABEL_*` settings. Provider raster tiles remain unchanged.

Vibrant finish (0.3.4): `VIBRANT_FINISH` enables layered buttons, gradients and colored panel edges. The manually selected `BUTTON` remains the starting color; `BUTTON_GRADIENT_ACCENT` and `BUTTON_GRADIENT_MIX` control its gradient. `BUTTON_EDGE_ACCENT` controls the lower accent edge, and `BUTTON_FONT_STYLE` controls label weight. `PANEL_TINT_MIX` controls panel color strength; `PANEL_ACCENTS` cycles settings card colors. Setting `VIBRANT_FINISH=false` returns flat surfaces/buttons. Base colors, fonts, sizes, map/trail colors and localization choices remain unchanged.

Psychedelic style (0.3.5): `PSYCHEDELIC_GRADIENTS`, `CANVAS_SPECTRUM`, `CANVAS_COLOR_STRENGTH` and `BUTTON_SPECTRUM` control the multi-stop gradients. `BODY_FONT_STYLE`, `BUTTON_FONT_STYLE`, `STATUS_FONT_STYLE`, `HEADING_FONT` and `HEADING_FONT_STYLE` control italics and the playful headings/buttons. City typography now uses four zoom bands, capped by `CITY_LABEL_MIN_SIZE`/`CITY_LABEL_MAX_SIZE`, multiplied by `CITY_LABEL_SIZE_SCALE`. The tile cache identity changes automatically.

Spacing: `BUTTON_INNER_PADDING_DP=4`, `BUTTON_GAP_DP=4`, `CONTROL_ROW_PADDING_DP=2`; sidebar controls use `SIDEBAR_WIDTH_DP=248`, `SIDEBAR_INSET_DP=6`, `PANEL_GAP_DP=4` and `PANEL_INNER_PADDING_DP=6`. Buttons stay 44dp high with centered labels. Button text is now 12sp; the small body/caption/secondary text sizes were increased by one.

Destination text uses `NAV_TITLE_SIZE_SP` (18sp), centered with a two-line limit; turn instructions use `NAV_INSTRUCTION_SIZE_SP` (14sp). Landscape stats stay below the scrolling right-hand controls. `SETTINGS_COLUMNS_LANDSCAPE` controls the three-column settings button rows.

Long stops use `LONG_STOP_MINUTES` (60 minutes), `LONG_STOP_COLOR`, `LONG_STOP_LABEL_SIZE_SP`, `LONG_STOP_LABEL_MIN_ZOOM`, and `LONG_STOP_LABEL_LIMIT`. Big offline city labels use `BIG_CITY_LABEL_COLOR` and `BIG_CITY_LABEL_STYLE`. Long-stop labels become bold italic when their projected screen rectangles overlap a city/town label from the installed offline map. This is an approximate collision check, since the map renderer can displace its labels. Stop-list entries and popups show existing derived inter-stop distance and average speed, including older records.

Map dropdown font: `MAP_SELECTOR_SIZE_SP` (body size +2sp). Total footer: `TOTAL_STATS_TEXT_COLOR` (neon green), `TOTAL_STATS_BACKGROUND` (dark for contrast), `TOTAL_STATS_FONT_STYLE` (bold italic).

Navigation panel: `NAV_PANEL_BACKGROUND` (black), `NAV_TEXT_SPECTRUM` (purple, magenta, violet, cyan, turquoise). Automatic long-stop labels are limited to one and hidden when they collide with another stop label; dots remain tappable.

Navigation heading, instruction and summary now use solid `NAV_TEXT_COLOR` (turquoise), without a text shader. `NAV_TEXT_SPECTRUM` remains available for the panel border/older color references.

Status/Stopped panel uses `STATUS_BACKGROUND` (black), `STATUS_TEXT_COLOR` (cyan), and `STATUS_SPEED_COLOR` (white). The speed span keeps its separate color in every movement state, including unavailable speed.

The floating Locate action shows the application icon inside a hexagon. Edit `LOCATE_SIZE_DP`, `LOCATE_BACKGROUND`, `LOCATE_LOGO_COLOR`, `LOCATE_BORDER_COLOR`, and `LOCATE_RIPPLE_COLOR`; the action and accessibility description remain Locate.

Live status speed uses metres per hour (`0f_m_hr`, m/s ×3600). A known stop always shows zero, even with weak/stale GPS. Uncertain movement remains unknown (`speed_unknown_m_hr`); distances and historical average-speed metrics retain their existing units.

Current live drive uses `LIVE_TRAIL_COLOR` (neon pink); history retains its pass palette and inferred gaps retain green. While stopped, the most recent incoming drive remains highlighted. This changes rendering only. `NAV_TITLE_BOLD` controls the destination's extra bold weight. `LOCATE_VISUAL_SCALE=.60f` reduces the logo and regular hexagon by 40%, while preserving its original touch target; `LOCATE_BEVEL_LIGHT`/`LOCATE_BEVEL_SHADOW` control its 3D edge.
