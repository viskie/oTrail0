package in.otrail;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.location.Location;
import org.json.*;
import java.io.*;
import java.util.*;

public final class TrailDb extends SQLiteOpenHelper {
    private final Context context;
    public TrailDb(Context c) { super(c, "trails.db", null, 1); context=c.getApplicationContext();setWriteAheadLoggingEnabled(true); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE trips(id INTEGER PRIMARY KEY, started_ms INTEGER NOT NULL, ended_ms INTEGER)");
        d.execSQL("CREATE TABLE fixes(id INTEGER PRIMARY KEY, trip_id INTEGER NOT NULL REFERENCES trips(id), time_ms INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, accuracy_m REAL NOT NULL, speed_mps REAL NOT NULL, altitude_m REAL, bearing_deg REAL, source TEXT NOT NULL)");
        d.execSQL("CREATE INDEX fixes_trip_time ON fixes(trip_id,time_ms)");
        d.execSQL("CREATE TABLE stops(id INTEGER PRIMARY KEY, trip_id INTEGER NOT NULL REFERENCES trips(id), arrival_ms INTEGER NOT NULL, departure_ms INTEGER, observed_ms INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL)");
        d.execSQL("CREATE TABLE sync_logs(id INTEGER PRIMARY KEY, time_ms INTEGER NOT NULL, provider TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase d,int a,int b) { throw new IllegalStateException("Migration required"); }
    public synchronized long activeTrip() { try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM trips WHERE ended_ms IS NULL ORDER BY id DESC LIMIT 1",null)){return c.moveToFirst()?c.getLong(0):-1;} }
    public synchronized long begin() { long id=activeTrip(); if(id!=-1)return id; ContentValues v=new ContentValues();v.put("started_ms",System.currentTimeMillis());return getWritableDatabase().insertOrThrow("trips",null,v); }
    public synchronized void finish(long trip) { SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try{d.execSQL("UPDATE stops SET departure_ms=observed_ms WHERE trip_id=? AND departure_ms IS NULL",new Object[]{trip});d.execSQL("UPDATE trips SET ended_ms=? WHERE id=?",new Object[]{System.currentTimeMillis(),trip});d.setTransactionSuccessful();}finally{d.endTransaction();} }
    public synchronized void recoverStops(long trip) { getWritableDatabase().execSQL("UPDATE stops SET departure_ms=observed_ms WHERE trip_id=? AND departure_ms IS NULL", new Object[]{trip}); }
    public synchronized void record(long trip, Location l, StopDetector detector) {
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try {
            ContentValues v=new ContentValues(); v.put("trip_id",trip);v.put("time_ms",l.getTime());v.put("latitude",l.getLatitude());v.put("longitude",l.getLongitude());v.put("accuracy_m",l.getAccuracy());v.put("speed_mps",l.hasSpeed()?l.getSpeed():0);if(l.hasAltitude())v.put("altitude_m",l.getAltitude());if(l.hasBearing())v.put("bearing_deg",l.getBearing());v.put("source",l.getProvider());d.insertOrThrow("fixes",null,v);
            StopDetector.Event e=detector.accept(l.getTime(),l.getLatitude(),l.getLongitude(),l.getAccuracy(),l.hasSpeed()?l.getSpeed():0);
            if(e!=null && e.closed())d.execSQL("UPDATE stops SET departure_ms=? WHERE trip_id=? AND departure_ms IS NULL",new Object[]{e.departure(),trip});
            if(e!=null && e.opened()){ContentValues s=new ContentValues();s.put("trip_id",trip);s.put("arrival_ms",e.arrival());s.put("observed_ms",l.getTime());s.put("latitude",e.lat());s.put("longitude",e.lon());d.insertOrThrow("stops",null,s);}
            if(detector.isStopped())d.execSQL("UPDATE stops SET observed_ms=? WHERE trip_id=? AND departure_ms IS NULL",new Object[]{l.getTime(),trip});
            d.setTransactionSuccessful();
        }finally{d.endTransaction();}
    }
    public record Fix(long id,long trip,long time,double lat,double lon,float accuracy,float speed){}
    public record Stop(long trip,long arrival,long departure,long observed,double lat,double lon){}
    public List<Fix> fixes(long trip) { List<Fix> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,trip_id,time_ms,latitude,longitude,accuracy_m,speed_mps FROM fixes WHERE trip_id=? ORDER BY time_ms,id",new String[]{""+trip})){while(c.moveToNext())out.add(new Fix(c.getLong(0),c.getLong(1),c.getLong(2),c.getDouble(3),c.getDouble(4),c.getFloat(5),c.getFloat(6)));}return out; }
    public List<Stop> stops(long trip) { List<Stop> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT trip_id,arrival_ms,departure_ms,observed_ms,latitude,longitude FROM stops WHERE trip_id=? ORDER BY arrival_ms",new String[]{""+trip})){while(c.moveToNext())out.add(new Stop(c.getLong(0),c.getLong(1),c.isNull(2)?0:c.getLong(2),c.getLong(3),c.getDouble(4),c.getDouble(5)));}return out; }
    public List<Long> trips() { List<Long> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM trips ORDER BY id DESC",null)){while(c.moveToNext())out.add(c.getLong(0));}return out; }
    public synchronized void log(String provider,String status,String detail) { ContentValues v=new ContentValues();v.put("time_ms",System.currentTimeMillis());v.put("provider",provider);v.put("status",status);v.put("detail",detail);getWritableDatabase().insertOrThrow("sync_logs",null,v); }
    public String logs(){StringBuilder b=new StringBuilder();try(Cursor c=getReadableDatabase().rawQuery("SELECT time_ms,provider,status,detail FROM sync_logs ORDER BY id DESC LIMIT 40",null)){while(c.moveToNext())b.append(new Date(c.getLong(0))).append("\n").append(c.getString(1)).append(" · ").append(c.getString(2)).append("\n").append(c.getString(3)).append("\n\n");}return b.length()==0?"No manual updates yet.":b.toString();}
    public void export(Writer w) throws Exception {
        // Stream each feature rather than assembling a multi-year trail in memory.
        w.write("{\"type\":\"FeatureCollection\",\"schema_version\":1,\"features\":[");boolean first=true;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM fixes ORDER BY trip_id,time_ms,id",null)){while(c.moveToNext()){
            JSONObject props=new JSONObject();props.put("kind","fix");String[] names={"id","trip_id","time_ms","accuracy_m","speed_mps","altitude_m","bearing_deg","source"};for(String name:names){int i=c.getColumnIndexOrThrow(name);props.put(name,c.isNull(i)?JSONObject.NULL:c.getType(i)==Cursor.FIELD_TYPE_STRING?c.getString(i):c.getType(i)==Cursor.FIELD_TYPE_INTEGER?c.getLong(i):c.getDouble(i));}
            JSONObject f=feature(c.getDouble(c.getColumnIndexOrThrow("longitude")),c.getDouble(c.getColumnIndexOrThrow("latitude")),props);if(!first)w.write(",");w.write(f.toString());first=false;
        }}
        for(long trip:trips())for(Stop s:stops(trip)){JSONObject p=new JSONObject().put("kind","stop").put("trip_id",trip).put("arrival_ms",s.arrival()).put("departure_ms",s.departure()==0?JSONObject.NULL:s.departure()).put("observed_ms",s.observed()).put("duration_ms",s.observed()-s.arrival());if(!first)w.write(",");w.write(feature(s.lon(),s.lat(),p).toString());first=false;}
        w.write("]}");w.flush();
    }
    private static final String[] BACKUP_TABLES={"trips","fixes","stops","sync_logs"};
    /** JSON snapshot preserves normalized tables, IDs, units and nullable values. */
    public void writeBackup(Writer output)throws Exception{
        android.util.JsonWriter w=new android.util.JsonWriter(output);SQLiteDatabase d=getReadableDatabase();d.beginTransactionNonExclusive();
        try{w.beginObject();w.name("otrail_backup_version").value(1);w.name("created_ms").value(System.currentTimeMillis());
            for(String table:BACKUP_TABLES){w.name(table).beginArray();try(Cursor c=d.rawQuery("SELECT * FROM "+table+" ORDER BY id",null)){
                while(c.moveToNext()){w.beginObject();for(int i=0;i<c.getColumnCount();i++){w.name(c.getColumnName(i));switch(c.getType(i)){case Cursor.FIELD_TYPE_NULL:w.nullValue();break;case Cursor.FIELD_TYPE_INTEGER:w.value(c.getLong(i));break;case Cursor.FIELD_TYPE_FLOAT:w.value(c.getDouble(i));break;default:w.value(c.getString(i));}}w.endObject();}
            }w.endArray();}
            w.name("settings").beginObject();android.content.SharedPreferences ui=context.getSharedPreferences("MainActivity",0);w.name("provider").value(ui.getInt("provider",0));w.name("offline").value(ui.getBoolean("offline",false));
            android.content.SharedPreferences pack=context.getSharedPreferences("packs",0);for(String key:new String[]{"mapsforge_status","geofabrik_status","mapsforge_retained","geofabrik_retained","raw_path"})if(pack.contains(key))w.name(key).value(pack.getString(key,""));w.endObject();
            w.endObject();w.flush();d.setTransactionSuccessful();
        }finally{d.endTransaction();}
    }
    /** Merge history with new always-on fixes. Any parsing failure rolls back the whole restore. */
    public void restoreBackup(Reader input)throws Exception{
        android.util.JsonReader r=new android.util.JsonReader(input);android.content.ContentValues settings=new android.content.ContentValues();SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        try{
            r.beginObject();if(!r.hasNext()||!r.nextName().equals("otrail_backup_version")||r.nextInt()!=1)throw new IOException("Not a supported oTraiL backup");
            java.util.Set<String> restored=new java.util.HashSet<>();java.util.Map<Long,Long> tripIds=new java.util.HashMap<>();java.util.Set<Long> historicalTrips=new java.util.HashSet<>();
            while(r.hasNext()){String table=r.nextName();if(table.equals("settings")){r.beginObject();while(r.hasNext()){String key=r.nextName();if(key.equals("provider")){int value=r.nextInt();if(value<0||value>2)throw new IOException("Invalid provider");settings.put(key,value);}else if(key.equals("offline"))settings.put(key,r.nextBoolean());else if(java.util.Arrays.asList("mapsforge_status","geofabrik_status","mapsforge_retained","geofabrik_retained","raw_path").contains(key))settings.put(key,r.nextString());else r.skipValue();}r.endObject();continue;}if(!java.util.Arrays.asList(BACKUP_TABLES).contains(table)){r.skipValue();continue;}if(!restored.add(table))throw new IOException("Duplicate table in backup");
                java.util.Set<String> allowed=new java.util.HashSet<>();try(Cursor columns=d.rawQuery("SELECT * FROM "+table+" LIMIT 0",null)){java.util.Collections.addAll(allowed,columns.getColumnNames());}
                r.beginArray();while(r.hasNext()){ContentValues v=new ContentValues();r.beginObject();while(r.hasNext()){String field=r.nextName();if(!allowed.contains(field))throw new IOException("Unknown backup field: "+field);if(r.peek()==android.util.JsonToken.NULL){r.nextNull();v.putNull(field);}else{v.put(field,r.nextString());}}r.endObject();
                    if(table.equals("trips")){long original=v.getAsLong("id");long mapped=-1;try(Cursor existing=d.rawQuery("SELECT id FROM trips WHERE started_ms=? LIMIT 1",new String[]{v.getAsString("started_ms")})){if(existing.moveToFirst())mapped=existing.getLong(0);}
                        if(mapped==-1){v.remove("id");if(v.get("ended_ms")==null){v.put("ended_ms",v.getAsLong("started_ms"));}mapped=d.insertOrThrow(table,null,v);historicalTrips.add(mapped);}tripIds.put(original,mapped);
                    }else if(table.equals("fixes")||table.equals("stops")){
                        Long mapped=tripIds.get(v.getAsLong("trip_id"));if(mapped==null)throw new IOException("Backup references an unknown trip");v.put("trip_id",mapped);v.remove("id");String time=table.equals("fixes")?"time_ms":"arrival_ms";
                        boolean duplicate;try(Cursor existing=d.rawQuery("SELECT id FROM "+table+" WHERE trip_id=? AND "+time+"=? LIMIT 1",new String[]{""+mapped,v.getAsString(time)})){duplicate=existing.moveToFirst();}
                        if(!duplicate){if(table.equals("stops")&&v.get("departure_ms")==null)v.put("departure_ms",v.getAsLong("observed_ms"));d.insertOrThrow(table,null,v);}
                    }else{v.remove("id");boolean duplicate;try(Cursor existing=d.rawQuery("SELECT id FROM sync_logs WHERE time_ms=? AND provider=? AND status=? AND detail=? LIMIT 1",new String[]{v.getAsString("time_ms"),v.getAsString("provider"),v.getAsString("status"),v.getAsString("detail")})){duplicate=existing.moveToFirst();}if(!duplicate)d.insertOrThrow(table,null,v);}
}r.endArray();
            }r.endObject();if(restored.size()!=BACKUP_TABLES.length)throw new IOException("Incomplete trail backup");
            if(r.peek()!=android.util.JsonToken.END_DOCUMENT)throw new IOException("Unexpected data after backup");
            for(long id:historicalTrips)d.execSQL("UPDATE trips SET ended_ms=MAX(COALESCE(ended_ms,started_ms),COALESCE((SELECT MAX(time_ms) FROM fixes WHERE trip_id=?),started_ms)) WHERE id=?",new Object[]{id,id});
            d.setTransactionSuccessful();
        }finally{d.endTransaction();}
        android.content.SharedPreferences.Editor ui=context.getSharedPreferences("MainActivity",0).edit();if(settings.containsKey("provider"))ui.putInt("provider",settings.getAsInteger("provider"));if(settings.containsKey("offline"))ui.putBoolean("offline",settings.getAsBoolean("offline"));ui.apply();
        android.content.SharedPreferences.Editor pack=context.getSharedPreferences("packs",0).edit();for(String key:settings.keySet())if(!key.equals("provider")&&!key.equals("offline")){String value=settings.getAsString(key);if(key.endsWith("_status")&&value.startsWith("Ready"))value="Previously downloaded · import retained file";pack.putString(key,value);}pack.apply();
    }
    private JSONObject feature(double lon,double lat,JSONObject p)throws JSONException{return new JSONObject().put("type","Feature").put("geometry",new JSONObject().put("type","Point").put("coordinates",new JSONArray().put(lon).put(lat))).put("properties",p);}
}
