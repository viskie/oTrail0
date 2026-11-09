package in.otrail;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Durable navigation destinations/route summaries, separate from the GPS journal. */
public final class NavigationHistory extends SQLiteOpenHelper {
  public record Entry(String name,double lat,double lon,long time,double meters){}
  public NavigationHistory(Context c){super(c.getApplicationContext(),"navigation-history.db",null,1);setWriteAheadLoggingEnabled(true);}
  @Override public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE routes(id TEXT PRIMARY KEY,name TEXT NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL,first_ms INTEGER NOT NULL,last_ms INTEGER NOT NULL,distance_m REAL NOT NULL,duration_s REAL NOT NULL)");}
  @Override public void onUpgrade(SQLiteDatabase d,int old,int next){}
  public void remember(NavigationManager.State state){
    if(state.route()==null||state.target()==null)return;RoutePlan.Point origin=state.route().points.get(0);String identity=state.name()+"|"+state.target().lat()+"|"+state.target().lon()+"|"+origin.lat()+"|"+origin.lon()+"|"+state.route().distance;
    String id=UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();long now=System.currentTimeMillis();ContentValues row=new ContentValues();row.put("id",id);row.put("name",state.name());row.put("latitude",state.target().lat());row.put("longitude",state.target().lon());row.put("first_ms",now);row.put("last_ms",now);row.put("distance_m",state.route().distance);row.put("duration_s",state.route().duration);
    SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try{d.insertWithOnConflict("routes",null,row,SQLiteDatabase.CONFLICT_IGNORE);ContentValues touch=new ContentValues();touch.put("last_ms",now);d.update("routes",touch,"id=?",new String[]{id});d.setTransactionSuccessful();}finally{d.endTransaction();}
  }
  public List<Entry> entries(){List<Entry> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT name,latitude,longitude,last_ms,distance_m FROM routes ORDER BY last_ms DESC",null)){while(c.moveToNext())out.add(new Entry(c.getString(0),c.getDouble(1),c.getDouble(2),c.getLong(3),c.getDouble(4)));}return out;}
}
