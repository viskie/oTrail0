package in.otrail;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import btools.router.*;
import com.valhalla.valhalla.Valhalla;

/** Embedded on-device engines. No HTTP client or online fallback is used here. */
public final class OfflineRouting {
  public static String engine(Context c) { return c.getSharedPreferences("navigation",0).getString("engine","online"); }
  public static boolean selected(Context c) { return !engine(c).equals("online"); }
  public static File root(Context c) { File f=new File(c.getFilesDir(),"routing");f.mkdirs();return f; }
  public static synchronized String route(Context c,String engine,RoutePlan.Point from,RoutePlan.Point to) throws Exception {
    return route(c,engine,List.of(from,to),"auto");
  }
  public static synchronized String route(Context c,String engine,List<RoutePlan.Point> points,String preference) throws Exception {
    if(points.size()<2||points.size()>10)throw new IOException("Choose 2–10 route points");
    for(RoutePlan.Point p:points)if(!DestinationSearch.india(p))throw new IOException("Offline routing is configured for India");
    RoutePlan.Point from=points.get(0),to=points.get(points.size()-1);
    if(from.lat()<6 || from.lat()>38 || from.lon()<68 || from.lon()>98 || to.lat()<6 || to.lat()>38 || to.lon()<68 || to.lon()>98)
      throw new IOException("Offline routing is configured for India");
    if(engine.equals("brouter")) {
      File rules=new File(root(c),"profiles2");rules.mkdirs();
      for(String name:new String[]{"car-vario.brf","lookups.dat"}) {
        File file=new File(rules,name);
        try(InputStream in=c.getAssets().open("brouter/"+name); FileOutputStream out=new FileOutputStream(file)) { TrailBackup.copy(in,out); }
      }
      File profile=new File(rules,"car-vario.brf");
      String original=new String(java.nio.file.Files.readAllBytes(profile.toPath()),StandardCharsets.UTF_8);
      java.nio.file.Files.write(profile.toPath(),RoutePreferences.brouterProfile(original,preference).getBytes(StandardCharsets.UTF_8));
      File segments=new File(root(c),"segments4");segments.mkdirs();
      List<OsmNodeNamed> waypoints=new ArrayList<>();
      for(RoutePlan.Point p:points) {
        OsmNodeNamed n=new OsmNodeNamed();n.name=waypoints.isEmpty()?"from":"to";
        n.ilon=(int)Math.round((p.lon()+180)*1e6);n.ilat=(int)Math.round((p.lat()+90)*1e6);waypoints.add(n);
      }
      RoutingContext rc=new RoutingContext();rc.localFunction=new File(rules,"car-vario.brf").getAbsolutePath();rc.memoryclass=128;rc.turnInstructionMode=3;
      RoutingEngine router=new RoutingEngine(null,null,segments,waypoints,rc);
      router.doRun(60_000);
      if(router.getErrorMessage()!=null) throw new IOException(router.getErrorMessage()+" · check BRouter regional tiles in settings");
      if(router.getFoundTrack()==null) throw new IOException("No BRouter route found");
      return OfflineRouteCodec.brouter(new FormatJson(rc).format(router.getFoundTrack()));
    }
    if(!engine.equals("valhalla")) throw new IOException("Choose an offline routing engine");
    File tiles=new File(root(c),"valhalla/india.tar");
    if(!tiles.isFile()) throw new IOException("Download or import an India Valhalla graph pack in settings");
    JSONObject config;
    try(InputStream in=Valhalla.class.getResourceAsStream("/com/valhalla/valhalla/default.json")) {
      if(in==null)throw new IOException("Valhalla default configuration missing");
      ByteArrayOutputStream out=new ByteArrayOutputStream();TrailBackup.copy(in,out);
      config=new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
    }
    JSONObject mj=config.getJSONObject("mjolnir");
    mj.put("tile_extract",tiles.getAbsolutePath());mj.put("tile_dir",tiles.getParent());
    mj.put("max_cache_size",134217728);mj.remove("tile_url");mj.remove("traffic_extract");
    File conf=new File(root(c),"valhalla.json");
    try(FileOutputStream out=new FileOutputStream(conf)){out.write(config.toString().getBytes(StandardCharsets.UTF_8));}
    JSONArray locations=new JSONArray();
    for(RoutePlan.Point p:points)locations.put(new JSONObject().put("lat",p.lat()).put("lon",p.lon()).put("type","break"));
    JSONObject req=new JSONObject().put("locations",locations)
      .put("costing","auto").put("units","kilometers").put("language","en-US")
      .put("costing_options",new JSONObject().put("auto",new JSONObject().put("use_highways",RoutePreferences.highwayUse(preference))));
    try(Valhalla actor=new Valhalla(conf.getAbsolutePath())) {
      return OfflineRouteCodec.valhalla(actor.routeRaw(req.toString()));
    } catch(LinkageError e) { throw new IOException("Valhalla is unavailable on this phone architecture",e); }
  }
}
