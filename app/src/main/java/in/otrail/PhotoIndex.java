package in.otrail;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;

/** CAM's normalized index. JPEG and JSON originals live in the user-selected public folder. */
public final class PhotoIndex {
  private PhotoIndex() {}
  static void schema(SQLiteDatabase d) {
    d.execSQL("CREATE TABLE IF NOT EXISTS photos(id TEXT PRIMARY KEY,stop_id INTEGER REFERENCES stops(id) ON DELETE SET NULL,captured_ms INTEGER NOT NULL,latitude REAL,longitude REAL,accuracy_m REAL,fix_ms INTEGER,uri TEXT NOT NULL UNIQUE,filename TEXT NOT NULL,metadata_json TEXT NOT NULL)");
    d.execSQL("CREATE INDEX IF NOT EXISTS photos_stop_time ON photos(stop_id,captured_ms)");
    d.execSQL("CREATE INDEX IF NOT EXISTS photos_capture_time ON photos(captured_ms)");
  }
  static Long stopAt(SQLiteDatabase d,long time,double lat,double lon) {
    try(Cursor c=d.rawQuery("SELECT id,latitude,longitude FROM stops WHERE arrival_ms<=? AND (departure_ms>=? OR (departure_ms IS NULL AND observed_ms>=?)) ORDER BY arrival_ms DESC LIMIT 1",new String[]{""+time,""+time,""+(time-120000)})) {
      if(c.moveToFirst()&&distance(lat,lon,c.getDouble(1),c.getDouble(2))<=150)return c.getLong(0);
    }
    return null;
  }
  static double distance(double lat,double lon,double otherLat,double otherLon) {
    double a=Math.toRadians(lat-otherLat),b=Math.toRadians(lon-otherLon);
    double h=Math.sin(a/2)*Math.sin(a/2)+Math.cos(Math.toRadians(lat))*Math.cos(Math.toRadians(otherLat))*Math.sin(b/2)*Math.sin(b/2);
    return 12742000*Math.asin(Math.min(1,Math.sqrt(h)));
  }
  static JSONObject context(TrailDb db,long time)throws Exception {
    JSONObject j=new JSONObject().put("captured_ms",time);TrailDb.Fix f=db.latestFix();
    if(f!=null){j.put("latitude",f.lat()).put("longitude",f.lon()).put("accuracy_m",f.accuracy()).put("fix_ms",f.time());
      // A stale fix is retained as explicitly aged metadata, never silently treated as a new fix.
      if(Math.abs(time-f.time())<=120000){Long stop=stopAt(db.getReadableDatabase(),time,f.lat(),f.lon());if(stop!=null){
        j.put("stop_id",stop);try(Cursor c=db.getReadableDatabase().rawQuery("SELECT trips.started_ms,stops.arrival_ms FROM stops JOIN trips ON trips.id=stops.trip_id WHERE stops.id=?",new String[]{""+stop})){
          if(c.moveToFirst())j.put("stop_uid","s"+c.getLong(0)+"-"+c.getLong(1));
        }
      }}
    }
    return j;
  }
  public static void validate(JSONObject j)throws Exception {
    if(!j.getString("id").matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")||j.getLong("captured_ms")<=0)throw new IllegalArgumentException("Invalid photo identity/time");
    if(!j.getString("uri").startsWith("content://")||j.getString("filename").contains("/")||j.getString("filename").length()>240)throw new IllegalArgumentException("Invalid photo destination");
    if(j.has("latitude")&&(Math.abs(j.getDouble("latitude"))>90||Math.abs(j.getDouble("longitude"))>180||!Double.isFinite(j.getDouble("latitude"))||!Double.isFinite(j.getDouble("longitude"))))throw new IllegalArgumentException("Invalid photo coordinates");
  }
  static void save(TrailDb db,String metadata)throws Exception {
    JSONObject j=new JSONObject(metadata);validate(j);SQLiteDatabase d=db.getWritableDatabase();
    Long stop=null;
    if(j.has("latitude")&&Math.abs(j.getLong("captured_ms")-j.optLong("fix_ms",0))<=120000)stop=stopAt(d,j.getLong("captured_ms"),j.getDouble("latitude"),j.getDouble("longitude"));
    j.put("stop_id",stop==null?JSONObject.NULL:stop);
    ContentValues v=new ContentValues();v.put("id",j.getString("id"));if(stop==null)v.putNull("stop_id");else v.put("stop_id",stop);
    v.put("captured_ms",j.getLong("captured_ms"));for(String key:new String[]{"latitude","longitude","accuracy_m"})if(j.has(key))v.put(key,j.getDouble(key));
    v.put("fix_ms",j.optLong("fix_ms",0));v.put("uri",j.getString("uri"));v.put("filename",j.getString("filename"));v.put("metadata_json",j.toString());
    d.insertWithOnConflict("photos",null,v,SQLiteDatabase.CONFLICT_REPLACE);
  }
  static String list(TrailDb db,long stop)throws Exception {
    JSONArray out=new JSONArray();
    try(Cursor c=db.getReadableDatabase().rawQuery("SELECT metadata_json,stop_id FROM photos"+(stop>0?" WHERE stop_id=?":"")+" ORDER BY captured_ms DESC",stop>0?new String[]{""+stop}:null)){
      while(c.moveToNext())out.put(new JSONObject(c.getString(0)).put("stop_id",c.isNull(1)?JSONObject.NULL:c.getLong(1)));
    }
    return out.toString();
  }
}
