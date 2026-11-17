package in.otrail;

import android.content.*;
import android.location.Location;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.*;

/**
 * Process-wide navigation survives map changes and restores its cached route after app/reboot
 * restart.
 */
public final class NavigationManager {
  public record State(
      RoutePlan route,
      RoutePlan.Point target,
      String name,
      String mode,
      String note,
      NavigationProgress.Guidance guidance,
      long revision,
      long fixTime) {}

  private static NavigationManager instance;

  public static synchronized NavigationManager get(Context c) {
    if (instance == null) instance = new NavigationManager(c.getApplicationContext());
    return instance;
  }

  private final Context context;
  private final ExecutorService requests = Executors.newSingleThreadExecutor(),
      progress = Executors.newSingleThreadExecutor(),
      cacheWriter = Executors.newSingleThreadExecutor();
  private State state = new State(null, null, "", "NONE", "", null, 0, 0);
  private NavigationProgress tracker;
  private State lastGoodState;
  private long ticket, lastRequest;
  private boolean fetching;
  private String routeJson;
  private java.util.List<RoutePlan.Point> remainingVia=java.util.List.of();
  private String routePreference="auto";

  private NavigationManager(Context c) {
    context = c;
    requests.execute(this::restore);
  }

  public synchronized State state() {
    return state;
  }

  public boolean offline() {
    return context.getSharedPreferences("MainActivity", 0).getBoolean("offline", false);
  }

  public String server() {
    return context
        .getSharedPreferences("navigation", 0)
        .getString("server", OnlineRouting.DEFAULT_SERVER);
  }

  public void server(String s) throws Exception {
    context
        .getSharedPreferences("navigation", 0)
        .edit()
        .putString("server", OnlineRouting.server(s))
        .apply();
  }

  public void preview(RoutePlan.Point origin, RoutePlan.Point destination, String name) {
    preview(origin,destination,name,java.util.List.of(),"auto");
  }

  public void preview(RoutePlan.Point origin,RoutePlan.Point destination,String name,java.util.List<RoutePlan.Point> via,String preference) {
    request(origin,destination,name,false,java.util.List.copyOf(via),RoutePreferences.valid(preference));
  }
  private synchronized void request(
      RoutePlan.Point origin, RoutePlan.Point target, String name, boolean reroute,java.util.List<RoutePlan.Point> via,String preference) {
    if (offline() && !OfflineRouting.selected(context)) {
      state =
          new State(
              state.route,
              state.target,
              state.name,
              state.mode,
              "Offline only · route calculation needs internet",
              state.guidance,
              state.revision,
              state.fixTime);
      return;
    }
    long now = System.currentTimeMillis();
    if (reroute && (fetching || now-lastRequest<60_000)) return;
    lastRequest = now;
    fetching = true;
    long request = ++ticket;
    if(state.route()!=null)lastGoodState=state;
    State before=state.route()==null&&lastGoodState!=null?lastGoodState:state;
    state =
        new State(
            reroute ? state.route : null,
            reroute ? state.target : target,
            reroute ? state.name : name,
            reroute ? state.mode : "PLANNING",
            reroute ? "Recalculating route…" : "Calculating driving route…",
            reroute ? state.guidance : null,
            state.revision + (reroute ? 0 : 1),
            state.fixTime);
    requests.execute(
        () -> {
          try {
            String engine = OfflineRouting.engine(context);
            java.util.List<RoutePlan.Point> points=new java.util.ArrayList<>();points.add(origin);points.addAll(via);points.add(target);
            String json = engine.equals("online") ? OnlineRouting.route(server(), points,preference)
                : OfflineRouting.route(context, engine, points,preference);
            RoutePlan plan = OsrmRouteParser.parse(json);
            synchronized (this) {
              if (request != ticket) return;
              if(!reroute&&before.route()!=null)rememberHistory(before);
              routeJson = json;
              remainingVia=java.util.List.copyOf(via);routePreference=preference;
              tracker = new NavigationProgress(plan);
              state =
                  new State(
                      plan,
                      target,
                      name,
                      reroute ? "ACTIVE" : "PREVIEW",
                      reroute ? "Route updated" : "Route preview · tap Start",
                      null,
                      state.revision + 1,
                      0);
              persist();
            }
          } catch (Exception e) {
            synchronized (this) {
              if (request == ticket) {
                State keep = reroute ? state : before;
                state =
                    new State(
                        keep.route,
                        keep.target,
                        keep.name,
                        keep.mode,
                        "Route unavailable: " + e.getMessage(),
                        keep.guidance,
                        state.revision + 1,
                        keep.fixTime);
              }
            }
          } finally {
            synchronized (this) {
              if (request == ticket) fetching = false;
            }
          }
        });
  }

  public synchronized void start() {
    if (state.route == null) return;
    tracker = new NavigationProgress(state.route);
    state =
        new State(
            state.route,
            state.target,
            state.name,
            "ACTIVE",
            "Waiting for a fresh GPS fix",
            null,
            state.revision,
            System.currentTimeMillis());
    rememberHistory(state);
    persist();
  }

  private void rememberHistory(State snapshot){cacheWriter.execute(()->{try(NavigationHistory history=new NavigationHistory(context)){history.remember(snapshot);}catch(Exception error){android.util.Log.w("oTraiL","Navigation history write failed; active route retained",error);}});}

  public synchronized void clear() {
    ticket++;
    fetching = false;
    routeJson = null;remainingVia=java.util.List.of();routePreference="auto";
    lastGoodState=null;
    tracker = null;
    state = new State(null, null, "", "NONE", "", null, state.revision + 1, 0);
    cacheWriter.execute(
        () -> new AtomicFile(new File(context.getFilesDir(), "navigation-route.json")).delete());
  }

  public void update(Location location) {
    if (!"ACTIVE".equals(state().mode)) return;
    Location fix = new Location(location);
    progress.execute(() -> accept(fix));
  }

  private synchronized void accept(Location fix) {
    if (tracker == null
        || !"ACTIVE".equals(state.mode)
        || !fix.hasAccuracy()
        || System.currentTimeMillis() - fix.getTime() > 30_000
        || System.currentTimeMillis() - fix.getTime() < -10_000) return;
    RoutePlan.Point point = new RoutePlan.Point(fix.getLatitude(), fix.getLongitude());
    NavigationProgress.Guidance g = tracker.update(point, fix.getAccuracy(), fix.getTime());
    if (!g.accepted()) return;
    if(!remainingVia.isEmpty()&&RoutePlan.metres(point,remainingVia.get(0))<1000) {
      remainingVia=java.util.List.copyOf(remainingVia.subList(1,remainingVia.size()));persist();
    }
    boolean arrived =
        g.arrived() && state.target != null && RoutePlan.metres(point, state.target) <= 70;
    String note =
        arrived
            ? "You have arrived"
            : g.arrived()
                ? "At route end · destination "
                    + Math.round(RoutePlan.metres(point, state.target))
                    + " m away"
                : g.offRoute()
                    ? (offline() && !OfflineRouting.selected(context)
                        ? "Off route · select an offline engine in settings"
                        : "Off route · checking a new route")
                    : "Following route";
    state =
        new State(
            state.route,
            state.target,
            state.name,
            arrived ? "ARRIVED" : "ACTIVE",
            note,
            g,
            state.revision,
            fix.getTime());
    if (arrived) {
      ticket++;
      fetching = false;
      persist();
    } else if (g.offRoute() && (!offline() || OfflineRouting.selected(context))) request(point, state.target, state.name, true,remainingVia,routePreference);
  }

  private void persist() {
    if (routeJson == null || state.target == null) return;
    String response = routeJson;
    State snapshot = state;
    java.util.List<RoutePlan.Point> viaSnapshot=java.util.List.copyOf(remainingVia);String preferenceSnapshot=routePreference;
    cacheWriter.execute(
        () -> {
          AtomicFile file =
              new AtomicFile(new File(context.getFilesDir(), "navigation-route.json"));
          FileOutputStream out = null;
          try {
            JSONArray vias=new JSONArray();for(RoutePlan.Point p:viaSnapshot)vias.put(new JSONObject().put("lat",p.lat()).put("lon",p.lon()));
            JSONObject saved =
                new JSONObject()
                    .put("version", 1)
                    .put("response", new JSONObject(response))
                    .put("name", snapshot.name)
                    .put("latitude", snapshot.target.lat())
                    .put("longitude", snapshot.target.lon())
                    .put("mode", snapshot.mode).put("via",vias).put("roadPreference",preferenceSnapshot);
            out = file.startWrite();
            out.write(saved.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
          } catch (Exception e) {
            if (out != null) file.failWrite(out);
          }
        });
  }

  private void restore() {
    try {
      AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "navigation-route.json"));
      if (file.getBaseFile().length() > 12_500_000) return;
      JSONObject saved = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
      String cached = saved.getJSONObject("response").toString();
      RoutePlan route = OsrmRouteParser.parse(cached);
      String mode = saved.optString("mode", "PREVIEW");
      if (!mode.equals("ACTIVE") && !mode.equals("ARRIVED")) mode = "PREVIEW";
      synchronized (this) {
        if (ticket != 0) return;
        routeJson = cached;
        java.util.List<RoutePlan.Point> vias=new java.util.ArrayList<>();JSONArray rawVia=saved.optJSONArray("via");
        if(rawVia!=null)for(int i=0;i<Math.min(8,rawVia.length());i++){JSONObject p=rawVia.getJSONObject(i);vias.add(new RoutePlan.Point(p.getDouble("lat"),p.getDouble("lon")));}
        remainingVia=java.util.List.copyOf(vias);routePreference=RoutePreferences.valid(saved.optString("roadPreference","auto"));
        state =
            new State(
                route,
                new RoutePlan.Point(saved.getDouble("latitude"), saved.getDouble("longitude")),
                saved.optString("name", "Destination"),
                mode,
                "Cached route · waiting for fresh GPS",
                null,
                1,
                0);
        tracker = new NavigationProgress(route);
      }
    } catch (Exception ignored) {
      /* A missing/broken route cache must never affect the journal. */
    }
  }
}
