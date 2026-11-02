package in.otrail;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.location.Location;
import org.json.*;
import java.io.*;
import java.util.*;

public final class TrailDb extends SQLiteOpenHelper {
    public TrailDb(Context c) { super(c, "trails.db", null, 1); setWriteAheadLoggingEnabled(true); }
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
    private JSONObject feature(double lon,double lat,JSONObject p)throws JSONException{return new JSONObject().put("type","Feature").put("geometry",new JSONObject().put("type","Point").put("coordinates",new JSONArray().put(lon).put(lat))).put("properties",p);}
}
