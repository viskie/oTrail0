# India religious places directory

The base app bundles a separate, read-only public POI database. It is independent of the GPS journal, modules, photos and downloaded maps. Updating it cannot replace trail records. Raw India map extracts and private phone data are not committed or bundled.

Open **Menu → Religious places**. Filter by state, explicitly sourced religion, city/locality/subdistrict, first three digits of an actual six-digit PIN, or place name. Named places are selected by default; uncheck the option to include unnamed source records. Select a result, then Navigate, then confirm. This uses the existing navigation engine and history; it does not interrupt the current destination until confirmed.

Actual counts and named coverage are displayed, rather than filling requested quotas with invented places. Names are copied from source name tags, preferring English when present. Religion, PIN and dates are not inferred from names. Missing city/PIN/date fields remain missing. Source dates and heritage/Wikidata tags can prioritize results but do not establish a certified oldest/prominence ranking. Every result has a source link. A representative point inside a mapped building is a destination estimate, not a guaranteed entrance.

Nearby religious places and police use this directory offline first, supplemented by cached online results. Online refresh has bounded timeouts, a fallback provider and a retry interval; failure does not remove the offline directory. Distances are straight-line distances within 25 km.

## Rebuild

`scripts/build-religious-directory.py` compiles a Geofabrik India OSM PBF, HOT cultural-place administrative hints and geoBoundaries ADM1 boundaries. Host dependencies: osmium and shapely. Keep inputs outside this repository. The generated `religious-directory.json` records checksums, source URLs, coverage and real counts; validate SQLite `integrity_check` before publishing.

## Data licensing

The derived POI database is © OpenStreetMap contributors and distributed under ODbL 1.0: https://opendatacommons.org/licenses/odbl/1-0/ . Attribution: https://www.openstreetmap.org/copyright . The SQLite asset is the reusable derived database, available with the source repository and APK.

Geofabrik: https://download.geofabrik.de/asia/india.html
HOT administrative hints: https://data.humdata.org/dataset/hotosm_ind_cultural_places
geoBoundaries India ADM1 (CC BY 2.5 IN, original boundary provider attribution in API): https://www.geoboundaries.org/api/current/gbOpen/IND/ADM1/
Online OSM fallback: https://livingatlas.esri.in/server/rest/services/OSM/POW/MapServer (and Police); © OpenStreetMap contributors, Esri India.

## Road estimates and compact live display

GPS gaps use cached road-routing geometry, trying installed offline graphs first and the configured online driving endpoint when online mode permits. No road path is drawn until routing succeeds; there is no straight-line fallback. Estimates stay green. Their coordinates never enter GPS records, exports or recorded distance calculations; logs identify them as estimated. The path is plausible rather than evidence of the route actually travelled.

Movement state appears in the live-trail caption. The compact GPS popup retains speed, four-decimal coordinate numbers and up to two lines for locality/current road. UI font families use the Resonations title family; existing sizes, colour choices and emphasis are retained except for explicitly requested purple button text and orange headings.

## Dark mode proposal — not implemented

Add Light / Dark / Follow phone for the map only. Use a separate Mapsforge night render theme with charcoal land, subdued water/vegetation, low-glare roads and readable brown/tan place labels. Preserve navigation, trail, green estimates, icons and bubbles. Include the theme in raster cache keys so switching cannot reuse light tiles. Satellite maps need an explicitly supported night alternative; a black overlay would reduce label readability and is not recommended.
