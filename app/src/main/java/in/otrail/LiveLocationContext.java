package in.otrail;

import android.app.Activity;
import android.location.Address;
import android.net.*;
import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Bounded asynchronous address/worship cache. Never uses the GPS writer or journal database. */
final class LiveLocationContext implements AutoCloseable {
  private final Activity activity;private final Runnable changed;private final ExecutorService worker=Executors.newFixedThreadPool(2);
  private final File directory;private volatile boolean closed;private boolean addressBusy,worshipBusy,catalogBusy;private JSONObject catalog;private double catalogLat=Double.NaN,catalogLon;private String catalogProblem="";private long addressAttempt,worshipAttempt;private double addressLat=Double.NaN,addressLon;
  private String address="Locality / road lookup pending",road="",worshipProblem="";private JSONObject worship;private long worshipFetched;private boolean policeSearched;private double worshipLat,worshipLon;
  LiveLocationContext(Activity a,Runnable changed){activity=a;this.changed=changed;directory=new File(a.getFilesDir(),"live-context");directory.mkdirs();try{JSONObject saved=read(new File(directory,"worship.json"));worship=saved.getJSONObject("payload");policeSearched=saved.optInt("schema",0)>=2;worshipFetched=policeSearched?saved.getLong("fetched"):0;worshipLat=saved.getDouble("lat");worshipLon=saved.getDouble("lon");}catch(Exception ignored){}}
  private boolean online(){ConnectivityManager manager=(ConnectivityManager)activity.getSystemService(Context.CONNECTIVITY_SERVICE);NetworkCapabilities capabilities=manager.getNetworkCapabilities(manager.getActiveNetwork());return capabilities!=null&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);}
  String address(){return address;}
  private JSONObject combined(double lat,double lon)throws Exception{
    JSONArray all=new JSONArray();if(catalog!=null&&PhotoIndex.distance(lat,lon,catalogLat,catalogLon)<5000){JSONArray entries=catalog.optJSONArray("elements");if(entries!=null)for(int i=0;i<entries.length();i++)all.put(entries.get(i));}
    if(worship!=null&&PhotoIndex.distance(lat,lon,worshipLat,worshipLon)<5000){JSONArray entries=worship.optJSONArray("elements");if(entries!=null)for(int i=0;i<entries.length();i++)all.put(entries.get(i));}
    return new JSONObject().put("elements",all);
  }
  String worship(double lat,double lon){try{if(catalog!=null||worship!=null)return "Religious places nearby\n"+NearbySites.summary(combined(lat,lon),lat,lon,catalog!=null||policeSearched)+"\nStraight-line · bundled India directory"+(online()?" + online cache":" · offline");}catch(Exception ignored){}return catalogProblem.isBlank()?"Religious places nearby: preparing offline directory":"Religious directory: "+catalogProblem;}
  List<NearbySites.Site> sites(double lat,double lon){try{return NearbySites.parse(combined(lat,lon),lat,lon);}catch(Exception ignored){return List.of();}}
  void update(double lat,double lon,String cachedRoad){
    if(closed)return;road=cachedRoad;long now=System.currentTimeMillis();String key=Math.round(lat*400)+"_"+Math.round(lon*400);File file=new File(directory,"address-"+key+".json");
    if(!Double.isFinite(addressLat)||PhotoIndex.distance(lat,lon,addressLat,addressLon)>100){try{JSONObject saved=read(file);address=saved.getString("address");addressLat=lat;addressLon=lon;changed.run();}catch(Exception ignored){}}
    if(online()&&!addressBusy&&now-addressAttempt>=30000&&(!Double.isFinite(addressLat)||PhotoIndex.distance(lat,lon,addressLat,addressLon)>100||now-addressAttempt>=300000)){
      addressBusy=true;addressAttempt=now;worker.execute(()->{try{Address found=StopGeocoder.lookupAddress(activity,lat,lon);if(found!=null){LinkedHashSet<String> names=new LinkedHashSet<>();for(String value:new String[]{found.getSubLocality(),found.getLocality()})if(value!=null&&!value.isBlank())names.add(value);if(names.isEmpty()&&found.getSubAdminArea()!=null)names.add(found.getSubAdminArea());String street=found.getThoroughfare();String result=String.join(" · ",names);if(street!=null&&!street.isBlank())result+=(result.isBlank()?"":"\n")+street;else if(!cachedRoad.isBlank())result+=(result.isBlank()?"":"\n")+cachedRoad;if(result.isBlank())result="Locality / street unavailable";write(file,new JSONObject().put("address",result).put("fetched",System.currentTimeMillis()));String value=result;post(()->{address=value;addressLat=lat;addressLon=lon;});}}catch(Exception ignored){}finally{post(()->addressBusy=false);}});
    }
    if(!catalogBusy&&(catalog==null||PhotoIndex.distance(lat,lon,catalogLat,catalogLon)>1000)){
      catalogBusy=true;worker.execute(()->{try{JSONObject records=ReligiousDirectory.nearby(activity,lat,lon);post(()->{catalog=records;catalogLat=lat;catalogLon=lon;catalogProblem="";});}catch(Exception error){post(()->catalogProblem=error.getMessage());}finally{post(()->catalogBusy=false);}});
    }
    boolean covered=worship!=null&&PhotoIndex.distance(lat,lon,worshipLat,worshipLon)<=5000;
    if(online()&&!worshipBusy&&now-worshipAttempt>=900000&&(!covered||now-worshipFetched>=1800000)){
      worshipBusy=true;worshipAttempt=now;worker.execute(()->{try{JSONObject payload=fetch(lat,lon);long time=System.currentTimeMillis();write(new File(directory,"worship.json"),new JSONObject().put("schema",2).put("payload",payload).put("fetched",time).put("lat",lat).put("lon",lon));post(()->{worship=payload;worshipFetched=time;policeSearched=true;worshipLat=lat;worshipLon=lon;worshipProblem="";});}catch(Exception error){android.util.Log.w("oTraiL","Worship lookup unavailable: "+error.getMessage());post(()->worshipProblem=error.toString());}finally{post(()->worshipBusy=false);}});
    }
  }
  private void post(Runnable action){activity.runOnUiThread(()->{if(!closed&&!activity.isDestroyed()){action.run();changed.run();}});}
  private static JSONObject read(File file)throws Exception{try(InputStream in=new AtomicFile(file).openRead()){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>6000000)throw new IOException("Cache too large");out.write(b,0,n);}return new JSONObject(out.toString("UTF-8"));}}
  private static void write(File file,JSONObject value)throws Exception{AtomicFile atomic=new AtomicFile(file);FileOutputStream out=atomic.startWrite();try{out.write(value.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(out);}catch(Exception e){atomic.failWrite(out);throw e;}}
  private static JSONObject fetch(double lat,double lon)throws Exception{
    Exception last=null;
    try{return fetchArcgis(lat,lon);}catch(Exception error){last=error;}
    for(String endpoint:UiConfig.LIVE_WORSHIP_SERVERS)try{return fetchAt(endpoint,lat,lon);}catch(Exception error){last=error;}
    throw last==null?new IOException("No religious-place server configured"):last;
  }
  private static JSONObject fetchArcgis(double lat,double lon)throws Exception{
    JSONArray elements=new JSONArray();double dy=.28,dx=dy/Math.max(.1,Math.cos(Math.toRadians(lat)));
    for(String layer:new String[]{"POW","Police"}){
      String geometry=String.format(Locale.US,"%.6f,%.6f,%.6f,%.6f",lon-dx,lat-dy,lon+dx,lat+dy);
      String endpoint="https://livingatlas.esri.in/server/rest/services/OSM/"+layer+"/MapServer/0/query?f=json&where=1%3D1&outFields=*&outSR=4326&inSR=4326&geometryType=esriGeometryEnvelope&geometry="+geometry+"&returnGeometry=true&resultRecordCount=2000";
      HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();c.setConnectTimeout(8000);c.setReadTimeout(16000);c.setRequestProperty("User-Agent","oTraiL/0.4.15 (+https://github.com/viskie/oTrail0)");
      try{if(c.getResponseCode()!=200)throw new IOException(layer+" HTTP "+c.getResponseCode());ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>6000000)throw new IOException("Map response too large");bytes.write(b,0,n);}}JSONObject result=new JSONObject(bytes.toString("UTF-8"));if(result.has("error"))throw new IOException(layer+" map query failed");JSONArray features=result.optJSONArray("features");if(features==null)continue;
        for(int i=0;i<features.length();i++){JSONObject f=features.getJSONObject(i),a=f.optJSONObject("attributes"),g=f.optJSONObject("geometry");if(a==null||g==null||!g.has("x")||!g.has("y"))continue;String kind=a.optString("fclass").toLowerCase(Locale.ROOT);String religion=kind.startsWith("christian")?"christian":kind.startsWith("muslim")?"muslim":kind;JSONObject tags=new JSONObject().put("amenity",layer.equals("Police")?"police":"place_of_worship").put("religion",religion).put("name",a.optString("fname",""));elements.put(new JSONObject().put("lat",g.getDouble("y")).put("lon",g.getDouble("x")).put("tags",tags));}
      }finally{c.disconnect();}
    }return new JSONObject().put("elements",elements);
  }

  private static JSONObject fetchAt(String endpoint,double lat,double lon)throws Exception{
    String query=String.format(Locale.US,"[out:json][timeout:15];(nwr(around:30000,%.6f,%.6f)[amenity=place_of_worship][religion~\"^(hindu|muslim|christian|sikh|buddhist)$\"];nwr(around:30000,%.6f,%.6f)[amenity=police];);out center;",lat,lon,lat,lon);
    HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(12000);c.setReadTimeout(22000);c.setRequestProperty("User-Agent","oTraiL personal India navigator");c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
    try{try(OutputStream out=c.getOutputStream()){out.write(("data="+URLEncoder.encode(query,"UTF-8")).getBytes(StandardCharsets.UTF_8));}if(c.getResponseCode()!=200)throw new IOException("Religious-place HTTP "+c.getResponseCode());ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>6000000)throw new IOException("Worship result too large");bytes.write(b,0,n);}}JSONObject result=new JSONObject(bytes.toString("UTF-8"));if(result.has("remark"))throw new IOException("Incomplete worship result");return result;}finally{c.disconnect();}
  }
  @Override public void close(){closed=true;worker.shutdownNow();}
}
