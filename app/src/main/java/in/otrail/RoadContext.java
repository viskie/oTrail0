package in.otrail;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Separate, replaceable source cache. It never changes the trail journal. */
public final class RoadContext extends SQLiteOpenHelper {
  public record Landmark(String key,String name,String kind,double lat,double lon,double meters){}
  public record Snapshot(String road,List<Landmark> landmarks,long fetched,boolean cached,String note){}
  public RoadContext(Context c){super(c,"road-cache.db",null,1);setWriteAheadLoggingEnabled(true);}
  public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE areas(key TEXT PRIMARY KEY,latitude REAL,longitude REAL,fetched_ms INTEGER,payload TEXT NOT NULL)");}
  public void onUpgrade(SQLiteDatabase d,int old,int next){}
  private record Area(JSONObject payload,long fetched){}
  private Area area(double lat,double lon) throws Exception {
    Area best=null;double distance=Double.POSITIVE_INFINITY;
    try(Cursor c=getReadableDatabase().rawQuery("SELECT latitude,longitude,fetched_ms,payload FROM areas WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? ORDER BY fetched_ms DESC",new String[]{""+(lat-.025),""+(lat+.025),""+(lon-.03),""+(lon+.03)})) {
      while(c.moveToNext()){double d=StopDetector.distance(lat,lon,c.getDouble(0),c.getDouble(1));if(d<1200&&d<distance){distance=d;best=new Area(new JSONObject(c.getString(3)),c.getLong(2));}}
    }
    return best;
  }
  public Snapshot cached(double lat,double lon,double bearing) throws Exception {
    Area area=area(lat,lon);return area==null?new Snapshot("",List.of(),0,true,"Road data not cached here"):parse(area.payload(),lat,lon,bearing,area.fetched(),true);
  }
  public Snapshot fetch(double lat,double lon,double bearing) throws Exception {
    if(lat<6||lat>38||lon<68||lon>98)throw new IOException("Road lookup is limited to India");
    String around=String.format(Locale.US,"(around:2500,%.6f,%.6f)",lat,lon);
    String roads="way"+around+"[highway~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|service|living_street|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$\"];";
    String query="[out:json][timeout:20];("+roads+");out tags geom 1800;(nwr"+around+"[amenity=place_of_worship];nwr"+around+"[tourism~\"^(attraction|museum|viewpoint|artwork|information)$\"];nwr"+around+"[historic];nwr"+around+"[natural=peak];);out tags center 1200;";
    HttpURLConnection connection=(HttpURLConnection)new URL("https://overpass-api.de/api/interpreter").openConnection();connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setConnectTimeout(12000);connection.setReadTimeout(30000);connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");connection.setRequestProperty("User-Agent","oTraiL/0.3.0 (personal India navigator)");
    JSONObject payload;
    try {
      try(OutputStream out=connection.getOutputStream()){out.write(("data="+URLEncoder.encode(query,"UTF-8")).getBytes(StandardCharsets.UTF_8));}
      if(connection.getResponseCode()!=200)throw new IOException("Road service HTTP "+connection.getResponseCode()+" · cache retained");
      ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=connection.getInputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>6_000_000)throw new IOException("Road response too large");out.write(b,0,n);}}
      payload=new JSONObject(out.toString("UTF-8"));if(payload.has("remark"))throw new IOException("Road service returned an incomplete area");
    }finally{connection.disconnect();}
    long now=System.currentTimeMillis();cache(lat,lon,payload,now);return parse(payload,lat,lon,bearing,now,false);
  }
  void cache(double lat,double lon,JSONObject payload,long now) {
    ContentValues v=new ContentValues();v.put("key",Math.round(lat*1000)+":"+Math.round(lon*1000));v.put("latitude",lat);v.put("longitude",lon);v.put("fetched_ms",now);v.put("payload",payload.toString());getWritableDatabase().insertWithOnConflict("areas",null,v,SQLiteDatabase.CONFLICT_REPLACE);
  }
  static Snapshot parse(JSONObject payload,double lat,double lon,double bearing,long fetched,boolean cached) throws Exception {
    JSONArray elements=payload.getJSONArray("elements");List<JSONObject> roads=new ArrayList<>();JSONObject selected=null;double best=70;
    for(int i=0;i<elements.length();i++){JSONObject e=elements.getJSONObject(i);JSONObject t=e.optJSONObject("tags");JSONArray g=e.optJSONArray("geometry");if(t==null||!t.has("highway")||g==null||g.length()<2)continue;roads.add(e);double d=RoadGeometry.match(g,lat,lon).distance();if(d<best){best=d;selected=e;}}
    if(selected==null)return new Snapshot("",List.of(),fetched,cached,"No mapped road within 70 m of this point");
    String identity=RoadGeometry.identity(selected);List<Landmark> pois=new ArrayList<>();Set<String> seen=new HashSet<>();
    for(int i=0;i<elements.length();i++) {
      JSONObject e=elements.getJSONObject(i),t=e.optJSONObject("tags");if(t==null||t.has("highway"))continue;
      JSONObject point=e.has("lat")?e:e.optJSONObject("center");if(point==null)continue;
      double y=point.getDouble("lat"),x=point.getDouble("lon"),distance=StopDetector.distance(lat,lon,y,x);if(distance>2500)continue;
      if(Double.isFinite(bearing)){double forward=(y-lat)*111320*Math.cos(Math.toRadians(bearing))+(x-lon)*111320*Math.cos(Math.toRadians(lat))*Math.sin(Math.toRadians(bearing));if(forward< -100)continue;}
      double same=Double.POSITIVE_INFINITY,other=Double.POSITIVE_INFINITY;
      for(JSONObject r:roads) {
        double d=RoadGeometry.match(r.getJSONArray("geometry"),y,x).distance();
        if(RoadGeometry.identity(r).equals(identity))same=Math.min(same,d);
        else if(r.getJSONObject("tags").has("ref")||r.getJSONObject("tags").has("name"))other=Math.min(other,d);
      }
      if(same>100||other+20<same)continue;
      String religion=t.optString("religion"),kind=t.optString("amenity",t.optString("tourism",t.optString("historic","landmark")));
      if(kind.equals("place_of_worship"))kind=religion.isBlank()?"Religious place":religion.equals("hindu")?"Temple":religion.equals("muslim")?"Mosque":religion.equals("christian")?"Church":religion.equals("sikh")?"Gurdwara":religion+" religious place";
      String key=e.optString("type")+"/"+e.optLong("id"),name=t.optString("name",t.optString("name:en",kind));
      if(seen.add(key))pois.add(new Landmark(key,name,kind,y,x,distance));
    }
    pois.sort(Comparator.comparingDouble(Landmark::meters));return new Snapshot(RoadGeometry.label(selected),List.copyOf(pois),fetched,cached,"© OpenStreetMap contributors · roadside proximity, access not verified");
  }
}
