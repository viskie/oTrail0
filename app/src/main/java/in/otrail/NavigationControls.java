package in.otrail;

import android.app.*;
import android.content.Intent;
import android.location.Location;
import android.net.Uri;
import android.view.View;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.osmdroid.util.*;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.*;

/**
 * Text-only guidance controls; route overlays have their own identities, separate from trail
 * history.
 */
final class NavigationControls implements AutoCloseable {
  private final Activity activity;
  private final NavigationManager manager;
  private final Supplier<Location> origin;
  private final Runnable locate, changed;
  private final ExecutorService searches = Executors.newSingleThreadExecutor();
  private LinearLayout panel;
  private TextView title, instruction, summary;
  private Button start;
  private MapView lastMap;
  private long drawnRevision = -1;
  private final List<Overlay> overlays = new ArrayList<>();
  private DestinationSearch.Result pending;
  private boolean searching;
  java.util.function.Consumer<GeoPoint> roadTap;

  NavigationControls(Activity a, Supplier<Location> origin, Runnable locate, Runnable changed) {
    activity = a;
    manager = NavigationManager.get(a);
    this.origin = origin;
    this.locate = locate;
    this.changed = changed;
  }

  LinearLayout panel() {
    panel = new LinearLayout(activity);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setBackgroundColor(AppStyle.SURFACE);
    title = text("", UiConfig.NAV_TITLE_SIZE_SP);
    instruction = text("", UiConfig.NAV_INSTRUCTION_SIZE_SP);
    summary = text("", UiConfig.NAV_SUMMARY_SIZE_SP);
    title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    instruction.setMaxLines(2);
    panel.addView(title);
    panel.addView(instruction);
    panel.addView(summary);
    LinearLayout row = new LinearLayout(activity);
    start =
        button(
            UiConfig.text("start"),
            () -> {
              NavigationManager.State s = manager.state();
              if ("ACTIVE".equals(s.mode())) {
                locate.run();
              } else {
                if (origin.get() == null) {
                  toast(UiConfig.text("a_fresh_gps_fix_is_required_to_start"));
                  locate.run();
                  return;
                }
                manager.start();
                Location current = origin.get();
                if (current != null) manager.update(current);
                locate.run();
              }
              changed.run();
            });
    row.addView(start, new LinearLayout.LayoutParams(0, dp(44), 1));
    row.addView(button(UiConfig.text("turns"), this::turns), new LinearLayout.LayoutParams(0, dp(44), 1));
    row.addView(
        button(
            UiConfig.text("clear"),
            () -> {
              pending = null;
              manager.clear();
              changed.run();
            }),
        new LinearLayout.LayoutParams(0, dp(44), 1));
    panel.addView(row);
    TextView credits = text(UiConfig.text("osrm_fossgis_openstreetmap_contributors"), 10);
    credits.setOnClickListener(
        v ->
            activity.startActivity(
                new Intent(
                    Intent.ACTION_VIEW, Uri.parse("https://routing.openstreetmap.de/about.html"))));
    credits.setVisibility(View.GONE);
    panel.addView(credits);
    panel.setVisibility(View.GONE);
    return panel;
  }

  private int dp(int value) {
    return (int) (value * activity.getResources().getDisplayMetrics().density + .5f);
  }

  private TextView text(String value, int size) {
    TextView v = new TextView(activity);
    v.setText(value);
    v.setTextSize(UiConfig.textSize(size));
    AppStyle.text(v);
    v.setTextColor(AppStyle.textColor(size, size>=14));
    if(size>=14)v.setTypeface(android.graphics.Typeface.create(UiConfig.BODY_FONT,android.graphics.Typeface.BOLD));
    v.setPadding(dp(8), dp(2), dp(8), dp(2));
    return v;
  }

  private Button button(String label, Runnable click) {
    Button b = new Button(activity);
    b.setText(label);
    b.setAllCaps(false);
    AppStyle.button(b);
    b.setOnClickListener(v -> click.run());
    return b;
  }

  void search() {
    if (searching) {
      toast(UiConfig.text("search_is_already_running"));
      return;
    }
    EditText query = new EditText(activity);
    query.setHint(UiConfig.text("place_in_india_or_latitude_longitude"));
    query.setSingleLine(true);
    new AlertDialog.Builder(activity)
        .setTitle(UiConfig.text("where_are_you_going"))
        .setView(query)
        .setPositiveButton(
            UiConfig.text("search"),
            (d, w) -> {
              String q = query.getText().toString().trim();
              if (manager.offline() && !q.matches("[+\\-0-9.]+\\s*,\\s*[+\\-0-9.]+")) {
                toast(UiConfig.text("offline_enter_latitude_longitude_or_hold_a_point_on_the_map"));
                return;
              }
              if (q.isEmpty()) return;
              searching = true;
              toast(UiConfig.text("searching_places"));
              searches.execute(
                  () -> {
                    try {
                      List<DestinationSearch.Result> results =
                          DestinationSearch.search(activity.getApplicationContext(), q);
                      activity.runOnUiThread(
                          () -> {
                            if (activity.isFinishing() || activity.isDestroyed()) return;
                            if (results.isEmpty()) {
                              toast(
                                  UiConfig.text("no_matching_place_in_india_try_a_nearby_town_or_coordinates"));
                              return;
                            }
                            String[] names =
                                results.stream()
                                    .map(DestinationSearch.Result::name)
                                    .toArray(String[]::new);
                            new AlertDialog.Builder(activity)
                                .setTitle(UiConfig.text("choose_destination"))
                                .setItems(names, (dialog, index) -> plan(results.get(index)))
                                .setNegativeButton(UiConfig.text("cancel"), null)
                                .show();
                          });
                    } catch (Exception e) {
                      activity.runOnUiThread(() -> toast(UiConfig.text("search_unavailable") + e.getMessage()));
                    } finally {
                      searching = false;
                    }
                  });
            })
        .setNegativeButton(UiConfig.text("cancel"), null)
        .show();
  }

  void plan(DestinationSearch.Result destination) {
    if (!DestinationSearch.india(destination.point())) {
      toast(UiConfig.text("choose_a_destination_in_india"));
      return;
    }
    if (manager.offline() && !OfflineRouting.selected(activity)) {
      toast(UiConfig.text("choose_brouter_or_valhalla_in_maps_downloads_to_calculate_offlin"));
      return;
    }
    pending = destination;
    Location current = origin.get();
    if (current == null) {
      toast(UiConfig.text("waiting_for_an_accurate_gps_fix"));
      locate.run();
    } else begin(current);
    changed.run();
  }

  private void begin(Location current) {
    DestinationSearch.Result target = pending;
    pending = null;
    manager.preview(
        new RoutePlan.Point(current.getLatitude(), current.getLongitude()),
        target.point(),
        target.name());
  }

  void refresh(MapView map) {
    if (panel == null) return;
    if (pending != null) {
      Location current = origin.get();
      if (current != null) begin(current);
    }
    NavigationManager.State s = manager.state();
    boolean has = s.route() != null;
    panel.setVisibility(has || pending != null || !s.note().isEmpty() ? View.VISIBLE : View.GONE);
    if (panel.getVisibility() != View.VISIBLE) {
      draw(map, s);
      return;
    }
    title.setText(pending != null ? pending.name() : s.name());
    NavigationProgress.Guidance g = s.guidance();
    boolean active = "ACTIVE".equals(s.mode());
    boolean stale =
        active && (s.fixTime() == 0 || System.currentTimeMillis() - s.fixTime() > 30_000);
    instruction.setText(
        pending != null
            ? UiConfig.text("waiting_for_a_fresh_gps_fix")
            : stale
                ? UiConfig.text("gps_stale_waiting_for_location")
                : g != null && g.offRoute()
                    ? s.note()
                    : g != null && active && !g.arrived()
                        ? distance(g.untilTurn()) + " · " + g.instruction()
                        : s.note());
    double remaining = g != null ? g.remaining() : has ? s.route().distance : 0,
        seconds = g != null ? g.seconds() : has ? s.route().duration : 0;
    summary.setText(
        has
            ? distance(remaining)
                + UiConfig.text("remaining")
                + Math.max(1, Math.round(seconds / 60))
                + UiConfig.text("min_no_live_traffic")
                + (g != null && g.offRoute() ? " · " + s.note() : "")
            : s.note());
    start.setText(active ? UiConfig.text("recenter") : UiConfig.text("start"));
    start.setEnabled(has && !"ARRIVED".equals(s.mode()) && !"PLANNING".equals(s.mode()));
    draw(map, s);
  }

  private void draw(MapView map, NavigationManager.State s) {
    if (map == null) return;
    if (lastMap != map) {
      lastMap = map;
      drawnRevision = -1;
      overlays.clear();
    }
    if (drawnRevision == s.revision() && overlays.stream().allMatch(map.getOverlays()::contains))
      return;
    map.getOverlays().removeAll(overlays);
    overlays.clear();
    drawnRevision = s.revision();
    if (s.route() != null) {
      ArrayList<GeoPoint> points = new ArrayList<>();
      for (RoutePlan.Point p : s.route().points) points.add(new GeoPoint(p.lat(), p.lon()));
      Polyline route = new Polyline(map);
      route.setId("navigation:route");
      route.setPoints(points);
      route.getOutlinePaint().setColor(UiConfig.NAV_ROUTE_COLOR);
      route.getOutlinePaint().setStrokeWidth(dp(5));
      route.setOnClickListener(
          (line, view, event) -> {
            if (roadTap != null) roadTap.accept(event); else turns();
            return true;
          });
      int index = map.getOverlays().size();
      for (int i = 0; i < map.getOverlays().size(); i++)
        if (map.getOverlays().get(i) instanceof Marker) {
          index = i;
          break;
        }
      map.getOverlays().add(index, route);
      overlays.add(route);
      Marker end = new Marker(map);
      end.setId("navigation:destination");
      end.setPosition(new GeoPoint(s.target().lat(), s.target().lon()));
      end.setTitle(s.name());
      end.setSnippet(UiConfig.text("navigation_destination"));
      end.setOnMarkerClickListener(
          (marker, view) -> {
            MarkerPopups.openOnly(view, marker);
            return true;
          });
      map.getOverlays().add(end);
      overlays.add(end);
    }
    map.invalidate();
  }

  boolean fit(MapView map) {
    NavigationManager.State s = manager.state();
    if (map == null || s.route() == null || map.getWidth() == 0 || map.getHeight() == 0)
      return false;
    List<GeoPoint> points = new ArrayList<>();
    for (RoutePlan.Point p : s.route().points) points.add(new GeoPoint(p.lat(), p.lon()));
    BoundingBox bounds = BoundingBox.fromGeoPoints(points);
    if (bounds.getLatitudeSpan() < .0001 && bounds.getLongitudeSpan() < .0001) {
      map.getController().setCenter(points.get(0));
      map.getController().setZoom(16.0);
    } else map.zoomToBoundingBox(bounds, false, dp(25));
    return true;
  }

  private void turns() {
    NavigationManager.State s = manager.state();
    if (s.route() == null) {
      toast(UiConfig.text("choose_a_destination_first"));
      return;
    }
    StringBuilder out = new StringBuilder();
    for (RoutePlan.Step step : s.route().steps)
      out.append(distance(step.along()))
          .append(UiConfig.text("from_start"))
          .append(step.instruction())
          .append("\n\n");
    new AlertDialog.Builder(activity)
        .setTitle(s.name())
        .setMessage(out.toString())
        .setPositiveButton(UiConfig.text("close"), null)
        .setNeutralButton(
            UiConfig.text("map_corrections"),
            (d, w) ->
                activity.startActivity(
                    new Intent(
                        Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/fixthemap"))))
        .show();
  }

  static String distance(double m) {
    return m < 1000
        ? Math.round(m) + UiConfig.text("m_2")
        : String.format(Locale.getDefault(), UiConfig.text("1f_km"), m / 1000);
  }

  private void toast(String message) {
    if (!activity.isDestroyed()) Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
  }

  @Override
  public void close() {
    searches.shutdownNow();
  }
}
