package in.otrail;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Read-only bundled India POI catalogue; never opens the trail journal. */
final class ReligiousDirectory {
  record Place(String id,String name,String religion,String state,String district,String city,String area,String pin,double lat,double lon,String date,String source,int score){
    String displayName(){return name.isBlank()?"Name not supplied · "+id:name;}
    //String detail(){return String.join(" · ",java.util.stream.Stream.of(religion,state,city.isBlank()?area:city,pin.isBlank()?"PIN not supplied":pin).filter(s->!s.isBlank()).toList());}
    String detail(){return java.util.stream.Stream.of(religion,state,city.isBlank()?area:city,pin.isBlank()?"PIN not supplied":pin).filter(s->!s.isBlank()).collect(java.util.stream.Collectors.joining(" · "));}
  }
  record Page(List<Place> places,int count){}
  private static SQLiteDatabase database;private static JSONObject metadata;
  private ReligiousDirectory(){}
  static synchronized JSONObject metadata(Context context)throws Exception{if(metadata==null)try(InputStream input=context.getAssets().open("religious-directory.json")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);metadata=new JSONObject(out.toString("UTF-8"));}return metadata;}
  private static synchronized SQLiteDatabase database(Context context)throws Exception{
    if(database!=null)return database;
    JSONObject info=metadata(context);File directory=new File(context.getFilesDir(),"religious-directory");directory.mkdirs();File file=new File(directory,"india-"+info.getString("sha256").substring(0,16)+".sqlite");
    if(!file.exists()){File temporary=new File(directory,file.getName()+".part");try(InputStream in=context.getAssets().open("religious-directory.sqlite");OutputStream out=new FileOutputStream(temporary)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}if(!temporary.renameTo(file))throw new IOException("Cannot prepare religious directory");}
    database=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS);return database;
  }
  static List<String> states(Context context)throws Exception{List<String> names=new ArrayList<>();try(Cursor cursor=database(context).rawQuery("SELECT DISTINCT state FROM places WHERE state<>'' ORDER BY state",null)){while(cursor.moveToNext())names.add(cursor.getString(0));}return names;}
  private static Place place(Cursor c){return new Place(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getString(6),c.getString(7),c.getDouble(8),c.getDouble(9),c.getString(10),c.getString(11),c.getInt(12));}
  private static final String COLUMNS="id,name,religion,state,district,city,area,pin,lat,lon,start_date,source,score";
  static Page search(Context context,String state,String religion,String city,String prefix,String query,int offset)throws Exception{
    StringBuilder where=new StringBuilder("religion<>'police'");List<String> args=new ArrayList<>();
    if(!state.isBlank()){where.append(" AND state=?");args.add(state);}
    if(religion.equals("other"))where.append(" AND religion NOT IN ('hindu','muslim','christian','buddhist','sikh','jain','police')");else if(!religion.isBlank()){where.append(" AND religion=?");args.add(religion);}
    if(!city.isBlank()){where.append(" AND (city LIKE ? ESCAPE '\\' OR area LIKE ? ESCAPE '\\' OR district LIKE ? ESCAPE '\\')");for(int i=0;i<3;i++)args.add(like(city));}
    if(!prefix.isBlank()){if(!prefix.matches("[1-9][0-9]{2}"))throw new IllegalArgumentException("Enter exactly the first 3 PIN digits");where.append(" AND pin LIKE ?");args.add(prefix+"%");}
    if(!query.isBlank()){where.append(" AND search LIKE ? ESCAPE '\\'");args.add(like(query.toLowerCase(Locale.ROOT)));}
    SQLiteDatabase db=database(context);String[] values=args.toArray(new String[0]);int count;try(Cursor c=db.rawQuery("SELECT count(*) FROM places WHERE "+where,values)){c.moveToFirst();count=c.getInt(0);}
    List<Place> result=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT "+COLUMNS+" FROM places WHERE "+where+" ORDER BY score DESC,year ASC,name,id LIMIT 50 OFFSET "+Math.max(0,offset),values)){while(c.moveToNext())result.add(place(c));}return new Page(List.copyOf(result),count);
  }
  private static String like(String text){return "%"+text.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";}
  static JSONObject nearby(Context context,double lat,double lon)throws Exception{
    double dy=.28,dx=dy/Math.max(.1,Math.cos(Math.toRadians(lat)));JSONArray elements=new JSONArray();
    try(Cursor c=database(context).rawQuery("SELECT "+COLUMNS+" FROM places WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",new String[]{""+(lat-dy),""+(lat+dy),""+(lon-dx),""+(lon+dx)})){
      while(c.moveToNext()){Place p=place(c);JSONObject tags=new JSONObject().put("amenity",p.religion().equals("police")?"police":"place_of_worship").put("religion",p.religion()).put("name",p.name());elements.put(new JSONObject().put("lat",p.lat()).put("lon",p.lon()).put("tags",tags));}
    }return new JSONObject().put("elements",elements);
  }
}
