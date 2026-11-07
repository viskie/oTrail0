package in.otrail;

import android.app.Instrumentation;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.io.File;
import org.json.*;

/** Isolated database only; does not launch activities or stop/start the recorder. */
public final class PhotoInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle args){super.onCreate(args);start();}
  private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  @Override public void onStart(){Bundle result=new Bundle();Context target=getTargetContext();File root=new File(target.getCacheDir(),"cam-test-db");root.mkdirs();
    Context c=new ContextWrapper(target){@Override public Context getApplicationContext(){return this;}@Override public File getDatabasePath(String name){return new File(root,name);}@Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}@Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,android.database.DatabaseErrorHandler errors){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),factory,errors);}};
    try{
      String before;
      try(TrailDb db=new TrailDb(c)){
        SQLiteDatabase d=db.getWritableDatabase();d.execSQL("INSERT INTO trips(id,started_ms) VALUES(1,1000)");d.execSQL("INSERT INTO stops(id,trip_id,arrival_ms,observed_ms,latitude,longitude) VALUES(7,1,10000,160000,29,78)");d.execSQL("INSERT INTO fixes(id,trip_id,time_ms,latitude,longitude,accuracy_m,speed_mps,source) VALUES(2,1,150000,29,78,5,0,'test')");
        java.io.StringWriter writer=new java.io.StringWriter();db.writeBackup(writer);before=writer.toString();d.execSQL("DROP TABLE photos");d.setVersion(3);
      }
      try(TrailDb db=new TrailDb(c)){
        require(db.getReadableDatabase().getVersion()==4,"v3 migration failed");java.io.StringWriter writer=new java.io.StringWriter();db.writeBackup(writer);JSONObject first=new JSONObject(before),second=new JSONObject(writer.toString());for(String table:new String[]{"trips","fixes","stops","sync_logs","settings"})require(first.get(table).toString().equals(second.get(table).toString()),"photo migration altered "+table);
        JSONObject j=PhotoIndex.context(db,160000);require(j.getLong("stop_id")==7,"capture stop association");require(j.getString("stop_uid").equals("s1000-10000"),"stable stop identity");
        j.put("id","eb11c7d1-d714-45f2-81de-50d9389a475c").put("uri","content://photos/old").put("filename","photo.jpg");PhotoIndex.save(db,j.toString());PhotoIndex.save(db,j.toString());require(new JSONArray(PhotoIndex.list(db,7)).length()==1,"index not idempotent");
        j.put("uri","content://photos/reselected");PhotoIndex.save(db,j.toString());require(new JSONArray(PhotoIndex.list(db,7)).getJSONObject(0).getString("uri").equals("content://photos/reselected"),"reselected URI not repaired");
        require(!PhotoIndex.context(db,400000).has("stop_id"),"stale location assigned a stop");
        try(Cursor cursor=db.getReadableDatabase().rawQuery("PRAGMA foreign_key_check",null)){require(!cursor.moveToFirst(),"broken photo FK");}
      }
      result.putString("stream","CAM migration, journal preservation, stop association, recovery and stale GPS checks passed\n");finish(-1,result);
    }catch(Throwable e){result.putString("stream",android.util.Log.getStackTraceString(e));finish(0,result);}finally{for(File f:root.listFiles())f.delete();root.delete();}
  }
}
