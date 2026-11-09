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
  private final File directory;private volatile boolean closed;private boolean addressBusy,worshipBusy;private long addressAttempt,worshipAttempt;private double addressLat=Double.NaN,addressLon;
  private String address="Locality / road lookup pending",road="",worshipProblem="";private JSONObject worship;private long worshipFetched;private boolean policeSearched;private double worshipLat,worshipLon;
  LiveLocationContext(Activity a,Runnable changed){activity=a;this.changed=changed;directory=new File(a.getFilesDir(),"live-context");directory.mkdirs();try{JSONObject saved=read(new File(directory,"worship.json"));worship=saved.getJSONObject("payload");policeSearched=saved.optInt("schema",0)>=2;worshipFetched=policeSearched?saved.getLong("fetched"):0;worshipLat=saved.getDouble("lat");worshipLon=saved.getDouble("lon");}catch(Exception ignored){}}
  private boolean online(){ConnectivityManager manager=(ConnectivityManager)activity.getSystemService(Context.CONNECTIVITY_SERVICE);NetworkCapabilities capabilities=manager.getNetworkCapabilities(manager.getActiveNetwork());return capabilities!=null&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);}
  String address(){return address;}
  String worship(double lat,double lon){try{if(worship!=null&&PhotoIndex.distance(lat,lon,worshipLat,worshipLon)<=5000)return NearbySites.summary(worship,lat,lon,policeSearched)+"\nStraight-line · "+(online()?"cached/online":"offline cache")+" · OSM";}catch(Exception ignored){}return online()?worshipProblem.isBlank()?"Worship sites: checking all directions (25 km)":"Worship lookup unavailable · retrying; cached results retained":"Worship sites: offline · no nearby cached results";}
  List<NearbySites.Site> sites(double lat,double lon){try{return worship!=null&&PhotoIndex.distance(lat,lon,worshipLat,worshipLon)<=5000?NearbySites.parse(worship,lat,lon):List.of();}catch(Exception ignored){return List.of();}}
  void update(double lat,double lon,String cachedRoad){
    if(closed)return;road=cachedRoad;long now=System.currentTimeMillis();String key=Math.round(lat*400)+"_"+Math.round(lon*400);File file=new File(directory,"address-"+key+".json");
    if(!Double.isFinite(addressLat)||PhotoIndex.distance(lat,lon,addressLat,addressLon)>100){try{JSONObject saved=read(file);address=saved.getString("address");addressLat=lat;addressLon=lon;changed.run();}catch(Exception ignored){}}
    if(online()&&!addressBusy&&now-addressAttempt>=30000&&(!Double.isFinite(addressLat)||PhotoIndex.distance(lat,lon,addressLat,addressLon)>100||now-addressAttempt>=300000)){
      addressBusy=true;addressAttempt=now;worker.execute(()->{try{Address found=StopGeocoder.lookupAddress(activity,lat,lon);if(found!=null){LinkedHashSet<String> names=new LinkedHashSet<>();for(String value:new String[]{found.getSubLocality(),found.getLocality()})if(value!=null&&!value.isBlank())names.add(value);if(names.isEmpty()&&found.getSubAdminArea()!=null)names.add(found.getSubAdminArea());String street=found.getThoroughfare();String result=String.join(" · ",names);if(street!=null&&!street.isBlank())result+=(result.isBlank()?"":"\n")+street;else if(!cachedRoad.isBlank())result+=(result.isBlank()?"":"\n")+cachedRoad;if(result.isBlank())result="Locality / street unavailable";write(file,new JSONObject().put("address",result).put("fetched",System.currentTimeMillis()));String value=result;post(()->{address=value;addressLat=lat;addressLon=lon;});}}catch(Exception ignored){}finally{post(()->addressBusy=false);}});
    }
    boolean covered=worship!=null&&PhotoIndex.distance(lat,lon,worshipLat,worshipLon)<=5000;
    if(online()&&!worshipBusy&&now-worshipAttempt>=120000&&(!covered||now-worshipFetched>=300000)){
      worshipBusy=true;worshipAttempt=now;worker.execute(()->{try{JSONObject payload=fetch(lat,lon);long time=System.currentTimeMillis();write(new File(directory,"worship.json"),new JSONObject().put("schema",2).put("payload",payload).put("fetched",time).put("lat",lat).put("lon",lon));post(()->{worship=payload;worshipFetched=time;policeSearched=true;worshipLat=lat;worshipLon=lon;worshipProblem="";});}catch(Exception error){android.util.Log.w("oTraiL","Worship lookup unavailable: "+error.getMessage());post(()->worshipProblem=error.toString());}finally{post(()->worshipBusy=false);}});
    }
  }
  private void post(Runnable action){activity.runOnUiThread(()->{if(!closed&&!activity.isDestroyed()){action.run();changed.run();}});}
  private static JSONObject read(File file)throws Exception{try(InputStream in=new AtomicFile(file).openRead()){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>6000000)throw new IOException("Cache too large");out.write(b,0,n);}return new JSONObject(out.toString("UTF-8"));}}
  private static void write(File file,JSONObject value)throws Exception{AtomicFile atomic=new AtomicFile(file);FileOutputStream out=atomic.startWrite();try{out.write(value.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(out);}catch(Exception e){atomic.failWrite(out);throw e;}}
  private static JSONObject fetch(double lat,double lon)throws Exception{
    Exception last=null;for(String endpoint:UiConfig.LIVE_WORSHIP_SERVERS)try{return fetchAt(endpoint,lat,lon);}catch(Exception error){last=error;}throw last==null?new IOException("No worship server configured"):last;
  }
  private static JSONObject fetchAt(String endpoint,double lat,double lon)throws Exception{
    String query=String.format(Locale.US,"[out:json][timeout:15];(nwr(around:30000,%.6f,%.6f)[amenity=place_of_worship][religion~\"^(hindu|muslim|christian|sikh|buddhist)$\"];nwr(around:30000,%.6f,%.6f)[amenity=police];);out center;",lat,lon,lat,lon);
    HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(12000);c.setReadTimeout(22000);c.setRequestProperty("User-Agent","oTraiL personal India navigator");c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
    try{try(OutputStream out=c.getOutputStream()){out.write(("data="+URLEncoder.encode(query,"UTF-8")).getBytes(StandardCharsets.UTF_8));}if(c.getResponseCode()!=200)throw new IOException("Worship lookup unavailable");ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>6000000)throw new IOException("Worship result too large");bytes.write(b,0,n);}}JSONObject result=new JSONObject(bytes.toString("UTF-8"));if(result.has("remark"))throw new IOException("Incomplete worship result");return result;}finally{c.disconnect();}
  }
  @Override public void close(){closed=true;worker.shutdownNow();}
}
