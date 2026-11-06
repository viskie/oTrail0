package in.otrail;

import android.app.*;
import android.widget.*;
import android.graphics.drawable.GradientDrawable;
import java.util.*;
import java.util.concurrent.*;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.util.GeoPoint;

/** Cache-first UI work is isolated from the GPS writer and navigation engine. */
final class RoadControls implements AutoCloseable {
  private final Activity activity;
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private TextView label;private MapView map;
  private RoadContext.Snapshot current;
  private final List<Marker> markers=new ArrayList<>();
  private long lastUpdate,lastNetwork;private double lastLat=Double.NaN,lastLon;
  private boolean loading,closed,enabled;private long tapTicket;
  RoadControls(Activity a){activity=a;}
  void attach(TextView label,MapView map){this.label=label;this.map=map;markers.clear();label.setText("Road landmarks · shown while on the move");label.setOnClickListener(v->showList());}
  void update(TrailDb.Fix fix,TrailDb.Fix previous,boolean moving,boolean offline,boolean history) {
    if(closed||label==null)return;
    enabled=!history&&moving;
    if(history||!moving){label.setText(history?"Tap a road for its cached name":"Road landmarks · shown while on the move");hide();return;}
    render();long now=System.currentTimeMillis();
    if(loading||now-lastUpdate<15000 && Double.isFinite(lastLat)&&StopDetector.distance(lastLat,lastLon,fix.lat(),fix.lon())<100)return;
    loading=true;lastUpdate=now;lastLat=fix.lat();lastLon=fix.lon();
    double bearing=Double.NaN;
    if(previous!=null&&StopDetector.distance(previous.lat(),previous.lon(),fix.lat(),fix.lon())>5){bearing=Math.toDegrees(Math.atan2((fix.lon()-previous.lon())*Math.cos(Math.toRadians(fix.lat())),fix.lat()-previous.lat()));}
    final double heading=bearing;
    worker.execute(()->{
      try(RoadContext cache=new RoadContext(activity)) {
        RoadContext.Snapshot saved=cache.cached(fix.lat(),fix.lon(),heading);post(saved);
        if(!offline&&(saved.fetched()==0||now-saved.fetched()>7L*86400000)&&now-lastNetwork>120000) {
          lastNetwork=now;
          try{post(cache.fetch(fix.lat(),fix.lon(),heading));}
          catch(Exception e){activity.runOnUiThread(()->{if(label!=null&&!closed)label.append(" · update unavailable; cache kept");});}
        }
      }catch(Exception e){activity.runOnUiThread(()->{if(label!=null&&!closed)label.setText("Road cache unavailable · tap road to retry");});}
      finally{activity.runOnUiThread(()->loading=false);}
    });
  }
  private void post(RoadContext.Snapshot snapshot){activity.runOnUiThread(()->{if(closed||activity.isDestroyed())return;current=snapshot;render();});}
  private void render(){
    if(label==null)return;
    if(!enabled){hide();return;}
    if(current==null){label.setText("Looking up road · cache first…");return;}
    label.setText(current.road().isBlank()?current.note():current.road()+" · "+current.landmarks().size()+" roadside landmarks ahead · "+(current.cached()?"cached":"updated"));
    hide();if(map==null)return;
    for(RoadContext.Landmark p:current.landmarks().subList(0,Math.min(40,current.landmarks().size()))) {
      Marker m=new Marker(map);m.setId("roadpoi:"+p.key());m.setPosition(new GeoPoint(p.lat(),p.lon()));m.setTitle(p.name());m.setSnippet(p.kind()+" · "+Math.round(p.meters())+" m · © OpenStreetMap contributors");
      GradientDrawable icon=new GradientDrawable();icon.setShape(GradientDrawable.OVAL);icon.setColor(0xffffa000);icon.setStroke(2,0xff603800);icon.setSize(18,18);m.setIcon(icon);m.setAnchor(.5f,.5f);
      m.setOnMarkerClickListener((marker,view)->{new AlertDialog.Builder(activity).setTitle(p.name()).setMessage(p.kind()+"\n"+Math.round(p.meters())+" m away\nMapped alongside "+current.road()+"\nAccess and opening hours are not verified.").setPositiveButton("Close",null).show();return true;});map.getOverlays().add(m);markers.add(m);
    }
    map.invalidate();
  }
  private void hide(){if(map!=null)map.getOverlays().removeAll(markers);markers.clear();}
  private void showList(){if(current==null||current.road().isBlank()){Toast.makeText(activity,"Tap a road on the map to identify it",Toast.LENGTH_LONG).show();return;}String[] names=current.landmarks().stream().map(p->p.name()+" · "+p.kind()+" · "+Math.round(p.meters())+" m").toArray(String[]::new);new AlertDialog.Builder(activity).setTitle(current.road()).setItems(names,(d,index)->{RoadContext.Landmark p=current.landmarks().get(index);if(map!=null){map.getController().setCenter(new GeoPoint(p.lat(),p.lon()));map.getController().setZoom(17.0);}}).setMessage(names.length==0?"No mapped roadside landmarks cached here. Coverage depends on OpenStreetMap.":null).setPositiveButton("Close",null).show();}
  void tapped(double lat,double lon,boolean offline){
    long ticket=++tapTicket;TextView text=new TextView(activity);text.setPadding(24,16,24,16);text.setText("Checking cached road names…");
    AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Road at this point").setView(text).setPositiveButton("Close",null).show();
    worker.execute(()->{
      try(RoadContext cache=new RoadContext(activity)) {
        RoadContext.Snapshot saved=cache.cached(lat,lon,Double.NaN);
        activity.runOnUiThread(()->{if(dialog.isShowing()&&ticket==tapTicket)text.setText(describe(saved,offline));});
        long now=System.currentTimeMillis();
        if(!offline&&now-lastNetwork>5000){lastNetwork=now;RoadContext.Snapshot fresh=cache.fetch(lat,lon,Double.NaN);activity.runOnUiThread(()->{if(dialog.isShowing()&&ticket==tapTicket)text.setText(describe(fresh,false));});}
      }catch(Exception e){activity.runOnUiThread(()->{if(dialog.isShowing())text.append("\nOnline update unavailable; any cached name above is retained.");});}
    });
  }
  private static String describe(RoadContext.Snapshot s,boolean offline){return (s.road().isBlank()?s.note():s.road())+"\n"+(s.fetched()>0?(s.cached()?"Cached · ":"Updated · ")+new java.util.Date(s.fetched()):offline?"Offline · connect to fetch this area":"Checking online source…")+"\n© OpenStreetMap contributors";}
  public void close(){closed=true;worker.shutdownNow();}
}
