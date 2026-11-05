package in.otrail;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.location.*;
import android.os.Bundle;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Validation builds target a separate app; all fixtures use isolated device-protected storage. */
public final class BackupInstrumentation extends Instrumentation {
  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  private Context context;
  private int checks;

  private void check(String name, RunnableWithException test) throws Exception {
    test.run();
    checks++;
    android.util.Log.i("oTraiLTests", "PASS: " + name);
  }

  interface RunnableWithException {
    void run() throws Exception;
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    context =
        new ContextWrapper(getTargetContext().createDeviceProtectedStorageContext()) {
          @Override
          public Context getApplicationContext() {
            return this;
          }
        };
    try {
      String old = versionOneFixture();
      check(
          "non-destructive v1 migration",
          () -> {
            try (TrailDb db = new TrailDb(context)) {
              require(db.getReadableDatabase().getVersion() == 2, "schema version");
              require(db.trips().equals(List.of(7L)), "trip ID changed");
              require(db.fixes(7).get(0).id() == 11, "fix ID changed");
              TrailDb.Stop s = db.stops(7).get(0);
              require(
                  s.id() == 13
                      && s.arrival() == 1_000_000
                      && s.observed() == 1_125_000
                      && s.departure() == 1_125_000
                      && s.lat() == 20
                      && s.lon() == 77,
                  "stop GPS/timing changed");
              require("PENDING".equals(s.geocodeStatus()), "old stop not queued");
              require(db.pendingStopIds(10).equals(List.of(13L)), "queue missing stop");
              require(db.logs().contains("v1 history"), "log lost");
              try (Cursor c = db.getReadableDatabase().rawQuery("PRAGMA foreign_key_check", null)) {
                require(!c.moveToFirst(), "broken foreign keys");
              }
            }
          });
      context.deleteDatabase("trails.db");
      check(
          "old backup and defaults",
          () -> {
            try (TrailDb db = new TrailDb(context)) {
              db.restoreBackup(new StringReader(old));
              TrailDb.Stop s = db.stops(-1).get(0);
              require(
                  s.placeName() == null && "PENDING".equals(s.geocodeStatus()),
                  "old backup not pending");
              require(db.fixes(-1).size() == 1, "old fix missing");
            }
          });
      String snapshot;
      try (TrailDb db = new TrailDb(context)) {
        TrailDb.Stop stop = db.stops(-1).get(0);
        db.saveStopAddress(
            stop.id(),
            "Test Town",
            "Test Locality",
            "Test District",
            "Test State",
            "India",
            "123456",
            "Test Road");
        StringWriter out = new StringWriter();
        db.writeBackup(out);
        snapshot = out.toString();
      }
      final String enriched = snapshot;
      check(
          "address snapshot",
          () ->
              require(
                  enriched.contains("\"place_name\":\"Test Town\"")
                      && enriched.contains("Test District"),
                  "address omitted"));
      check(
          "GeoJSON address and coordinate order",
          () -> {
            try (TrailDb db = new TrailDb(context)) {
              StringWriter out = new StringWriter();
              db.export(out);
              JSONArray features = new JSONObject(out.toString()).getJSONArray("features");
              boolean found = false;
              for (int i = 0; i < features.length(); i++) {
                JSONObject f = features.getJSONObject(i), p = f.getJSONObject("properties");
                if ("stop".equals(p.getString("kind"))) {
                  found = true;
                  require("Test Town".equals(p.getString("place_name")), "GeoJSON name");
                  require(p.getString("postal_code").equals("123456"), "GeoJSON PIN");
                  JSONArray xy = f.getJSONObject("geometry").getJSONArray("coordinates");
                  require(xy.getDouble(0) == 77 && xy.getDouble(1) == 20, "coordinate order");
                }
              }
              require(found, "no stop feature");
            }
          });
      context.deleteDatabase("trails.db");
      check(
          "truncated snapshot rolls back",
          () -> {
            try (TrailDb db = new TrailDb(context)) {
              try {
                db.restoreBackup(new StringReader(enriched.substring(0, enriched.length() / 2)));
                throw new AssertionError("truncated backup accepted");
              } catch (Exception expected) {
              }
              require(db.trips().isEmpty() && db.fixes(-1).isEmpty(), "partial restore committed");
            }
          });
      check(
          "enriched restore",
          () -> {
            try (TrailDb db = new TrailDb(context)) {
              db.restoreBackup(new StringReader(enriched));
              TrailDb.Stop s = db.stops(-1).get(0);
              require(
                  "Test Town".equals(s.placeName())
                      && "Test District".equals(s.district())
                      && "RESOLVED".equals(s.geocodeStatus()),
                  "address lost on restore");
            }
          });
      check(
          "merge enriches old duplicate",
          () -> {
            try (TrailDb db = new TrailDb(context)) {
              TrailDb.Stop stop = db.stops(-1).get(0);
              db.getWritableDatabase()
                  .execSQL(
                      "UPDATE stops SET"
                          + " place_name=NULL,locality=NULL,district=NULL,state=NULL,country=NULL,postal_code=NULL,road=NULL,geocode_status='PENDING'");
              db.restoreBackup(new StringReader(enriched));
              TrailDb.Stop merged = db.stops(-1).get(0);
              require(
                  merged.id() == stop.id()
                      && merged.lat() == stop.lat()
                      && merged.observed() == stop.observed(),
                  "merge changed stop identity");
              require("Test Town".equals(merged.placeName()), "merge did not supply address");
              db.restoreBackup(new StringReader(enriched));
              require(
                  db.stops(-1).size() == 1 && db.fixes(-1).size() == 1, "merge duplicated rows");
            }
          });
      check(
          "merge retains current address and enriches missing fields",
          () -> {
            try (TrailDb db = new TrailDb(context)) {
              db.getWritableDatabase()
                  .execSQL("UPDATE stops SET place_name='Current name',road=NULL");
              db.restoreBackup(new StringReader(enriched));
              TrailDb.Stop s = db.stops(-1).get(0);
              require(
                  "Current name".equals(s.placeName()) && "Test Road".equals(s.road()),
                  "merge replaced current label or lost road");
            }
          });
      context.deleteDatabase("trails.db");
      check("new stop ID and geocoder independence", this::independentGeocoding);
      check("lookup failure and 24h cooldown", this::failedGeocoding);
      check("historical queue drains beyond first 25", this::largeBackfill);
      check("historic place age filtering/cache", this::historicCache);
      check(
          "inferred edges only in logs",
          () -> {
            try (TrailDb db = new TrailDb(context)) {
              int count = db.fixes(-1).size();
              List<TrailSegments.Segment> edges =
                  TrailSegments.build(
                      List.of(
                          new TrailSegments.Sample(1, 1, 1000, 20, 77),
                          new TrailSegments.Sample(2, 1, 121000, 20.001, 77)),
                      10);
              db.logInferredGaps(edges);
              db.logInferredGaps(edges);
              require(db.fixes(-1).size() == count, "inference fabricated GPS samples");
              try (Cursor c =
                  db.getReadableDatabase()
                      .rawQuery(
                          "SELECT count(*) FROM sync_logs WHERE provider='Trail gap'", null)) {
                c.moveToFirst();
                require(c.getInt(0) == 1, "gap log duplicated");
              }
            }
          });
      check("sequential marker popup ownership", this::markerPopups);
      check("cached navigation preserves journal", this::navigationIntegrity);
      result.putString(
          "stream",
          "PASS: "
              + checks
              + " migration, backup, geocoding, heritage and gap checks (isolated database)\n");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable e) {
      android.util.Log.e("oTraiLTests", "FAIL", e);
      result.putString("stream", "FAIL: " + e + "\n");
      finish(Activity.RESULT_CANCELED, result);
    } finally {
      context.deleteDatabase("trails.db");
    }
  }

  private String versionOneFixture() throws Exception {
    context.deleteDatabase("trails.db");
    try (SQLiteDatabase d = context.openOrCreateDatabase("trails.db", Context.MODE_PRIVATE, null)) {
      d.execSQL(
          "CREATE TABLE trips(id INTEGER PRIMARY KEY,started_ms INTEGER NOT NULL,ended_ms"
              + " INTEGER)");
      d.execSQL(
          "CREATE TABLE fixes(id INTEGER PRIMARY KEY,trip_id INTEGER NOT NULL REFERENCES"
              + " trips(id),time_ms INTEGER NOT NULL,latitude REAL NOT NULL,longitude REAL NOT"
              + " NULL,accuracy_m REAL NOT NULL,speed_mps REAL NOT NULL,altitude_m REAL,bearing_deg"
              + " REAL,source TEXT NOT NULL)");
      d.execSQL("CREATE INDEX fixes_trip_time ON fixes(trip_id,time_ms)");
      d.execSQL(
          "CREATE TABLE stops(id INTEGER PRIMARY KEY,trip_id INTEGER NOT NULL REFERENCES"
              + " trips(id),arrival_ms INTEGER NOT NULL,departure_ms INTEGER,observed_ms INTEGER"
              + " NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL)");
      d.execSQL(
          "CREATE TABLE sync_logs(id INTEGER PRIMARY KEY,time_ms INTEGER NOT NULL,provider TEXT NOT"
              + " NULL,status TEXT NOT NULL,detail TEXT NOT NULL)");
      d.execSQL("INSERT INTO trips VALUES(7,1000000,1200000)");
      d.execSQL("INSERT INTO fixes VALUES(11,7,1000000,20,77,8,0,NULL,NULL,'fixture')");
      d.execSQL("INSERT INTO stops VALUES(13,7,1000000,1125000,1125000,20,77)");
      d.execSQL("INSERT INTO sync_logs VALUES(17,1000000,'test','SUCCESS','v1 history')");
      d.setVersion(1);
      JSONObject backup = new JSONObject().put("otrail_backup_version", 1);
      for (String table : new String[] {"trips", "fixes", "stops", "sync_logs"}) {
        JSONArray rows = new JSONArray();
        try (Cursor c = d.rawQuery("SELECT * FROM " + table, null)) {
          while (c.moveToNext()) {
            JSONObject row = new JSONObject();
            for (int i = 0; i < c.getColumnCount(); i++) {
              Object value =
                  c.isNull(i)
                      ? JSONObject.NULL
                      : c.getType(i) == Cursor.FIELD_TYPE_INTEGER
                          ? c.getLong(i)
                          : c.getType(i) == Cursor.FIELD_TYPE_FLOAT
                              ? c.getDouble(i)
                              : c.getString(i);
              row.put(c.getColumnName(i), value);
            }
            rows.put(row);
          }
        }
        backup.put(table, rows);
      }
      return backup.toString();
    }
  }

  private long seedStop(TrailDb db) throws Exception {
    long trip = db.begin(), id = -1;
    StopDetector detector = new StopDetector();
    for (int i = 1; i <= 26; i++) {
      Location l = fix(1_000_000 + i * 5000, 20, 77, 0);
      long opened = db.record(trip, l, detector);
      if (i < 26) require(opened == -1, "stop opened before >2 min");
      else id = opened;
    }
    require(id > 0, "stop ID not returned");
    return id;
  }

  private Location fix(long time, double lat, double lon, float speed) {
    Location l = new Location("fixture");
    l.setTime(time);
    l.setLatitude(lat);
    l.setLongitude(lon);
    l.setAccuracy(8);
    l.setSpeed(speed);
    return l;
  }

  private Address address() {
    Address a = new Address(Locale.ENGLISH);
    a.setLocality("Test Town");
    a.setSubAdminArea("Test District");
    a.setAdminArea("Test State");
    a.setCountryName("India");
    return a;
  }

  private void independentGeocoding() throws Exception {
    try (TrailDb db = new TrailDb(context)) {
      long id = seedStop(db);
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      try (StopGeocoder geocoder =
          new StopGeocoder(
              context,
              (lat, lon) -> {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
                return address();
              })) {
        geocoder.request();
        require(entered.await(3, TimeUnit.SECONDS), "lookup not queued");
        long began = System.nanoTime();
        db.record(db.activeTrip(), fix(1_135_000, 20.001, 77, 5), new StopDetector());
        require(
            (System.nanoTime() - began) < TimeUnit.SECONDS.toNanos(2) && db.fixes(-1).size() == 27,
            "lookup blocked recording");
        release.countDown();
        awaitStatus(db, id, "RESOLVED");
        require(
            db.stopById(id).lat() == 20 && db.stopById(id).lon() == 77,
            "lookup changed coordinates");
      }
    }
  }

  private void failedGeocoding() throws Exception {
    context.deleteDatabase("trails.db");
    try (TrailDb db = new TrailDb(context)) {
      long id = seedStop(db);
      try (StopGeocoder geocoder =
          new StopGeocoder(
              context,
              (a, b) -> {
                throw new IOException("offline");
              })) {
        geocoder.request();
        awaitStatus(db, id, "FAILED");
        require(db.pendingStopIds(10).isEmpty(), "failed lookup retried too soon");
        db.record(db.activeTrip(), fix(1_135_000, 20.001, 77, 5), new StopDetector());
        require(db.fixes(-1).size() == 27, "failure stopped recording");
        db.getWritableDatabase()
            .execSQL(
                "UPDATE stops SET geocode_attempt_ms=?",
                new Object[] {System.currentTimeMillis() - TrailDb.GEOCODE_RETRY_MS - 1000});
        require(db.pendingStopIds(10).contains(id), "expired failure not eligible");
      }
    }
  }

  private void largeBackfill() throws Exception {
    context.deleteDatabase("trails.db");
    try (TrailDb db = new TrailDb(context)) {
      long trip = db.begin();
      for (int i = 0; i < 28; i++)
        db.getWritableDatabase()
            .execSQL(
                "INSERT INTO stops(trip_id,arrival_ms,observed_ms,latitude,longitude)"
                    + " VALUES(?,?,?,?,?)",
                new Object[] {trip, 1_000_000 + i * 200000, 1_125_000 + i * 200000, 20, 77});
      try (StopGeocoder geocoder = new StopGeocoder(context, (a, b) -> address(), 0)) {
        geocoder.request();
        long deadline = System.currentTimeMillis() + 5000;
        while (!db.pendingStopIds(1).isEmpty() && System.currentTimeMillis() < deadline)
          Thread.sleep(20);
        require(db.pendingStopIds(100).isEmpty(), "backfill stopped after a batch");
      }
    }
  }

  private void historicCache() throws Exception {
    try (TrailDb db = new TrailDb(context)) {
      int y = java.time.Year.now(java.time.ZoneOffset.UTC).getValue();
      JSONArray elements = new JSONArray();
      for (int age : new int[] {350, 50, 900})
        elements.put(
            new JSONObject()
                .put("type", "node")
                .put("id", age)
                .put("lat", 20)
                .put("lon", 77)
                .put(
                    "tags",
                    new JSONObject()
                        .put("name", "Fixture " + age)
                        .put("start_date", "" + (y - age))));
      int count =
          HistoricPlaces.cacheResult(db, new JSONObject().put("elements", elements), 20, 77);
      require(count == 1, "wrong historic age filter");
      require(HistoricPlaces.cached(db, 20, 77).size() == 1, "cache missing");
      require(HistoricPlaces.cached(db, 25, 77).isEmpty(), "distant place shown nearby");
    }
  }

  private void markerPopups() throws Exception {
    java.util.concurrent.atomic.AtomicReference<Throwable> failure =
        new java.util.concurrent.atomic.AtomicReference<>();
    runOnMainSync(
        () -> {
          org.osmdroid.views.MapView map = null;
          try {
            map = new org.osmdroid.views.MapView(getTargetContext());
            map.setUseDataConnection(false);
            map.layout(0, 0, 720, 1200);
            map.getController().setCenter(new org.osmdroid.util.GeoPoint(20, 77));
            org.osmdroid.views.overlay.Marker a = new org.osmdroid.views.overlay.Marker(map),
                b = new org.osmdroid.views.overlay.Marker(map);
            a.setPosition(new org.osmdroid.util.GeoPoint(20, 77));
            b.setPosition(new org.osmdroid.util.GeoPoint(20.001, 77));
            a.setTitle("First stop");
            b.setTitle("Second stop");
            map.getOverlays().add(a);
            map.getOverlays().add(b);
            MarkerPopups.openOnly(map, a);
            require(a.isInfoWindowShown(), "first popup not open");
            MarkerPopups.openOnly(map, b);
            require(!a.isInfoWindowShown() && b.isInfoWindowShown(), "old popup remained open");
            MarkerPopups.openOnly(map, a);
            require(
                a.isInfoWindowShown() && !b.isInfoWindowShown(), "sequence showed wrong marker");
            require(
                org.osmdroid.views.overlay.infowindow.InfoWindow.getOpenedInfoWindowsOn(map).size()
                    == 1,
                "multiple windows open");
          } catch (Throwable e) {
            failure.set(e);
          } finally {
            if (map != null) map.onDetach();
          }
        });
    if (failure.get() != null) throw new AssertionError("popup regression", failure.get());
  }

  private void navigationIntegrity() throws Exception {
    try (TrailDb db = new TrailDb(context)) {
      StringWriter beforeOut = new StringWriter();
      db.writeBackup(beforeOut);
      JSONObject before = new JSONObject(beforeOut.toString());
      before.remove("created_ms");
      JSONObject maneuver =
          new JSONObject().put("type", "depart").put("location", new JSONArray("[77,20]"));
      JSONObject response =
          new JSONObject()
              .put("code", "Ok")
              .put(
                  "routes",
                  new JSONArray()
                      .put(
                          new JSONObject()
                              .put("distance", 1112)
                              .put("duration", 120)
                              .put(
                                  "geometry",
                                  new JSONObject()
                                      .put("coordinates", new JSONArray("[[77,20],[77,20.01]]")))
                              .put(
                                  "legs",
                                  new JSONArray()
                                      .put(
                                          new JSONObject()
                                              .put(
                                                  "steps",
                                                  new JSONArray()
                                                      .put(
                                                          new JSONObject()
                                                              .put("name", "Fixture road")
                                                              .put("maneuver", maneuver)))))));
      JSONObject cached =
          new JSONObject()
              .put("version", 1)
              .put("response", response)
              .put("name", "Test route")
              .put("latitude", 20.01)
              .put("longitude", 77)
              .put("mode", "ACTIVE");
      try (Writer w =
          new java.io.FileWriter(
              new java.io.File(context.getFilesDir(), "navigation-route.json"))) {
        w.write(cached.toString());
      }
      NavigationManager navigation = NavigationManager.get(context);
      long until = System.currentTimeMillis() + 5000;
      while (navigation.state().route() == null && System.currentTimeMillis() < until)
        Thread.sleep(20);
      require(navigation.state().route() != null, "cached route not restored");
      navigation.start();
      Location l = fix(System.currentTimeMillis(), 20.005, 77, 4);
      navigation.update(l);
      Thread.sleep(100);
      navigation.clear();
      StringWriter afterOut = new StringWriter();
      db.writeBackup(afterOut);
      JSONObject after = new JSONObject(afterOut.toString());
      after.remove("created_ms");
      require(
          before.toString().equals(after.toString()),
          "navigation altered trail tables or backup settings");
    }
  }

  private void awaitStatus(TrailDb db, long id, String expected) throws Exception {
    long end = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < end) {
      if (expected.equals(db.stopById(id).geocodeStatus())) return;
      Thread.sleep(20);
    }
    throw new AssertionError("expected " + expected);
  }
}
