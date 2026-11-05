package in.otrail;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Optional on-demand Overpass lookup with an offline cache, separate from GPS/geocoding. */
public final class HistoricPlaces {
  public record Place(
      String key,
      String name,
      double lat,
      double lon,
      String date,
      int earliest,
      int latest,
      boolean approximate,
      long fetched) {}

  public static void createSchema(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE historic_places(osm_key TEXT PRIMARY KEY,name TEXT NOT NULL,latitude REAL NOT"
            + " NULL,longitude REAL NOT NULL,source_date TEXT NOT NULL,earliest_year INTEGER NOT"
            + " NULL,latest_year INTEGER NOT NULL,approximate INTEGER NOT NULL,fetched_ms INTEGER"
            + " NOT NULL)");
    db.execSQL("CREATE INDEX fixes_time ON fixes(time_ms,id)");
    db.execSQL(
        "CREATE UNIQUE INDEX inferred_gap_logs ON sync_logs(provider,detail) WHERE provider='Trail"
            + " gap'");
  }

  public static List<Place> cached(TrailDb helper, double lat, double lon) {
    List<Place> out = new ArrayList<>();
    int year = java.time.Year.now(java.time.ZoneOffset.UTC).getValue();
    double dx = 25_000 / (111_000 * Math.max(.1, Math.cos(Math.toRadians(lat)))),
        dy = 25_000 / 111_000.0;
    try (Cursor c =
        helper
            .getReadableDatabase()
            .rawQuery(
                "SELECT"
                    + " osm_key,name,latitude,longitude,source_date,earliest_year,latest_year,approximate,fetched_ms"
                    + " FROM historic_places WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ?"
                    + " AND ? AND earliest_year>=? AND latest_year<=?",
                new String[] {
                  "" + (lat - dy),
                  "" + (lat + dy),
                  "" + (lon - dx),
                  "" + (lon + dx),
                  "" + (year - 600),
                  "" + (year - 200)
                })) {
      while (c.moveToNext()) {
        Place p =
            new Place(
                c.getString(0),
                c.getString(1),
                c.getDouble(2),
                c.getDouble(3),
                c.getString(4),
                c.getInt(5),
                c.getInt(6),
                c.getInt(7) != 0,
                c.getLong(8));
        if (StopDetector.distance(lat, lon, p.lat, p.lon) <= 25_000) out.add(p);
      }
    }
    out.sort(Comparator.comparingDouble(p -> StopDetector.distance(lat, lon, p.lat, p.lon)));
    return out;
  }

  public static int fetch(TrailDb helper, double lat, double lon) throws Exception {
    if (lat < 6 || lat > 38 || lon < 68 || lon > 98)
      throw new IOException("Historic lookup is limited to India");
    String around = String.format(Locale.US, "(around:25000,%.6f,%.6f)", lat, lon);
    String query =
        "[out:json][timeout:25];(nwr"
            + around
            + "[historic][name][start_date];nwr"
            + around
            + "[heritage][name][start_date];nwr"
            + around
            + "[tourism][name][start_date];);out tags center 300;";
    HttpURLConnection connection =
        (HttpURLConnection) new URL("https://overpass-api.de/api/interpreter").openConnection();
    connection.setConnectTimeout(15_000);
    connection.setReadTimeout(35_000);
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setRequestProperty(
        "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
    connection.setRequestProperty("User-Agent", "oTraiL/0.2.3 (personal India navigator)");
    try {
      try (OutputStream out = connection.getOutputStream()) {
        out.write(
            ("data=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name()))
                .getBytes(StandardCharsets.UTF_8));
      }
      int status = connection.getResponseCode();
      if (status != 200)
        throw new IOException(
            "Historic-place service returned HTTP " + status + "; cached places kept");
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (InputStream in = connection.getInputStream()) {
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) {
          if (bytes.size() + n > 4_000_000) throw new IOException("Historic result is too large");
          bytes.write(b, 0, n);
        }
      }
      JSONObject result = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
      if (result.has("remark"))
        throw new IOException("Historic service returned an incomplete result; cached places kept");
      return cacheResult(helper, result, lat, lon);
    } finally {
      connection.disconnect();
    }
  }

  static int cacheResult(TrailDb helper, JSONObject response, double lat, double lon)
      throws JSONException {
    JSONArray elements = response.getJSONArray("elements");
    SQLiteDatabase db = helper.getWritableDatabase();
    int accepted = 0, year = java.time.Year.now(java.time.ZoneOffset.UTC).getValue();
    db.beginTransaction();
    try {
      for (int i = 0; i < elements.length(); i++) {
        JSONObject e = elements.getJSONObject(i), tags = e.optJSONObject("tags");
        if (tags == null) continue;
        String date = tags.optString("start_date", ""),
            name = tags.optString("name:en", tags.optString("name", ""));
        HistoricAge age = HistoricAge.parse(date);
        if (name.isBlank() || age == null || !age.inWindow(year)) continue;
        JSONObject center = e.has("lat") ? e : e.optJSONObject("center");
        if (center == null) continue;
        double a = center.optDouble("lat", Double.NaN), b = center.optDouble("lon", Double.NaN);
        if (!Double.isFinite(a)
            || !Double.isFinite(b)
            || a < 6
            || a > 38
            || b < 68
            || b > 98
            || StopDetector.distance(lat, lon, a, b) > 25_000) continue;
        String type = e.optString("type");
        long id = e.optLong("id", -1);
        if (!List.of("node", "way", "relation").contains(type) || id <= 0) continue;
        ContentValues v = new ContentValues();
        v.put("osm_key", type + "/" + id);
        v.put("name", name);
        v.put("latitude", a);
        v.put("longitude", b);
        v.put("source_date", date);
        v.put("earliest_year", age.earliest());
        v.put("latest_year", age.latest());
        v.put("approximate", age.approximate() ? 1 : 0);
        v.put("fetched_ms", System.currentTimeMillis());
        db.insertWithOnConflict("historic_places", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        accepted++;
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    return accepted;
  }
}
