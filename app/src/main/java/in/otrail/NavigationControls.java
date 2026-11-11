package in.otrail;

import android.app.*;
import android.content.Intent;
import android.location.Location;
import android.net.Uri;
import android.view.View;
import android.widget.*;
import android.content.Context;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.osmdroid.util.*;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.*;
import in.otrail.DestinationSearch.Result;

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
  private DestinationSearch.Result pending,autoStartTarget;
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
    panel.setBackground(NeonBorderDrawable.panel(panel,NeonBorderDrawable.Palette.BRIGHT_YELLOW,UiConfig.NAV_BOX_CORNER_DP));
    panel.setPadding(dp(8), dp(8), dp(8), dp(8));
    title = text("", UiConfig.NAV_TITLE_SIZE_SP);
    instruction = text("", UiConfig.NAV_INSTRUCTION_SIZE_SP);
    summary = text("", UiConfig.NAV_SUMMARY_SIZE_SP);
    title.setGravity(android.view.Gravity.CENTER);
    title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
    title.setTextColor(UiConfig.ORANGE);
    title.getPaint().setFakeBoldText(UiConfig.NAV_TITLE_BOLD);
    title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    instruction.setMaxLines(2);
    instruction.setPadding(instruction.getPaddingLeft(),instruction.getPaddingTop(),instruction.getPaddingRight(),0);
    summary.setPadding(summary.getPaddingLeft(),0,summary.getPaddingRight(),summary.getPaddingBottom());
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
              pending = null;autoStartTarget=null;
              manager.clear();
              changed.run();
            }),
        new LinearLayout.LayoutParams(0, dp(44), 1));
    AppStyle.spaceButtons(row);
    panel.addView(row);
    // TextView credits = text(UiConfig.text("osrm_fossgis_openstreetmap_contributors"), 10);
    // credits.setOnClickListener(
    //     v ->
    //         activity.startActivity(
    //             new Intent(
    //                 Intent.ACTION_VIEW, Uri.parse("https://routing.openstreetmap.de/about.html"))));
    // credits.setVisibility(View.GONE);
    // panel.addView(credits);
    // panel.setVisibility(View.GONE);
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
    if(size>=14)v.setTypeface(android.graphics.Typeface.create(UiConfig.HEADING_FONT,UiConfig.HEADING_FONT_STYLE));
    v.setPadding(dp(8), dp(2), dp(8), dp(2));
    v.getPaint().setShader(null);
    v.setTextColor(UiConfig.NAV_TEXT_COLOR);
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

  // void search() {
  //   if (searching) {
  //     toast(UiConfig.text("search_is_already_running"));
  //     return;
  //   }
  //   EditText query = new EditText(activity);
  //   query.setHint(UiConfig.text("place_in_india_or_latitude_longitude"));
  //   query.setSingleLine(true);
  //   new AppDialogs(activity)
  //       .setTitle(UiConfig.text("where_are_you_going"))
  //       .setView(query)
  //       .setPositiveButton(
  //           UiConfig.text("search"),
  //           (d, w) -> {
  //             String q = query.getText().toString().trim();
  //             if (manager.offline() && !q.matches("[+\\-0-9.]+\\s*,\\s*[+\\-0-9.]+")) {
  //               toast(UiConfig.text("offline_enter_latitude_longitude_or_hold_a_point_on_the_map"));
  //               return;
  //             }
  //             if (q.isEmpty()) return;
  //             searching = true;
  //             toast(UiConfig.text("searching_places"));
  //             searches.execute(
  //                 () -> {
  //                   try {
  //                     List<DestinationSearch.Result> results =
  //                         DestinationSearch.search(activity.getApplicationContext(), q);
  //                     activity.runOnUiThread(
  //                         () -> {
  //                           if (activity.isFinishing() || activity.isDestroyed()) return;
  //                           if (results.isEmpty()) {
  //                             toast(
  //                                 UiConfig.text("no_matching_place_in_india_try_a_nearby_town_or_coordinates"));
  //                             return;
  //                           }
  //                           String[] names =
  //                               results.stream()
  //                                   .map(DestinationSearch.Result::name)
  //                                   .toArray(String[]::new);
  //                           new AppDialogs(activity)
  //                               .setTitle(UiConfig.text("choose_destination"))
  //                               .setItems(names, (dialog, index) -> plan(results.get(index)))
  //                               .setNegativeButton(UiConfig.text("cancel"), null)
  //                               .show();
  //                         });
  //                   } catch (Exception e) {
  //                     activity.runOnUiThread(() -> toast(UiConfig.text("search_unavailable") + e.getMessage()));
  //                   } finally {
  //                     searching = false;
  //                   }
  //                 });
  //           })
  //       .setNegativeButton(UiConfig.text("cancel"), null)
  //       .show();
  // }

void search() {
    if (searching) {
        toast(UiConfig.text("search_is_already_running"));
        return;
    }

    searching = true;

    final android.os.Handler handler =
        new android.os.Handler(
            android.os.Looper.getMainLooper()
        );

    final java.util.concurrent.atomic.AtomicInteger generation =
        new java.util.concurrent.atomic.AtomicInteger();

    final Runnable[] pending = new Runnable[1];

    // Main dialog layout
    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(12), dp(8), dp(12), dp(8));

    // Search input
    EditText query = new EditText(activity);
    query.setHint("Enter city, locality, place or coordinates");
    query.setSingleLine(true);
    query.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
    );

    root.addView(
        query,
        new LinearLayout.LayoutParams(-1, dp(48))
    );

    // Search progress / message
    TextView status = new TextView(activity);
    AppStyle.text(status);
    status.setTextSize(12);
    status.setText("Start typing to find a destination");
    status.setPadding(dp(6), dp(8), dp(6), dp(8));

    root.addView(status);

    // Clickable results container
    ScrollView scroll = new ScrollView(activity);
    scroll.setFillViewport(false);

    LinearLayout resultsBox = new LinearLayout(activity);
    resultsBox.setOrientation(LinearLayout.VERTICAL);

    scroll.addView(resultsBox);

    root.addView(
        scroll,
        new LinearLayout.LayoutParams(-1, dp(235))
    );

    android.app.AlertDialog dialog =
        new AppDialogs(activity)
            .setTitle(UiConfig.text("where_are_you_going"))
            .setView(root)
            .setNegativeButton(
                UiConfig.text("cancel"),
                null
            )
            .create();

    // Execute a location lookup without blocking the UI
    Runnable lookup = () -> {
        final String q =
            query.getText().toString().trim();

        final int requestId =
            generation.incrementAndGet();

        resultsBox.removeAllViews();

        if (q.length() < 2) {
            status.setText("Enter at least 2 characters");
            return;
        }

        boolean coordinates =
            q.matches(
                "[+\\-0-9.]+\\s*,\\s*[+\\-0-9.]+"
            );

        if (manager.offline() && !coordinates) {
            status.setText(
                "Offline: enter latitude, longitude"
            );
            return;
        }

        status.setText("Searching: " + q);

        searches.execute(() -> {
            // Skip requests superseded while queued
            if (requestId != generation.get()) {
                return;
            }

            try {
                List<DestinationSearch.Result> found =
                    // DestinationSearch.search(
                    //     activity.getApplicationContext(),
                    //     q
                    // );
                    DestinationSearch.autocomplete(
                        activity.getApplicationContext(),
                        q
                    );
                activity.runOnUiThread(() -> {
                    if (!dialog.isShowing()
                            || activity.isFinishing()
                            || activity.isDestroyed()
                            || requestId != generation.get()) {
                        return;
                    }

                    resultsBox.removeAllViews();

                    if (found.isEmpty()) {
                        status.setText(
                            "No matching destination found"
                        );
                        return;
                    }

                    status.setText(
                        found.size() + " matching destinations"
                    );

                    for (DestinationSearch.Result place : found) {
                        TextView item = new TextView(activity);

                        AppStyle.text(item);

                        item.setText(place.name());
                        item.setTextSize(14);
                        item.setTextColor(
                            UiConfig.NAV_TEXT_COLOR
                        );

                        item.setGravity(
                            android.view.Gravity.CENTER_VERTICAL
                        );

                        item.setPadding(
                            dp(10),
                            dp(9),
                            dp(10),
                            dp(9)
                        );

                        item.setMinHeight(dp(44));
                        item.setMaxLines(2);

                        item.setBackground(
                            AppStyle.panel(
                                item,
                                UiConfig.CYAN
                            )
                        );

                        LinearLayout.LayoutParams params =
                            new LinearLayout.LayoutParams(
                                -1,
                                -2
                            );

                        params.setMargins(
                            0,
                            dp(2),
                            0,
                            dp(2)
                        );

                        resultsBox.addView(item, params);

                        item.setOnClickListener(v -> {
                            generation.incrementAndGet();
                            dialog.dismiss();
                            plan(place);
                        });
                    }
                });

            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (dialog.isShowing()
                            && requestId == generation.get()) {
                        resultsBox.removeAllViews();
                        status.setText(
                            "Search unavailable: "
                                + error.getMessage()
                        );
                    }
                });
            }
        });
    };

    // Live text-change listener
    query.addTextChangedListener(
        new android.text.TextWatcher() {

            @Override
            public void beforeTextChanged(
                CharSequence s,
                int start,
                int count,
                int after
            ) {}

            @Override
            public void onTextChanged(
                CharSequence s,
                int start,
                int before,
                int count
            ) {
                // Invalidate previous lookup
                generation.incrementAndGet();

                if (pending[0] != null) {
                    handler.removeCallbacks(pending[0]);
                }

                resultsBox.removeAllViews();

                String value = s.toString().trim();

                if (value.length() < 2) {
                    status.setText(
                        "Enter at least 2 characters"
                    );
                    return;
                }

                status.setText("Typing: " + value);

                pending[0] = lookup;

                // Wait 500ms after last keystroke
                handler.postDelayed(
                    pending[0],
                    500
                );
            }

            @Override
            public void afterTextChanged(
                android.text.Editable s
            ) {}
        }
    );

    dialog.setOnDismissListener(d -> {
        generation.incrementAndGet();

        if (pending[0] != null) {
            handler.removeCallbacks(pending[0]);
        }

        searching = false;
    });

    dialog.show();
}


public static List<Result> autocomplete(
        Context context,
        String query
) throws Exception {

    String q = query.trim();

    // Preserve existing coordinate search.
    if (q.matches("[+\\-0-9.]+\\s*,\\s*[+\\-0-9.]+")) {
        return search(context, q);
    }

    if (q.length() < 3) {
        return List.of();
    }

    String url =
        "https://photon.komoot.io/api/?q="
        + java.net.URLEncoder.encode(q, "UTF-8")
        + "&countrycode=IN"
        + "&limit=50"
        + "&bbox=68,6,98,38";

    java.net.HttpURLConnection connection =
        (java.net.HttpURLConnection)
        new java.net.URL(url).openConnection();

    connection.setConnectTimeout(8000);
    connection.setReadTimeout(10000);
    connection.setRequestProperty(
        "User-Agent",
        "oTraiL/0.4.9 destination search"
    );

    try {
        if (connection.getResponseCode() != 200) {
            throw new java.io.IOException(
                "Search HTTP " + connection.getResponseCode()
            );
        }

        String json;

        try (java.io.InputStream input =
                 connection.getInputStream()) {

            json = new String(
                input.readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8
            );
        }

        org.json.JSONArray features =
            new org.json.JSONObject(json)
                .getJSONArray("features");

        java.util.LinkedHashMap<String, Result> unique =
            new java.util.LinkedHashMap<>();

        String prefix = q.toLowerCase(java.util.Locale.ROOT);

        for (int i = 0; i < features.length(); i++) {
            org.json.JSONObject feature =
                features.getJSONObject(i);

            org.json.JSONObject properties =
                feature.getJSONObject("properties");

            String name =
                properties.optString("name", "").trim();

            // Match the actual place name, not its full address.
            if (!name.toLowerCase(java.util.Locale.ROOT)
                    .startsWith(prefix)) {
                continue;
            }

            org.json.JSONArray coordinates =
                feature.getJSONObject("geometry")
                    .getJSONArray("coordinates");

            double lon = coordinates.getDouble(0);
            double lat = coordinates.getDouble(1);

            RoutePlan.Point point =
                new RoutePlan.Point(lat, lon);

            if (!india(point)) {
                continue;
            }

            String city =
                properties.optString("city", "");

            String state =
                properties.optString("state", "");

            String label = name;

            if (!city.isBlank()
                    && !city.equalsIgnoreCase(name)) {
                label += ", " + city;
            }

            if (!state.isBlank()) {
                label += ", " + state;
            }

            String key =
                name.toLowerCase(java.util.Locale.ROOT)
                + ":"
                + Math.round(lat * 100000)
                + ":"
                + Math.round(lon * 100000);

            unique.putIfAbsent(
                key,
                new Result(label, point)
            );
        }

        List<Result> results =
            new java.util.ArrayList<>(unique.values());

        // Exact matches first. Preserve provider relevance
        // order among the remaining suggestions.
        results.sort(
            java.util.Comparator.comparingInt(
                result ->
                    result.name()
                        .split(",", 2)[0]
                        .equalsIgnoreCase(q) ? 0 : 1
            )
        );

        return new java.util.ArrayList<>(
            results.subList(
                0,
                Math.min(5, results.size())
            )
        );

    } finally {
        connection.disconnect();
    }
}

  void plan(DestinationSearch.Result destination){plan(destination,false);}
  void confirmedPlan(DestinationSearch.Result destination){plan(destination,true);}
  private void plan(DestinationSearch.Result destination,boolean autoStart) {
    if (!DestinationSearch.india(destination.point())) {
      toast(UiConfig.text("choose_a_destination_in_india"));
      return;
    }
    if (manager.offline() && !OfflineRouting.selected(activity)) {
      toast(UiConfig.text("choose_brouter_or_valhalla_in_maps_downloads_to_calculate_offlin"));
      return;
    }
    autoStartTarget=autoStart?destination:null;
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
    if(autoStartTarget!=null&&pending==null&&s.route()!=null&&s.target()!=null&&"PREVIEW".equals(s.mode())&&RoutePlan.metres(s.target(),autoStartTarget.point())<1){autoStartTarget=null;manager.start();s=manager.state();changed.run();}
    else if(autoStartTarget!=null&&pending==null&&!"PLANNING".equals(s.mode())&&s.target()!=null&&RoutePlan.metres(s.target(),autoStartTarget.point())>=1)autoStartTarget=null;
    boolean has = s.route() != null;
    panel.setVisibility(has || pending != null || !s.note().isEmpty() ? View.VISIBLE : View.GONE);
    if (panel.getVisibility() != View.VISIBLE) {
      draw(map, s);
      return;
    }
    android.text.SpannableString destination=new android.text.SpannableString(DestinationLabel.format(pending != null ? pending.name() : s.name()));
    int placeEnd=destination.toString().indexOf(':');if(placeEnd<0)placeEnd=destination.length();
    if(UiConfig.NAV_TITLE_BOLD)destination.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),0,placeEnd,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    title.setTypeface(android.graphics.Typeface.create(UiConfig.NAV_TITLE_FONT,android.graphics.Typeface.NORMAL));title.getPaint().setFakeBoldText(false);title.setText(destination);
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
      route.getOutlinePaint().setStrokeWidth(dp(6));
      route.getOutlinePaint().setStyle(android.graphics.Paint.Style.STROKE);route.getOutlinePaint().setStrokeJoin(android.graphics.Paint.Join.ROUND);route.getOutlinePaint().setStrokeCap(android.graphics.Paint.Cap.ROUND);route.getOutlinePaint().setAntiAlias(true);route.getOutlinePaint().setPathEffect(null);
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
      end.setTitle(DestinationLabel.format(s.name()));
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
    new AppDialogs(activity)
        .setTitle(DestinationLabel.format(s.name()))
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
