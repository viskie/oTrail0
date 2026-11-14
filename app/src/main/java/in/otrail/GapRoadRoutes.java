package in.otrail;

import android.app.Activity;
import android.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Display-only road estimates. Never writes synthetic fixes or changes distance totals. */
final class GapRoadRoutes implements AutoCloseable {
  private final Activity activity;private final Runnable changed;
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private final Map<String,List<RoutePlan.Point>> ready=new HashMap<>();
  private final Map<String,Long> attempts=new HashMap<>();private volatile boolean closed;
  GapRoadRoutes(Activity activity,Runnable changed){this.activity=activity;this.changed=changed;}
  List<RoutePlan.Point> geometry(List<TrailSegments.Sample> points,boolean offlineOnly){
    if(points.size()<2)return List.of();
    TrailSegments.Sample a=points.get(0),b=points.get(points.size()-1);
    RoutePlan.Point from=new RoutePlan.Point(a.lat(),a.lon()),to=new RoutePlan.Point(b.lat(),b.lon());
    if(RoutePlan.metres(from,to)<15)return List.of();
    String key=String.format(Locale.US,"%.6f_%.6f_%.6f_%.6f",a.lat(),a.lon(),b.lat(),b.lon());
    if(ready.containsKey(key))return ready.get(key);
    long now=System.currentTimeMillis();if(closed||now-attempts.getOrDefault(key,0L)<600000)return List.of();
    // Bound queued work on very long histories; later refreshes resume remaining gaps.
    long recent=attempts.values().stream().filter(t->now-t<120000).count();if(recent>=24)return List.of();
    attempts.put(key,now);String preferred=OfflineRouting.engine(activity),endpoint=NavigationManager.get(activity).server();
    worker.execute(()->{
      try{
        File directory=new File(activity.getFilesDir(),"estimated-road-gaps");directory.mkdirs();File file=new File(directory,key+".json");String json=null,engine="cached";
        if(file.isFile())json=new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8);
        else{
          List<String> engines=new ArrayList<>();if(!preferred.equals("online"))engines.add(preferred);
          File[] tiles=new File(OfflineRouting.root(activity),"segments4").listFiles((d,n)->n.endsWith(".rd5"));
          if(tiles!=null&&tiles.length>0&&!engines.contains("brouter"))engines.add("brouter");
          if(new File(OfflineRouting.root(activity),"valhalla/india.tar").isFile()&&!engines.contains("valhalla"))engines.add("valhalla");
          for(String candidate:engines)try{json=OfflineRouting.route(activity,candidate,from,to);engine=candidate;break;}catch(Exception e){android.util.Log.i("oTraiL","Estimated road gap: "+candidate+" unavailable");}
          if(json==null&&!offlineOnly){ConnectivityManager manager=(ConnectivityManager)activity.getSystemService(Activity.CONNECTIVITY_SERVICE);NetworkCapabilities net=manager.getNetworkCapabilities(manager.getActiveNetwork());if(net!=null&&net.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)){json=OnlineRouting.route(endpoint,from,to);engine="online";}}
        }
        if(json==null)return;
        RoutePlan plan=OsrmRouteParser.parse(json);
        if(b.time()<=a.time()||plan.length()/((b.time()-a.time())/1000.0)>60)throw new IOException("Road estimate exceeds plausible gap speed");
        if(RoutePlan.metres(from,plan.points.get(0))>1000||RoutePlan.metres(to,plan.points.get(plan.points.size()-1))>1000)throw new IOException("Road estimate endpoints too far from observations");
        if(!file.exists()){File temp=new File(directory,key+".part");java.nio.file.Files.write(temp.toPath(),json.getBytes(java.nio.charset.StandardCharsets.UTF_8));if(!temp.renameTo(file))throw new IOException("Cannot cache road estimate");}
        android.util.Log.i("oTraiL","Estimated road gap "+a.id()+" to "+b.id()+" via "+engine+"; not recorded GPS");
        activity.runOnUiThread(()->{if(!closed){ready.put(key,plan.points);changed.run();}});
      }catch(Exception e){android.util.Log.w("oTraiL","Estimated road gap pending: "+e.getMessage());}
    });return List.of();
  }
  public void close(){closed=true;worker.shutdownNow();}
}
