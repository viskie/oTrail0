package in.otrail;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.location.Location;
import java.io.*;
import java.util.*;
import org.json.*;

public final class TrailDb implements AutoCloseable {
  private final Context context;
  private static final Map<String,Shared> CONNECTIONS=new HashMap<>();
  private final String databasePath;
  private final Shared shared;
  private boolean closed;

  /** One SQLite pool per database file, shared by GPS, UI, geocoder and backup workers. */
  private static final class Shared {
    final SQLiteOpenHelper helper;
    int users;
    Shared(Context context,TrailDb schema) {
      helper=new SQLiteOpenHelper(context,"trails.db",null,3) {
        @Override public void onConfigure(SQLiteDatabase db){schema.onConfigure(db);}
        @Override public void onCreate(SQLiteDatabase db){schema.onCreate(db);}
        @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){schema.onUpgrade(db,oldVersion,newVersion);}
      };
      helper.setWriteAheadLoggingEnabled(true);
    }
  }

  public TrailDb(Context c) {
    context=c.getApplicationContext();
    databasePath=context.getDatabasePath("trails.db").getAbsolutePath();
    synchronized(CONNECTIONS) {
      Shared existing=CONNECTIONS.get(databasePath);
      if(existing==null){existing=new Shared(context,this);CONNECTIONS.put(databasePath,existing);}
      shared=existing;
      shared.users++;
    }
  }
  public synchronized SQLiteDatabase getReadableDatabase() {
    if(closed)throw new IllegalStateException("Trail database handle is closed");
    return shared.helper.getReadableDatabase();
  }
  public synchronized SQLiteDatabase getWritableDatabase() {
    if(closed)throw new IllegalStateException("Trail database handle is closed");
    return shared.helper.getWritableDatabase();
  }
  @Override public synchronized void close() {
    if(closed)return;
    closed=true;
    synchronized(CONNECTIONS) {
      if(--shared.users==0){shared.helper.close();CONNECTIONS.remove(databasePath);}
    }
  }

  public void onConfigure(SQLiteDatabase db) {
    db.setForeignKeyConstraintsEnabled(true);
  }

  public void onCreate(SQLiteDatabase d) {
    d.execSQL(
        "CREATE TABLE trips(id INTEGER PRIMARY KEY, started_ms INTEGER NOT NULL, ended_ms"
            + " INTEGER)");
    d.execSQL(
        "CREATE TABLE fixes(id INTEGER PRIMARY KEY, trip_id INTEGER NOT NULL REFERENCES trips(id),"
            + " time_ms INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL,"
            + " accuracy_m REAL NOT NULL, speed_mps REAL NOT NULL, altitude_m REAL, bearing_deg"
            + " REAL, source TEXT NOT NULL)");
    d.execSQL("CREATE INDEX fixes_trip_time ON fixes(trip_id,time_ms)");
    d.execSQL(
        "CREATE TABLE stops(id INTEGER PRIMARY KEY, trip_id INTEGER NOT NULL REFERENCES trips(id),"
            + " arrival_ms INTEGER NOT NULL, departure_ms INTEGER, observed_ms INTEGER NOT NULL,"
            + " latitude REAL NOT NULL, longitude REAL NOT NULL, place_name TEXT, locality TEXT,"
            + " district TEXT, state TEXT, country TEXT, postal_code TEXT, road TEXT,"
            + " geocode_status TEXT NOT NULL DEFAULT 'PENDING', geocode_attempt_ms INTEGER NOT NULL"
            + " DEFAULT 0)");
    createGeocodeIndex(d);
    d.execSQL(
        "CREATE TABLE sync_logs(id INTEGER PRIMARY KEY, time_ms INTEGER NOT NULL, provider TEXT NOT"
            + " NULL, status TEXT NOT NULL, detail TEXT NOT NULL)");
    HistoricPlaces.createSchema(d);
    createMetrics(d);
  }

  private static final String[] ADDRESS_FIELDS = {
    "place_name", "locality", "district", "state", "country", "postal_code", "road"
  };

  private static void createGeocodeIndex(SQLiteDatabase d) {
    d.execSQL("CREATE INDEX stops_geocode_queue ON stops(geocode_status,geocode_attempt_ms)");
  }

  public void onUpgrade(SQLiteDatabase d, int oldVersion, int newVersion) {
    if (oldVersion < 2) {
      long trips = tableCount(d, "trips"),
          fixes = tableCount(d, "fixes"),
          stops = tableCount(d, "stops");
      for (String field : ADDRESS_FIELDS)
        d.execSQL("ALTER TABLE stops ADD COLUMN " + field + " TEXT");
      d.execSQL("ALTER TABLE stops ADD COLUMN geocode_status TEXT NOT NULL DEFAULT 'PENDING'");
      d.execSQL("ALTER TABLE stops ADD COLUMN geocode_attempt_ms INTEGER NOT NULL DEFAULT 0");
      createGeocodeIndex(d);
      HistoricPlaces.createSchema(d);
      if (tableCount(d, "trips") != trips
          || tableCount(d, "fixes") != fixes
          || tableCount(d, "stops") != stops)
        throw new IllegalStateException("Migration changed journal counts");
      ContentValues audit = new ContentValues();
      audit.put("time_ms", System.currentTimeMillis());
      audit.put("provider", "Database migration");
      audit.put("status", "SUCCESS");
      audit.put(
          "detail",
          "v1 → v2; preserved "
              + trips
              + " trips, "
              + fixes
              + " fixes, "
              + stops
              + " stops; added address fields and display caches");
      d.insertOrThrow("sync_logs", null, audit);
    }
    if (oldVersion < 3) createMetrics(d);
  }

  private static void createMetrics(SQLiteDatabase d) {
    d.execSQL("CREATE TABLE IF NOT EXISTS stop_drive_metrics(stop_id INTEGER PRIMARY KEY REFERENCES stops(id) ON DELETE CASCADE,start_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL,distance_m REAL NOT NULL,observed_ms INTEGER NOT NULL,missing_ms INTEGER NOT NULL,average_kmh REAL NOT NULL)");
  }
  private long metricsSignature=Long.MIN_VALUE;
  private DriveMetrics.Result metricsCache;
  public synchronized DriveMetrics.Result driveMetrics() {
    Fix latest=latestFix();List<Stop> stops=stops(-1);
    long signature=latest==null?0:latest.id();
    for(Stop s:stops)signature=31*signature+Objects.hash(s.id(),s.arrival(),s.departure(),s.observed());
    if(metricsCache!=null&&signature==metricsSignature)return metricsCache;
    List<Fix> fixes=fixes(-1);
    List<DriveMetrics.Point> points=new ArrayList<>();for(Fix f:fixes)points.add(new DriveMetrics.Point(f.time(),f.lat(),f.lon(),f.accuracy(),f.speed()));
    List<DriveMetrics.Stop> dwell=new ArrayList<>();for(Stop s:stops)dwell.add(new DriveMetrics.Stop(s.id(),s.arrival(),s.departure(),s.observed()));
    DriveMetrics.Result result=DriveMetrics.calculate(points,dwell);
    SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
    try {
      for(DriveMetrics.Leg leg:result.stops().values()) {
        ContentValues v=new ContentValues();v.put("stop_id",leg.stopId());v.put("start_ms",leg.start());v.put("end_ms",leg.end());v.put("distance_m",leg.meters());v.put("observed_ms",leg.observedMs());v.put("missing_ms",leg.missingMs());v.put("average_kmh",leg.averageKmh());
        d.insertWithOnConflict("stop_drive_metrics",null,v,SQLiteDatabase.CONFLICT_REPLACE);
      }
      d.setTransactionSuccessful();
    }finally{d.endTransaction();}
    metricsSignature=signature;metricsCache=result;return result;
  }

  private static long tableCount(SQLiteDatabase d, String table) {
    try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
      c.moveToFirst();
      return c.getLong(0);
    }
  }

  public synchronized long activeTrip() {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id FROM trips WHERE ended_ms IS NULL ORDER BY id DESC LIMIT 1", null)) {
      return c.moveToFirst() ? c.getLong(0) : -1;
    }
  }

  public synchronized long begin() {
    long id = activeTrip();
    if (id != -1) return id;
    ContentValues v = new ContentValues();
    v.put("started_ms", System.currentTimeMillis());
    return getWritableDatabase().insertOrThrow("trips", null, v);
  }

  public synchronized void finish(long trip) {
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      d.execSQL(
          "UPDATE stops SET departure_ms=observed_ms WHERE trip_id=? AND departure_ms IS NULL",
          new Object[] {trip});
      d.execSQL(
          "UPDATE trips SET ended_ms=? WHERE id=?",
          new Object[] {System.currentTimeMillis(), trip});
      d.setTransactionSuccessful();
    } finally {
      d.endTransaction();
    }
  }

  public synchronized void restoreDetector(long trip, StopDetector detector) {
    Fix latest=latestFix();detector.reset();
    if(latest==null)return;
    long since=latest.time()-6*60*60*1000L;
    // Re-evaluate saved observations after a restart, including previously rejected GPS excursions.
    try(Cursor c=getReadableDatabase().rawQuery(
        "SELECT time_ms,latitude,longitude,accuracy_m,speed_mps FROM fixes WHERE time_ms>=? ORDER BY time_ms,id",new String[]{Long.toString(since)})) {
      if(c.moveToFirst()) {
        long first=c.getLong(0);
        for(Stop stop:stops(-1))if(stop.arrival()<=first&&stop.observed()>=first
            &&StopDetector.distance(stop.lat(),stop.lon(),c.getDouble(1),c.getDouble(2))<=RADIUS_FOR_RECOVERY) {
          detector.restore(stop.arrival(),first-1,stop.lat(),stop.lon(),true);break;
        }
        do {detector.accept(c.getLong(0),c.getDouble(1),c.getDouble(2),c.getFloat(3),c.getFloat(4));}while(c.moveToNext());
      }
    }
    if(detector.isStopped()) {
      long nearest=Long.MAX_VALUE;
      for(Stop stop:stops(-1)) {
        long delta=Math.abs(stop.arrival()-detector.arrival());
        if(stop.arrival()<=detector.arrival()+120_000&&stop.observed()>=detector.arrival()
            &&StopDetector.distance(stop.lat(),stop.lon(),detector.latitude(),detector.longitude())<=RADIUS_FOR_RECOVERY&&delta<nearest) {
          detector.persistedStopId=stop.id();nearest=delta;
        }
      }
    }
  }
  private static final double RADIUS_FOR_RECOVERY=60;

  public synchronized long record(long trip, Location l, StopDetector detector) {
    long newStopId = -1;
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      ContentValues v = new ContentValues();
      v.put("trip_id", trip);
      v.put("time_ms", l.getTime());
      v.put("latitude", l.getLatitude());
      v.put("longitude", l.getLongitude());
      v.put("accuracy_m", l.getAccuracy());
      v.put("speed_mps", l.hasSpeed() ? l.getSpeed() : 0);
      if (l.hasAltitude()) v.put("altitude_m", l.getAltitude());
      if (l.hasBearing()) v.put("bearing_deg", l.getBearing());
      v.put("source", l.getProvider());
      d.insertOrThrow("fixes", null, v);
      StopDetector.Event e =
          detector.accept(
              l.getTime(),
              l.getLatitude(),
              l.getLongitude(),
              l.getAccuracy(),
              l.hasSpeed() ? l.getSpeed() : 0);
      if (e != null && e.closed())
        d.execSQL(
            "UPDATE stops SET departure_ms=? WHERE id=? OR (trip_id=? AND departure_ms IS NULL)",
            new Object[] {e.departure(), detector.persistedStopId, trip});
      if ((e != null && e.opened()) || (detector.isStopped() && detector.persistedStopId<0 && detector.stableSignal())) {
        ContentValues s = new ContentValues();
        s.put("trip_id", trip);
        s.put("arrival_ms", detector.arrival());
        s.put("observed_ms", l.getTime());
        s.put("latitude", detector.latitude());
        s.put("longitude", detector.longitude());
        newStopId = d.insertOrThrow("stops", null, s);
        detector.persistedStopId = newStopId;
      }
      if(detector.isStopped()&&detector.persistedStopId>0&&detector.stableSignal()) {
        try(Cursor old=d.rawQuery("SELECT arrival_ms,departure_ms FROM stops WHERE id=?",new String[]{Long.toString(detector.persistedStopId)})) {
          if(old.moveToFirst()&&(!old.isNull(1)||old.getLong(0)>detector.arrival()))
            log("Stop continuity","RECOVERED","stop="+detector.persistedStopId+"; previous_arrival_ms="+old.getLong(0)+"; previous_departure_ms="+(old.isNull(1)?"null":old.getLong(1))+"; stable return confirms original arrival_ms="+detector.arrival()+"; raw GPS retained");
        }
        d.execSQL("UPDATE stops SET arrival_ms=MIN(arrival_ms,?),observed_ms=?,departure_ms=NULL WHERE id=?",
            new Object[]{detector.arrival(),detector.observationTime(),detector.persistedStopId});
        // Older collectors may have opened a second row during the same uncertain dwell.
        // Retain its ID/content; only the recovered canonical stop remains open.
        try(Cursor other=d.rawQuery("SELECT id,latitude,longitude,observed_ms FROM stops WHERE departure_ms IS NULL AND id!=? AND arrival_ms>=?",
            new String[]{Long.toString(detector.persistedStopId),Long.toString(detector.arrival())})) {
          while(other.moveToNext())if(StopDetector.distance(detector.latitude(),detector.longitude(),other.getDouble(1),other.getDouble(2))<=RADIUS_FOR_RECOVERY) {
            d.execSQL("UPDATE stops SET departure_ms=observed_ms WHERE id=?",new Object[]{other.getLong(0)});
            log("Stop continuity","SUPERSEDED","stop="+other.getLong(0)+"; retained record and observed_ms="+other.getLong(3)+"; continuing canonical stop="+detector.persistedStopId);
          }
        }
      }
      d.setTransactionSuccessful();
    } finally {
      d.endTransaction();
    }
    context.getSharedPreferences("tracking_signal",0).edit()
        .putBoolean("uncertain",detector.isUncertain()).putLong("fix_ms",l.getTime())
        .putLong("last_reliable_ms",detector.observationTime())
        .putLong("stop_arrival_ms",detector.isStopped()?detector.arrival():0)
        .putLong("stop_id",detector.persistedStopId).apply();
    return newStopId;
  }

  public record Fix(
      long id, long trip, long time, double lat, double lon, float accuracy, float speed) {}

  public record Stop(
      long id,
      long trip,
      long arrival,
      long departure,
      long observed,
      double lat,
      double lon,
      String placeName,
      String locality,
      String district,
      String state,
      String country,
      String postalCode,
      String road,
      String geocodeStatus,
      long geocodeAttempt) {}

  private static final String STOP_COLUMNS =
      "id,trip_id,arrival_ms,departure_ms,observed_ms,latitude,longitude,place_name,locality,district,state,country,postal_code,road,geocode_status,geocode_attempt_ms";

  private static Stop readStop(Cursor c) {
    return new Stop(
        c.getLong(0),
        c.getLong(1),
        c.getLong(2),
        c.isNull(3) ? 0 : c.getLong(3),
        c.getLong(4),
        c.getDouble(5),
        c.getDouble(6),
        c.getString(7),
        c.getString(8),
        c.getString(9),
        c.getString(10),
        c.getString(11),
        c.getString(12),
        c.getString(13),
        c.getString(14),
        c.getLong(15));
  }

  public Fix latestFix() {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id,trip_id,time_ms,latitude,longitude,accuracy_m,speed_mps FROM fixes ORDER"
                    + " BY time_ms DESC,id DESC LIMIT 1",
                null)) {
      return c.moveToFirst()
          ? new Fix(
              c.getLong(0),
              c.getLong(1),
              c.getLong(2),
              c.getDouble(3),
              c.getDouble(4),
              c.getFloat(5),
              c.getFloat(6))
          : null;
    }
  }

  public record TripSummary(long id, long first, long count) {}
  public List<TripSummary> tripSummaries() {
    List<TripSummary> out = new ArrayList<>();
    try (Cursor c = getReadableDatabase().rawQuery(
        "SELECT t.id,COALESCE(MIN(f.time_ms),t.started_ms),COUNT(f.id) FROM trips t LEFT JOIN fixes f ON f.trip_id=t.id GROUP BY t.id ORDER BY t.id DESC", null)) {
      while(c.moveToNext()) out.add(new TripSummary(c.getLong(0),c.getLong(1),c.getLong(2)));
    }
    return out;
  }

  public List<Fix> fixes(long trip) {
    List<Fix> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id,trip_id,time_ms,latitude,longitude,accuracy_m,speed_mps FROM fixes"
                    + (trip < 0 ? "" : " WHERE trip_id=?")
                    + " ORDER BY time_ms,id",
                trip < 0 ? null : new String[] {"" + trip})) {
      while (c.moveToNext())
        out.add(
            new Fix(
                c.getLong(0),
                c.getLong(1),
                c.getLong(2),
                c.getDouble(3),
                c.getDouble(4),
                c.getFloat(5),
                c.getFloat(6)));
    }
    return out;
  }

  public List<Stop> stops(long trip) {
    List<Stop> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT "
                    + STOP_COLUMNS
                    + " FROM stops"
                    + (trip < 0 ? "" : " WHERE trip_id=?")
                    + " ORDER BY arrival_ms,id",
                trip < 0 ? null : new String[] {"" + trip})) {
      while (c.moveToNext()) out.add(readStop(c));
    }
    return out;
  }

  public Stop stopById(long id) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT " + STOP_COLUMNS + " FROM stops WHERE id=?", new String[] {"" + id})) {
      return c.moveToFirst() ? readStop(c) : null;
    }
  }

  public static final long GEOCODE_RETRY_MS = 24L * 60 * 60 * 1000;

  public List<Long> pendingStopIds(int limit) {
    List<Long> ids = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id FROM stops WHERE geocode_status='PENDING' OR (geocode_status='FAILED'"
                    + " AND geocode_attempt_ms<?) ORDER BY arrival_ms,id LIMIT ?",
                new String[] {"" + (System.currentTimeMillis() - GEOCODE_RETRY_MS), "" + limit})) {
      while (c.moveToNext()) ids.add(c.getLong(0));
    }
    return ids;
  }

  public synchronized void saveStopAddress(
      long id,
      String placeName,
      String locality,
      String district,
      String state,
      String country,
      String postalCode,
      String road) {
    ContentValues v = new ContentValues();
    String[] values = {placeName, locality, district, state, country, postalCode, road};
    for (int i = 0; i < ADDRESS_FIELDS.length; i++) v.put(ADDRESS_FIELDS[i], values[i]);
    v.put("geocode_status", "RESOLVED");
    v.put("geocode_attempt_ms", System.currentTimeMillis());
    getWritableDatabase()
        .update("stops", v, "id=? AND geocode_status!='RESOLVED'", new String[] {"" + id});
  }

  public synchronized void markGeocodeFailed(long id) {
    ContentValues v = new ContentValues();
    v.put("geocode_status", "FAILED");
    v.put("geocode_attempt_ms", System.currentTimeMillis());
    getWritableDatabase()
        .update("stops", v, "id=? AND geocode_status!='RESOLVED'", new String[] {"" + id});
  }

  public List<Long> trips() {
    List<Long> out = new ArrayList<>();
    try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM trips ORDER BY id DESC", null)) {
      while (c.moveToNext()) out.add(c.getLong(0));
    }
    return out;
  }

  public synchronized void log(String provider, String status, String detail) {
    ContentValues v = new ContentValues();
    v.put("time_ms", System.currentTimeMillis());
    v.put("provider", provider);
    v.put("status", status);
    v.put("detail", detail);
    getWritableDatabase().insertOrThrow("sync_logs", null, v);
  }

  public void logInferredGaps(List<TrailSegments.Segment> segments) {
    for (TrailSegments.Segment s : segments)
      if (s.inferred()) {
        TrailSegments.Sample a = s.points().get(0), b = s.points().get(1);
        ContentValues v = new ContentValues();
        v.put("time_ms", System.currentTimeMillis());
        v.put("provider", "Trail gap");
        v.put("status", "ESTIMATED");
        v.put(
            "detail",
            "fixes="
                + a.id()
                + ".."
                + b.id()
                + "; duration_ms="
                + (b.time() - a.time())
                + "; method=straight_line; GPS samples unchanged");
        getWritableDatabase()
            .insertWithOnConflict("sync_logs", null, v, SQLiteDatabase.CONFLICT_IGNORE);
      }
  }

  public String logs() {
    StringBuilder b = new StringBuilder();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT time_ms,provider,status,detail FROM sync_logs ORDER BY id DESC LIMIT 40",
                null)) {
      while (c.moveToNext())
        b.append(new Date(c.getLong(0)))
            .append("\n")
            .append(c.getString(1))
            .append(" · ")
            .append(c.getString(2))
            .append("\n")
            .append(c.getString(3))
            .append("\n\n");
    }
    return b.length() == 0 ? "No manual updates yet." : b.toString();
  }

  public void export(Writer w) throws Exception {
    // Stream each feature rather than assembling a multi-year trail in memory.
    w.write("{\"type\":\"FeatureCollection\",\"schema_version\":1,\"features\":[");
    boolean first = true;
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT * FROM fixes ORDER BY trip_id,time_ms,id", null)) {
      while (c.moveToNext()) {
        JSONObject props = new JSONObject();
        props.put("kind", "fix");
        String[] names = {
          "id",
          "trip_id",
          "time_ms",
          "accuracy_m",
          "speed_mps",
          "altitude_m",
          "bearing_deg",
          "source"
        };
        for (String name : names) {
          int i = c.getColumnIndexOrThrow(name);
          props.put(
              name,
              c.isNull(i)
                  ? JSONObject.NULL
                  : c.getType(i) == Cursor.FIELD_TYPE_STRING
                      ? c.getString(i)
                      : c.getType(i) == Cursor.FIELD_TYPE_INTEGER ? c.getLong(i) : c.getDouble(i));
        }
        JSONObject f =
            feature(
                c.getDouble(c.getColumnIndexOrThrow("longitude")),
                c.getDouble(c.getColumnIndexOrThrow("latitude")),
                props);
        if (!first) w.write(",");
        w.write(f.toString());
        first = false;
      }
    }
    DriveMetrics.Result metrics=driveMetrics();
    for (long trip : trips())
      for (Stop s : stops(trip)) {
        JSONObject p =
            new JSONObject()
                .put("kind", "stop")
                .put("id", s.id())
                .put("trip_id", trip)
                .put("arrival_ms", s.arrival())
                .put("departure_ms", s.departure() == 0 ? JSONObject.NULL : s.departure())
                .put("observed_ms", s.observed())
                .put("duration_ms", s.observed() - s.arrival());
        DriveMetrics.Leg leg=metrics.stops().get(s.id());
        if(leg!=null)p.put("drive_since_previous_stop_m",leg.meters()).put("drive_average_kmh",leg.averageKmh()).put("drive_start_ms",leg.start()).put("drive_missing_ms",leg.missingMs());
        p.put("place_name", jsonValue(s.placeName()))
            .put("locality", jsonValue(s.locality()))
            .put("district", jsonValue(s.district()))
            .put("state", jsonValue(s.state()))
            .put("country", jsonValue(s.country()))
            .put("postal_code", jsonValue(s.postalCode()))
            .put("road", jsonValue(s.road()))
            .put("geocode_status", s.geocodeStatus());
        if (!first) w.write(",");
        w.write(feature(s.lon(), s.lat(), p).toString());
        first = false;
      }
    w.write("]}");
    w.flush();
  }

  private static final String[] BACKUP_TABLES = {"trips", "fixes", "stops", "sync_logs"};

  /** JSON snapshot preserves normalized tables, IDs, units and nullable values. */
  public void writeBackup(Writer output) throws Exception {
    android.util.JsonWriter w = new android.util.JsonWriter(output);
    SQLiteDatabase d = getReadableDatabase();
    // Android 15+ supports a real read snapshot: backups do not reserve the writer.
    if(android.os.Build.VERSION.SDK_INT>=35)d.beginTransactionReadOnly();
    else d.beginTransactionNonExclusive();
    try {
      w.beginObject();
      w.name("otrail_backup_version").value(1);
      w.name("created_ms").value(System.currentTimeMillis());
      for (String table : BACKUP_TABLES) {
        w.name(table).beginArray();
        try (Cursor c = d.rawQuery("SELECT * FROM " + table + " ORDER BY id", null)) {
          while (c.moveToNext()) {
            w.beginObject();
            for (int i = 0; i < c.getColumnCount(); i++) {
              w.name(c.getColumnName(i));
              switch (c.getType(i)) {
                case Cursor.FIELD_TYPE_NULL:
                  w.nullValue();
                  break;
                case Cursor.FIELD_TYPE_INTEGER:
                  w.value(c.getLong(i));
                  break;
                case Cursor.FIELD_TYPE_FLOAT:
                  w.value(c.getDouble(i));
                  break;
                default:
                  w.value(c.getString(i));
              }
            }
            w.endObject();
          }
        }
        w.endArray();
      }
      w.name("settings").beginObject();
      android.content.SharedPreferences ui = context.getSharedPreferences("MainActivity", 0);
      w.name("provider").value(ui.getInt("provider", 0));
      w.name("offline").value(ui.getBoolean("offline", false));
      android.content.SharedPreferences pack = context.getSharedPreferences("packs", 0);
      for (String key :
          new String[] {
            "mapsforge_status",
            "geofabrik_status",
            "mapsforge_retained",
            "geofabrik_retained",
            "raw_path"
          }) if (pack.contains(key)) w.name(key).value(pack.getString(key, ""));
      w.endObject();
      w.endObject();
      w.flush();
      d.setTransactionSuccessful();
    } finally {
      d.endTransaction();
    }
  }

  /** Merge history with new always-on fixes. Any parsing failure rolls back the whole restore. */
  public void restoreBackup(Reader input) throws Exception {
    android.util.JsonReader r = new android.util.JsonReader(input);
    android.content.ContentValues settings = new android.content.ContentValues();
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      r.beginObject();
      if (!r.hasNext() || !r.nextName().equals("otrail_backup_version") || r.nextInt() != 1)
        throw new IOException("Not a supported oTraiL backup");
      java.util.Set<String> restored = new java.util.HashSet<>();
      java.util.Map<Long, Long> tripIds = new java.util.HashMap<>();
      java.util.Set<Long> historicalTrips = new java.util.HashSet<>();
      while (r.hasNext()) {
        String table = r.nextName();
        if (table.equals("settings")) {
          r.beginObject();
          while (r.hasNext()) {
            String key = r.nextName();
            if (key.equals("provider")) {
              int value = r.nextInt();
              if (value < 0 || value > 2) throw new IOException("Invalid provider");
              settings.put(key, value);
            } else if (key.equals("offline")) settings.put(key, r.nextBoolean());
            else if (java.util.Arrays.asList(
                    "mapsforge_status",
                    "geofabrik_status",
                    "mapsforge_retained",
                    "geofabrik_retained",
                    "raw_path")
                .contains(key)) settings.put(key, r.nextString());
            else r.skipValue();
          }
          r.endObject();
          continue;
        }
        if (!java.util.Arrays.asList(BACKUP_TABLES).contains(table)) {
          r.skipValue();
          continue;
        }
        if (!restored.add(table)) throw new IOException("Duplicate table in backup");
        java.util.Set<String> allowed = new java.util.HashSet<>();
        try (Cursor columns = d.rawQuery("SELECT * FROM " + table + " LIMIT 0", null)) {
          java.util.Collections.addAll(allowed, columns.getColumnNames());
        }
        r.beginArray();
        while (r.hasNext()) {
          ContentValues v = new ContentValues();
          r.beginObject();
          while (r.hasNext()) {
            String field = r.nextName();
            if (!allowed.contains(field)) throw new IOException("Unknown backup field: " + field);
            if (r.peek() == android.util.JsonToken.NULL) {
              r.nextNull();
              v.putNull(field);
            } else {
              v.put(field, r.nextString());
            }
          }
          r.endObject();
          if (table.equals("trips")) {
            long original = v.getAsLong("id");
            long mapped = -1;
            try (Cursor existing =
                d.rawQuery(
                    "SELECT id FROM trips WHERE started_ms=? LIMIT 1",
                    new String[] {v.getAsString("started_ms")})) {
              if (existing.moveToFirst()) mapped = existing.getLong(0);
            }
            if (mapped == -1) {
              v.remove("id");
              if (v.get("ended_ms") == null) {
                v.put("ended_ms", v.getAsLong("started_ms"));
              }
              mapped = d.insertOrThrow(table, null, v);
              historicalTrips.add(mapped);
            }
            tripIds.put(original, mapped);
          } else if (table.equals("fixes") || table.equals("stops")) {
            Long mapped = tripIds.get(v.getAsLong("trip_id"));
            if (mapped == null) throw new IOException("Backup references an unknown trip");
            v.put("trip_id", mapped);
            v.remove("id");
            String time = table.equals("fixes") ? "time_ms" : "arrival_ms";
            boolean duplicate;
            try (Cursor existing =
                d.rawQuery(
                    "SELECT id FROM " + table + " WHERE trip_id=? AND " + time + "=? LIMIT 1",
                    new String[] {"" + mapped, v.getAsString(time)})) {
              duplicate = existing.moveToFirst();
            }
            if (!duplicate) {
              if (table.equals("stops") && v.get("departure_ms") == null
                  && historicalTrips.contains(mapped))
                v.put("departure_ms", v.getAsLong("observed_ms"));
              d.insertOrThrow(table, null, v);
            } else if (table.equals("stops")) mergeStopAddress(d, v);
          } else {
            v.remove("id");
            boolean duplicate;
            try (Cursor existing =
                d.rawQuery(
                    "SELECT id FROM sync_logs WHERE time_ms=? AND provider=? AND status=? AND"
                        + " detail=? LIMIT 1",
                    new String[] {
                      v.getAsString("time_ms"),
                      v.getAsString("provider"),
                      v.getAsString("status"),
                      v.getAsString("detail")
                    })) {
              duplicate = existing.moveToFirst();
            }
            if (!duplicate) d.insertOrThrow(table, null, v);
          }
        }
        r.endArray();
      }
      r.endObject();
      if (restored.size() != BACKUP_TABLES.length) throw new IOException("Incomplete trail backup");
      if (r.peek() != android.util.JsonToken.END_DOCUMENT)
        throw new IOException("Unexpected data after backup");
      for (long id : historicalTrips)
        d.execSQL(
            "UPDATE trips SET ended_ms=MAX(COALESCE(ended_ms,started_ms),COALESCE((SELECT"
                + " MAX(time_ms) FROM fixes WHERE trip_id=?),started_ms)) WHERE id=?",
            new Object[] {id, id});
      d.setTransactionSuccessful();
    } finally {
      d.endTransaction();
    }
    android.content.SharedPreferences.Editor ui =
        context.getSharedPreferences("MainActivity", 0).edit();
    if (settings.containsKey("provider")) ui.putInt("provider", settings.getAsInteger("provider"));
    if (settings.containsKey("offline")) ui.putBoolean("offline", settings.getAsBoolean("offline"));
    ui.apply();
    android.content.SharedPreferences.Editor pack = context.getSharedPreferences("packs", 0).edit();
    for (String key : settings.keySet())
      if (!key.equals("provider") && !key.equals("offline")) {
        String value = settings.getAsString(key);
        if (key.endsWith("_status") && value.startsWith("Ready"))
          value = "Previously downloaded · import retained file";
        pack.putString(key, value);
      }
    pack.apply();
  }

  // Merge only missing address fields; restore never replaces an existing label or stop timing.
  private static void mergeStopAddress(SQLiteDatabase d, ContentValues incoming) {
    try (Cursor c =
        d.rawQuery(
            "SELECT id,place_name,locality,district,state,country,postal_code,road,geocode_status"
                + " FROM stops WHERE trip_id=? AND arrival_ms=? LIMIT 1",
            new String[] {incoming.getAsString("trip_id"), incoming.getAsString("arrival_ms")})) {
      if (!c.moveToFirst()) return;
      ContentValues update = new ContentValues();
      boolean hasAddress = false;
      for (int i = 0; i < ADDRESS_FIELDS.length; i++) {
        String existing = c.getString(i + 1), value = incoming.getAsString(ADDRESS_FIELDS[i]);
        if ((existing == null || existing.isBlank()) && value != null && !value.isBlank())
          update.put(ADDRESS_FIELDS[i], value);
        if (existing != null && !existing.isBlank() || value != null && !value.isBlank())
          hasAddress = true;
      }
      if (hasAddress
          && "RESOLVED".equals(incoming.getAsString("geocode_status"))
          && !"RESOLVED".equals(c.getString(8))) {
        update.put("geocode_status", "RESOLVED");
        update.put("geocode_attempt_ms", System.currentTimeMillis());
      }
      if (update.size() > 0) d.update("stops", update, "id=?", new String[] {"" + c.getLong(0)});
    }
  }

  private static Object jsonValue(String s) {
    return s == null ? JSONObject.NULL : s;
  }

  private JSONObject feature(double lon, double lat, JSONObject p) throws JSONException {
    return new JSONObject()
        .put("type", "Feature")
        .put(
            "geometry",
            new JSONObject()
                .put("type", "Point")
                .put("coordinates", new JSONArray().put(lon).put(lat)))
        .put("properties", p);
  }
}
