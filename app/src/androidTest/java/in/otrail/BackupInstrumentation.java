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
              require(db.getReadableDatabase().getVersion() == 3, "schema version");
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
      check("map reparent keeps repository and stop details", this::mapReparent);
      check("cached navigation preserves journal", this::navigationIntegrity);
      check("restart recovers earliest stationary arrival", this::stopContinuity);
      check("v2 metrics migration preserves normalized journal", this::metricsMigration);
      check("road cache survives reopen without network", this::roadCache);
      check("shared journal pool under concurrent writes", this::concurrentJournal);
      check("backup snapshot does not block GPS writer", this::backupDuringRecording);
      check("uncertain GPS retains stop and raw observations", this::uncertainStop);
      check("native text honors configured dark text", this::nativeTextStyle);
      check("fullscreen activity cold launch", this::fullscreenStartup);
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

  private void concurrentJournal() throws Exception {
    try(TrailDb tracker=new TrailDb(context);TrailDb ui=new TrailDb(context)) {
      require(tracker.getWritableDatabase()==ui.getWritableDatabase(),"journal handles opened competing pools");
      TrailDb temporary=new TrailDb(context);temporary.getReadableDatabase();temporary.close();temporary.close();
      require(tracker.getWritableDatabase().isOpen(),"closing UI handle closed active tracker pool");
      long trip=tracker.begin();int before=tracker.fixes(-1).size();
      ExecutorService workers=Executors.newFixedThreadPool(3);
      try {
        Future<?> fixes=workers.submit(()->{
          StopDetector detector=new StopDetector();
          for(int n=0;n<60;n++) {
            Location fix=new Location("gps");fix.setLatitude(20);fix.setLongitude(77);
            fix.setTime(2_000_000_000L+n*5000L);fix.setAccuracy(3);fix.setSpeed(0);
            tracker.record(trip,fix,detector);
          }
        });
        Future<?> logs=workers.submit(()->{try(TrailDb db=new TrailDb(context)){for(int n=0;n<100;n++)db.log("Concurrency fixture","OK","entry "+n);}});
        Future<?> reads=workers.submit(()->{try(TrailDb db=new TrailDb(context)){for(int n=0;n<10;n++){db.fixes(-1);db.stops(-1);}}});
        fixes.get(20,TimeUnit.SECONDS);logs.get(20,TimeUnit.SECONDS);reads.get(20,TimeUnit.SECONDS);
        require(tracker.fixes(-1).size()==before+60,"concurrent work dropped GPS fixes");
        try(Cursor check=tracker.getReadableDatabase().rawQuery("PRAGMA integrity_check",null)) {
          require(check.moveToFirst()&&"ok".equals(check.getString(0)),"concurrent journal integrity");
        }
      } finally {workers.shutdownNow();}
    }
  }

  private void backupDuringRecording() throws Exception {
    if(android.os.Build.VERSION.SDK_INT<35)return;
    try(TrailDb backup=new TrailDb(context);TrailDb tracker=new TrailDb(context)) {
      long trip=tracker.begin();CountDownLatch snapshotOpen=new CountDownLatch(1),release=new CountDownLatch(1);
      ExecutorService workers=Executors.newFixedThreadPool(2);
      try {
        Future<?> snapshot=workers.submit(()->{
          try {
            backup.writeBackup(new Writer(){
              boolean waited;
              @Override public void write(char[] b,int start,int count)throws IOException {
                if(!waited){waited=true;snapshotOpen.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IOException("snapshot fixture timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
              }
              @Override public void flush(){}
              @Override public void close(){}
            });
          } catch(Exception e){throw new RuntimeException(e);}
        });
        require(snapshotOpen.await(10,TimeUnit.SECONDS),"backup snapshot not started");
        Future<?> write=workers.submit(()->{
          Location fix=new Location("gps");fix.setLatitude(20);fix.setLongitude(77);fix.setAccuracy(3);
          fix.setTime(2_001_000_000L);tracker.record(trip,fix,new StopDetector());
        });
        write.get(5,TimeUnit.SECONDS); // Must finish while backup's read snapshot is still open.
        release.countDown();snapshot.get(10,TimeUnit.SECONDS);
      } finally {release.countDown();workers.shutdownNow();}
    }
  }

  private void uncertainStop() throws Exception {
    context.deleteDatabase("trails.db");
    try(TrailDb db=new TrailDb(context)) {
      long stop=seedStop(db),trip=db.activeTrip();int before=db.fixes(-1).size();
      StopDetector detector=new StopDetector();db.restoreDetector(trip,detector);
      long arrival=db.stopById(stop).arrival(),observed=db.stopById(stop).observed();
      Location poor=fix(1140000,20.01,77,30);poor.setAccuracy(80);db.record(trip,poor,detector);
      require(db.stopById(stop).departure()==0,"poor accuracy closed stop");
      require(db.stopById(stop).observed()==observed,"uncertain fix advanced confirmed observation");
      poor=fix(1145000,20.01,77,30);poor.setAccuracy(15);db.record(trip,poor,detector);
      ContentValues duplicate=new ContentValues();duplicate.put("trip_id",trip);duplicate.put("arrival_ms",1145000L);
      duplicate.put("observed_ms",1145000L);duplicate.put("latitude",20);duplicate.put("longitude",77.001);duplicate.put("place_name","Retained user label");
      long duplicateId=db.getWritableDatabase().insertOrThrow("stops",null,duplicate);
      for(int n=0;n<3;n++)db.record(trip,fix(1150000+n*5000,20,77,0),detector);
      require(db.stopById(stop).arrival()==arrival,"stable return reset original arrival");
      require(db.stopById(stop).observed()==1160000,"stable return not observed");
      require(db.stops(-1).size()==2,"duplicate repair removed saved stop IDs");
      require(db.stopById(duplicateId).departure()==1145000L,"duplicate stop remained open");
      require("Retained user label".equals(db.stopById(duplicateId).placeName()),"duplicate repair removed saved label");
      require(db.fixes(-1).size()==before+5,"quality filtering removed raw GPS");
    }
  }
  private void nativeTextStyle() {
    java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
    runOnMainSync(()->{try{
      android.widget.Switch toggle=new android.widget.Switch(getTargetContext());AppStyle.nativeTextTree(toggle);
      require(toggle.getCurrentTextColor()==UiConfig.INK,"switch retained native black");
      android.widget.Spinner selector=new android.widget.Spinner(getTargetContext());
      android.widget.ArrayAdapter<String> choices=AppStyle.choices(getTargetContext(),List.of("India offline"));
      android.widget.TextView label=(android.widget.TextView)choices.getView(0,null,selector);
      require(label.getCurrentTextColor()==UiConfig.INK,"selector ignored configured text color");
      android.widget.EditText input=new android.widget.EditText(getTargetContext());AppStyle.nativeTextTree(input);
      require(input.getCurrentTextColor()==UiConfig.INK,"input ignored configured text color");
    }catch(Throwable error){failure.set(error);}});
    if(failure.get()!=null)throw new AssertionError("Native text configuration",failure.get());
  }

  private void fullscreenStartup() throws Exception {
    Activity activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    waitForIdleSync();
    runOnMainSync(() -> {
      require(activity.getWindow().getDecorView().getWindowInsetsController()!=null,"fullscreen window not attached");
      activity.finish();
    });
    getTargetContext().stopService(new Intent(getTargetContext(),TrackingService.class));
  }

  private void metricsMigration() throws Exception {
    String before;
    try(TrailDb db=new TrailDb(context)) {
      StringWriter w=new StringWriter();db.writeBackup(w);before=w.toString();
      db.getWritableDatabase().execSQL("DROP TABLE stop_drive_metrics");db.getWritableDatabase().setVersion(2);
    }
    try(TrailDb db=new TrailDb(context)) {
      require(db.getWritableDatabase().getVersion()==3,"v2 migration missing");
      DriveMetrics.Result metrics=db.driveMetrics();require(metrics.totalMeters()>=0,"invalid historical km");
      try(Cursor c=db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM stop_drive_metrics",null)){c.moveToFirst();require(c.getInt(0)==db.stops(-1).size(),"stop metrics not backfilled");}
      StringWriter w=new StringWriter();db.writeBackup(w);
      JSONObject a=new JSONObject(before),b=new JSONObject(w.toString());
      for(String table:new String[]{"trips","fixes","stops","sync_logs"})
        require(a.getJSONArray(table).toString().equals(b.getJSONArray(table).toString()),"metrics changed normalized source table "+table);
    }
  }
  private void roadCache() throws Exception {
    context.deleteDatabase("road-cache.db");
    JSONObject road=new JSONObject().put("type","way").put("id",123)
      .put("tags",new JSONObject().put("highway","primary").put("ref","NH-09"))
      .put("geometry",new JSONArray().put(new JSONObject().put("lat",20).put("lon",77)).put(new JSONObject().put("lat",20).put("lon",77.02)));
    try(RoadContext cache=new RoadContext(context)){cache.cache(20,77.01,new JSONObject().put("elements",new JSONArray().put(road)),System.currentTimeMillis());}
    try(RoadContext cache=new RoadContext(context)){require("NH-09".equals(cache.cached(20,77.01,Double.NaN).road()),"cached road lost across reopen");}
    context.deleteDatabase("road-cache.db");
  }

  private void stopContinuity() throws Exception {
    context.deleteDatabase("trails.db");
    long trip, stop;
    try (TrailDb db = new TrailDb(context)) {
      trip = db.begin(); StopDetector detector = new StopDetector(); stop = -1;
      for(int i=1;i<=26;i++) {
        Location l=new Location("test");l.setTime(i*5000);l.setLatitude(20);l.setLongitude(77);l.setAccuracy(8);l.setSpeed(0);
        long id=db.record(trip,l,detector);if(id>0)stop=id;
      }
      require(stop>0,"missing confirmed stop");
      // Simulate a legacy version closing and restarting the same dwell.
      db.getWritableDatabase().execSQL("UPDATE stops SET arrival_ms=60000,departure_ms=observed_ms WHERE id=?",new Object[]{stop});
    }
    try (TrailDb db = new TrailDb(context)) {
      StopDetector detector=new StopDetector();db.restoreDetector(trip,detector);
      require(detector.arrival()==5000,"lost earlier stationary observations");
      Location resumed=new Location("test");resumed.setTime(3600000);resumed.setLatitude(20);resumed.setLongitude(77);resumed.setAccuracy(8);resumed.setSpeed(0);
      db.record(trip,resumed,detector);
      require(db.stopById(stop).departure()!=0,"repaired before stable return");
      for(int n=1;n<=2;n++){resumed.setTime(3600000+n*5000);db.record(trip,resumed,detector);}
      require(db.stopById(stop).arrival()==5000&&db.stopById(stop).departure()==0,"stable return did not restore original stop");
      require(db.stops(-1).size()==1,"restart duplicated stop");
      for(int n=0;n<=4;n++){resumed.setTime(3615000+n*5000);resumed.setLatitude(20.001+n*.0002);resumed.setSpeed(5);db.record(trip,resumed,detector);}
      require(db.stopById(stop).departure()==3610000,"departure not preserved");
      StopDetector moved=new StopDetector();db.restoreDetector(trip,moved);require(!moved.isStopped(),"resurrected departed stop");
      require(db.fixes(-1).size()==34,"recovery changed GPS rows");
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

  private static final class ReparentMap extends org.osmdroid.views.MapView {
    ReparentMap(Context c) { super(c); }
    void windowDetached() { super.onDetachedFromWindow(); }
  }
  private void mapReparent() throws Exception {
    java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
    runOnMainSync(() -> {
      ReparentMap map=null;
      try {
        map=new ReparentMap(getTargetContext());map.setDestroyMode(false);map.setUseDataConnection(false);
        org.osmdroid.views.overlay.Marker stop=new org.osmdroid.views.overlay.Marker(map);
        stop.setTitle("Preserved stop");stop.setSnippet("Arrived 13:47 · duration 1h");map.getOverlays().add(stop);
        map.windowDetached();
        map.getOverlays().add(new org.osmdroid.views.overlay.Polyline(map));
        require("Preserved stop".equals(stop.getTitle()),"stop title lost during reparent");
        require(stop.getSnippet().contains("13:47"),"stop details lost during reparent");
        org.osmdroid.views.overlay.Marker another=new org.osmdroid.views.overlay.Marker(map);
        require(another.getInfoWindow()!=null,"marker repository destroyed");
      }catch(Throwable e){failure.set(e);}finally{if(map!=null)map.onDetach();}
    });
    if(failure.get()!=null)throw new Exception("Map reparent regression",failure.get());
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
