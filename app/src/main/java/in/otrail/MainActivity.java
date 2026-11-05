package in.otrail;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.location.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.*;
import org.osmdroid.mapsforge.*;
import org.osmdroid.tileprovider.MapTileProviderBasic;
import org.osmdroid.tileprovider.tilesource.*;
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver;
import org.osmdroid.util.*;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.*;

public final class MainActivity extends Activity {
  private static final int INK = 0xff102d32,
      TEAL = 0xff008f83,
      MUTED = 0xff597277,
      PAPER = 0xfff3f6f3;
  private TrailDb db;
  private NavigationControls navigation;
  private UpdateControls updates;
  private boolean navigationFollow = true;
  private long routeFitRevision = -1;
  private MapPacks packs;
  private MapView map;
  private LinearLayout root, body, downloadCards;
  private TextView status, stats, mode, packSummary;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final ExecutorService heritageIo = Executors.newSingleThreadExecutor();
  private final ArrayList<Runnable> cardUpdates = new ArrayList<>();
  private boolean downloads, history, offlineOnly, follow = true, busy;
  private long selectedTrip = -1, lastFixId = -1;
  private int provider;
  private Location preview;
  private String lastState = "";
  private int lastStopSignature, lastHeritageSignature;
  private boolean historicVisible;
  private boolean fitTrail;
  private long loggedGapSignature = Long.MIN_VALUE;
  private boolean historicLoading;
  private List<HistoricPlaces.Place> historicPlaces = List.of();
  private GeoPoint nearbyCenter;
  private String openMarkerKey;
  private final Map<String, Marker> visibleMarkers = new HashMap<>();
  private final Runnable ticker =
      new Runnable() {
        public void run() {
          if (downloads) {
            for (Runnable r : cardUpdates) r.run();
          } else {
            refresh();
            navigationTick();
          }
          handler.postDelayed(this, 2500);
        }
      };

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    db = new TrailDb(this);
    packs = new MapPacks(this, db);
    navigation =
        new NavigationControls(this, this::navigationOrigin, this::locate, this::navigationChanged);
    updates = new UpdateControls(this);
    AppUpdater.schedule(this);
    io.execute(
        () -> {
          for (MapPacks.Pack p : MapPacks.PACKS) packs.state(p);
        });
    Configuration.getInstance()
        .setUserAgentValue("oTraiL/0.1 (in.otrail; personal India navigator)");
    Configuration.getInstance().setOsmdroidBasePath(new File(getCacheDir(), "osmdroid"));
    Configuration.getInstance().setOsmdroidTileCache(new File(getCacheDir(), "osmdroid/tiles"));
    Configuration.getInstance().setExpirationExtendedDuration(7L * 24 * 3600 * 1000);
    MapsForgeTileSource.createInstance(getApplication());
    offlineOnly = getPreferences(0).getBoolean("offline", false);
    provider = getPreferences(0).getInt("provider", 0);
    showMap();
    if (AppUpdater.ACTION.equals(getIntent().getAction())) handler.post(() -> updates.show());
    if (!TrackingStartup.hasLocation(this))
      requestPermissions(
          new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
          },
          1);
    else if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 2);
  }

  private int dp(float n) {
    return (int) (n * getResources().getDisplayMetrics().density + .5f);
  }

  private GradientDrawable bg(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  private TextView text(String s, int size, int color) {
    TextView v = new TextView(this);
    v.setText(s);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setPadding(dp(16), dp(7), dp(16), dp(7));
    return v;
  }

  private Button button(String s, Runnable click) {
    Button b = new Button(this);
    b.setText(s);
    b.setTextSize(12);
    b.setAllCaps(false);
    b.setTextColor(INK);
    b.setOnClickListener(v -> click.run());
    return b;
  }

  private void base(String subtitle) {
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(PAPER);
    root.setFitsSystemWindows(true);
    setContentView(root);
    LinearLayout head = new LinearLayout(this);
    head.setGravity(Gravity.CENTER_VERTICAL);
    head.setPadding(dp(4), dp(8), dp(8), dp(8));
    head.setBackgroundColor(INK);
    TextView title = text("oTraiL", 27, Color.WHITE);
    title.setTypeface(null, Typeface.BOLD);
    head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    TextView region = text("INDIA  /  IN", 12, 0xff8de0d3);
    head.addView(region);
    root.addView(head);
    TextView sub = text(subtitle, 12, MUTED);
    root.addView(sub);
    body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
  }

  private void showMap() {
    fitTrail = true;
    lastHeritageSignature = 0;
    openMarkerKey = null;
    visibleMarkers.clear();
    downloads = false;
    cardUpdates.clear();
    base("YOUR ROAD. YOUR RECORD.  ·  Personal navigation");
    LinearLayout row = new LinearLayout(this);
    mode = text(history ? "HISTORY" : "LIVE TRAIL", 13, TEAL);
    mode.setTypeface(null, Typeface.BOLD);
    row.addView(mode, new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(button("Navigate", () -> navigation.search()));
    row.addView(button("Maps & downloads", this::showDownloads));
    body.addView(row);
    LinearLayout tools = new LinearLayout(this);
    String[] names = {"OSM roads", "Esri satellite", "India offline"};
    Spinner selector = new Spinner(this);
    selector.setAdapter(
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
    selector.setSelection(provider);
    tools.addView(selector, new LinearLayout.LayoutParams(0, dp(48), 1));
    Switch network = new Switch(this);
    network.setText("Offline only ");
    network.setChecked(offlineOnly);
    tools.addView(network);
    body.addView(tools);
    body.addView(navigation.panel());
    map = new MapView(this);
    map.setMultiTouchControls(true);
    map.setOnTouchListener(
        (v, event) -> {
          if (event.getActionMasked() == android.view.MotionEvent.ACTION_MOVE)
            navigationFollow = false;
          return false;
        });
    map.getController().setZoom(5.0);
    map.getController().setCenter(new GeoPoint(22.5, 79));
    map.setMinZoomLevel(4.0);
    map.setScrollableAreaLimitDouble(new BoundingBox(38, 98, 6, 68));
    body.addView(map, new LinearLayout.LayoutParams(-1, 0, 1));
    applyProvider();
    selector.setOnItemSelectedListener(
        new android.widget.AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View v, int i, long id) {
            if (provider != i) {
              provider = i;
              getPreferences(0).edit().putInt("provider", i).apply();
              applyProvider();
              lastFixId = -1;
              lastStopSignature = 0;
              refresh();
            }
          }
        });
    network.setOnCheckedChangeListener(
        (v, checked) -> {
          offlineOnly = checked;
          getPreferences(0).edit().putBoolean("offline", checked).apply();
          map.setUseDataConnection(!checked && provider != 2);
        });
    map.getOverlays()
        .add(
            new MapEventsOverlay(
                new MapEventsReceiver() {
                  public boolean singleTapConfirmedHelper(GeoPoint p) {
                    org.osmdroid.views.overlay.infowindow.InfoWindow.closeAllInfoWindowsOn(map);
                    openMarkerKey = null;
                    return false;
                  }

                  public boolean longPressHelper(GeoPoint p) {
                    destination(p);
                    return true;
                  }
                }));
    TextView tip = text("Hold the map to navigate · Tap stops for arrival & duration", 11, MUTED);
    body.addView(tip);
    status = text("Ready when you are", 17, INK);
    status.setTypeface(null, Typeface.BOLD);
    body.addView(status);
    stats = text("Always-on GPS · every 5 seconds", 12, MUTED);
    body.addView(stats);
    LinearLayout actions = new LinearLayout(this);
    actions.addView(
        button("Tracker status", this::trackerStatus), new LinearLayout.LayoutParams(0, dp(52), 1));
    actions.addView(
        button("Old places", this::oldPlaces), new LinearLayout.LayoutParams(0, dp(52), 1));
    actions.addView(button("Locate", this::locate), new LinearLayout.LayoutParams(0, dp(52), 1));
    actions.addView(
        button(history ? "Live" : "History", this::history),
        new LinearLayout.LayoutParams(0, dp(52), 1));
    body.addView(actions);
    TextView legend =
        text("● Green ready   ● Blue moving   ● Turquoise stopped   ● Cyan history", 10, MUTED);
    body.addView(legend);
    if (getResources().getConfiguration().orientation
        == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
      root.getChildAt(0).setVisibility(View.GONE);
      root.getChildAt(1).setVisibility(View.GONE);
      ArrayList<View> controls = new ArrayList<>();
      for (int i = 0; i < body.getChildCount(); i++) {
        View child = body.getChildAt(i);
        if (child != map) controls.add(child);
      }
      body.removeAllViews();
      body.setOrientation(LinearLayout.HORIZONTAL);
      body.addView(map, new LinearLayout.LayoutParams(0, -1, 1));
      ScrollView panel = new ScrollView(this);
      LinearLayout stack = new LinearLayout(this);
      stack.setOrientation(LinearLayout.VERTICAL);
      for (View child : controls) stack.addView(child);
      panel.addView(stack);
      body.addView(panel, new LinearLayout.LayoutParams(dp(270), -1));
    }
    lastFixId = -1;
    lastStopSignature = 0;
    refresh();
  }

  private void applyProvider() {
    if (map == null) return;
    try {
      if (provider == 2) {
        if (!packs.active().exists()) {
          map.setUseDataConnection(false);
          toast("Download the India pack in Maps & downloads first");
          return;
        }
        MapsForgeTileSource source =
            MapsForgeTileSource.createFromFiles(
                new File[] {packs.active()}, null, "India-" + packs.active().lastModified());
        map.setTileProvider(
            new MapsForgeTileProvider(new SimpleRegisterReceiver(this), source, null));
      } else {
        ITileSource source =
            provider == 0
                ? TileSourceFactory.MAPNIK
                : new OnlineTileSourceBase(
                    "EsriWorldImagery",
                    0,
                    19,
                    256,
                    ".jpg",
                    new String[] {
                      "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"
                    },
                    "Tiles © Esri · Maxar · Earthstar Geographics and contributors") {
                  @Override
                  public String getTileURLString(long index) {
                    return getBaseUrl()
                        + MapTileIndex.getZoom(index)
                        + "/"
                        + MapTileIndex.getY(index)
                        + "/"
                        + MapTileIndex.getX(index);
                  }
                };
        map.setTileProvider(new MapTileProviderBasic(this, source));
      }
      map.setUseDataConnection(!offlineOnly && provider != 2);
      map.getOverlays().removeIf(o -> o instanceof CopyrightOverlay);
      map.getOverlays().add(new CopyrightOverlay(this));
      map.invalidate();
    } catch (Exception e) {
      toast("Map unavailable: " + e.getMessage());
    }
  }

  private void refresh() {
    if (busy || map == null) return;
    busy = true;
    final boolean viewHistory = history;
    final long chosen = selectedTrip;
    io.execute(
        () -> {
          try {
            long active = db.activeTrip();
            long trip = viewHistory ? chosen : -1;
            List<TrailDb.Fix> fixes = db.fixes(trip);
            List<TrailDb.Stop> stops = db.stops(trip);
            List<TrailSegments.Sample> samples = new ArrayList<>();
            for (TrailDb.Fix f : fixes)
              samples.add(new TrailSegments.Sample(f.id(), f.trip(), f.time(), f.lat(), f.lon()));
            List<TrailSegments.Segment> segments = TrailSegments.build(samples, 6000);
            long gapSignature = 1;
            for (TrailSegments.Segment edge : segments)
              if (edge.inferred())
                gapSignature =
                    31 * gapSignature
                        + Objects.hash(edge.points().get(0).id(), edge.points().get(1).id());
            if (gapSignature != loggedGapSignature) {
              db.logInferredGaps(segments);
              loggedGapSignature = gapSignature;
            }
            GeoPoint center = nearbyCenter;
            if (center == null && !fixes.isEmpty()) {
              TrailDb.Fix f = fixes.get(fixes.size() - 1);
              center = new GeoPoint(f.lat(), f.lon());
            }
            List<HistoricPlaces.Place> places =
                historicVisible && center != null
                    ? HistoricPlaces.cached(db, center.getLatitude(), center.getLongitude())
                    : List.of();
            runOnUiThread(
                () -> {
                  busy = false;
                  if (downloads || history != viewHistory || viewHistory && selectedTrip != chosen)
                    return;
                  render(active, trip, fixes, stops, segments, places);
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  busy = false;
                  toast(e.getMessage());
                });
          }
        });
  }

  private void render(
      long active,
      long trip,
      List<TrailDb.Fix> fixes,
      List<TrailDb.Stop> stops,
      List<TrailSegments.Segment> segments,
      List<HistoricPlaces.Place> places) {
    historicPlaces = places;
    if (fixes.isEmpty()) {
      if (lastHeritageSignature != places.hashCode()) {
        lastHeritageSignature = places.hashCode();
        org.osmdroid.views.overlay.infowindow.InfoWindow.closeAllInfoWindowsOn(map);
        openMarkerKey = null;
        drawHistoricPlaces();
      }
      status.setText(
          active == -1
              ? "Setup needed · grant location access"
              : "Always recording · waiting for GPS");
      stats.setText("GPS needs a clear view of the sky · stops logged after 2 min");
      return;
    }
    TrailDb.Fix last = fixes.get(fixes.size() - 1);
    long age = System.currentTimeMillis() - last.time();
    boolean stale = age > 30000;
    TrailDb.Stop current = stops.isEmpty() ? null : stops.get(stops.size() - 1);
    boolean stopped = current != null && current.departure() == 0;
    boolean recorded = history || active == -1;
    int color =
        recorded
            ? 0xff00cce5
            : stale
                ? 0xff889999
                : stopped ? 0xff00b5a5 : last.speed() > 1.5 ? 0xff2878ef : 0xff22aa65;
    String state =
        recorded
            ? "History"
            : stale
                ? "GPS signal stale"
                : stopped
                    ? "Stopped · " + duration(current.observed() - current.arrival())
                    : last.speed() > 1.5 ? "On the move" : "Recording";
    status.setText(state + String.format(Locale.US, "  ·  %.0f km/h", last.speed() * 3.6));
    stats.setText(
        (trip < 0 ? "Full trail · " : "")
            + clock(fixes.get(0).time())
            + " · "
            + fixes.size()
            + " fixes · "
            + stops.size()
            + " stops · ±"
            + Math.round(last.accuracy())
            + " m · "
            + (stale ? "last fix " + duration(age) + " ago" : clock(last.time())));
    int stopSignature = stops.hashCode(), heritageSignature = places.hashCode();
    if (lastFixId == last.id()
        && lastState.equals(state)
        && lastStopSignature == stopSignature
        && lastHeritageSignature == heritageSignature) return;
    lastFixId = last.id();
    lastState = state;
    lastStopSignature = stopSignature;
    lastHeritageSignature = heritageSignature;
    Marker selected = visibleMarkers.get(openMarkerKey);
    if (selected == null || !selected.isInfoWindowShown()) openMarkerKey = null;
    org.osmdroid.views.overlay.infowindow.InfoWindow.closeAllInfoWindowsOn(map);
    visibleMarkers.clear();
    map.getOverlays()
        .removeIf(
            o ->
                (o instanceof Marker || o instanceof Polyline)
                    && (!(o instanceof Marker)
                        || ((Marker) o).getId() == null
                        || !((Marker) o).getId().startsWith("navigation:"))
                    && (!(o instanceof Polyline)
                        || ((Polyline) o).getId() == null
                        || !((Polyline) o).getId().startsWith("navigation:")));
    for (TrailSegments.Segment segment : segments) {
      ArrayList<GeoPoint> points = new ArrayList<>();
      for (TrailSegments.Sample p : segment.points()) points.add(new GeoPoint(p.lat(), p.lon()));
      line(points, segment.inferred() ? 0xffd88919 : recorded ? 0xff00adc5 : TEAL);
    }
    for (TrailDb.Stop s : stops) {
      Marker m = new Marker(map);
      m.setPosition(new GeoPoint(s.lat(), s.lon()));
      m.setAnchor(.5f, .5f);
      m.setIcon(dot(0xff00b5a5, 14));
      String place =
          s.placeName() == null || s.placeName().isBlank()
              ? "Location not yet identified"
              : s.placeName();
      m.setTitle(place);
      StringBuilder details = new StringBuilder();
      if (s.locality() != null && !s.locality().equals(place))
        details.append(s.locality()).append("\n");
      addressLine(details, "Road", s.road());
      addressLine(details, "District", s.district());
      addressLine(details, "State", s.state());
      addressLine(details, "Country", s.country());
      addressLine(details, "PIN", s.postalCode());
      details
          .append("Arrived: ")
          .append(clock(s.arrival()))
          .append("\nObserved duration: ")
          .append(duration(s.observed() - s.arrival()))
          .append(s.departure() == 0 ? "\nLast observation: " : "\nDeparted: ")
          .append(clock(s.departure() == 0 ? s.observed() : s.departure()));
      details.append(String.format(Locale.US, "\nGPS: %.6f, %.6f", s.lat(), s.lon()));
      if (!"RESOLVED".equals(s.geocodeStatus()))
        details
            .append("\nPlace lookup: ")
            .append(
                "FAILED".equals(s.geocodeStatus()) ? "unavailable; retry after 24h" : "pending");
      m.setSnippet(android.text.TextUtils.htmlEncode(details.toString()).replace("\n", "<br/>"));
      registerMarker("stop:" + s.id(), m);
    }
    Marker dot = new Marker(map);
    dot.setPosition(new GeoPoint(last.lat(), last.lon()));
    dot.setAnchor(.5f, .5f);
    dot.setIcon(dot(color, 22));
    dot.setTitle(state);
    dot.setSnippet(
        String.format(
            Locale.US,
            "%.6f, %.6f\n%.1f km/h · ±%.0f m\n%s",
            last.lat(),
            last.lon(),
            last.speed() * 3.6,
            last.accuracy(),
            clock(last.time())));
    registerMarker("live", dot);
    drawHistoricPlaces();
    Marker reopen = visibleMarkers.get(openMarkerKey);
    if (reopen != null) reopen.showInfoWindow();
    if (NavigationManager.get(this).state().route() != null && !history) fitTrail = false;
    if (fitTrail) {
      fitTrail = false;
      follow = false;
      double north = -90, south = 90, east = -180, west = 180;
      for (TrailDb.Fix f : fixes) {
        north = Math.max(north, f.lat());
        south = Math.min(south, f.lat());
        east = Math.max(east, f.lon());
        west = Math.min(west, f.lon());
      }
      if (north - south < .001 && east - west < .001) {
        map.getController().setCenter(dot.getPosition());
        map.getController().setZoom(16.0);
      } else {
        BoundingBox bounds = new BoundingBox(north, east, south, west);
        MapView view = map;
        view.post(
            () -> {
              if (view == map) view.zoomToBoundingBox(bounds, false, dp(30));
            });
      }
    } else if (follow) {
      map.getController().setCenter(dot.getPosition());
      if (map.getZoomLevelDouble() < 10) map.getController().setZoom(15.0);
      follow = false;
    }
    map.invalidate();
  }

  private void registerMarker(String key, Marker marker) {
    Marker previous = visibleMarkers.put(key, marker);
    if (previous != null && previous != marker) {
      previous.closeInfoWindow();
      map.getOverlays().remove(previous);
    }
    marker.setOnMarkerClickListener(
        (m, view) -> {
          openMarkerKey = key;
          MarkerPopups.openOnly(view, m);
          return true;
        });
    map.getOverlays().add(marker);
  }

  private static void addressLine(StringBuilder out, String label, String value) {
    if (value != null && !value.isBlank())
      out.append(label).append(": ").append(value).append("\n");
  }

  private void line(ArrayList<GeoPoint> pts, int color) {
    if (pts.size() < 2) return;
    Polyline p = new Polyline(map);
    p.setPoints(pts);
    p.getOutlinePaint().setColor(color);
    p.getOutlinePaint().setStrokeWidth(dp(4));
    map.getOverlays().add(p);
  }

  private LayerDrawable dot(int color, int size) {
    GradientDrawable inner = bg(color, 99);
    inner.setShape(GradientDrawable.OVAL);
    inner.setStroke(dp(3), Color.WHITE);
    inner.setSize(dp(size), dp(size));
    return new LayerDrawable(new android.graphics.drawable.Drawable[] {inner});
  }

  private void trackerStatus() {
    new AlertDialog.Builder(this)
        .setTitle("Always-on recording")
        .setMessage(
            "GPS request interval: 5 seconds. Recording runs independently of Live / History.\n\n"
                + (TrackingStartup.rebootReady(this)
                    ? "Reboot location permission is ready."
                    : "Setup needed: grant precise location and select Allow all the time so"
                        + " recording can restart after reboot.")
                + "\n\n"
                + "On POCO: enable Background autostart and set Battery → No restrictions. Android"
                + " force-stop prevents automatic restart until you reopen oTraiL.")
        .setPositiveButton(
            "Location permissions",
            (d, w) -> {
              if (!TrackingStartup.hasLocation(this))
                requestPermissions(
                    new String[] {
                      Manifest.permission.ACCESS_FINE_LOCATION,
                      Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    1);
              else
                startActivity(
                    new Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            })
        .setNegativeButton("Close", null)
        .show();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] p, int[] results) {
    super.onRequestPermissionsResult(request, p, results);
    if (request == 1) {
      if (TrackingStartup.hasLocation(this)) {
        TrackingStartup.start(this, false);
        trackerStatus();
      } else toast("Precise location is required for automatic GPS recording");
    }
    if (request == 3 && TrackingStartup.hasLocation(this)) locate();
  }

  private void locate() {
    fitTrail = false;
    navigationFollow = true;
    follow = true;
    lastFixId = -1;
    lastStopSignature = 0;
    if (db.activeTrip() != -1) refresh();
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(
          new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
          },
          3);
      return;
    }
    LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
    if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
      startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
      return;
    }
    toast("Finding GPS location…");
    lm.getCurrentLocation(
        LocationManager.GPS_PROVIDER,
        null,
        getMainExecutor(),
        l -> {
          if (l == null || isFinishing()) return;
          preview = l;
          if (downloads) return;
          Marker m = new Marker(map);
          m.setPosition(new GeoPoint(l.getLatitude(), l.getLongitude()));
          m.setAnchor(.5f, .5f);
          m.setIcon(dot(0xff22aa65, 22));
          m.setTitle("Current GPS location");
          m.setSnippet("±" + Math.round(l.getAccuracy()) + " m");
          registerMarker("live", m);
          map.getController().setZoom(16.0);
          map.getController().setCenter(m.getPosition());
        });
  }

  private void oldPlaces() {
    new AlertDialog.Builder(this)
        .setTitle("Old places · 200–600 years · 25 km")
        .setItems(
            new String[] {
              "Find / refresh nearby places (OSM)",
              "Show cached nearby places",
              "Hide historic labels"
            },
            (d, i) -> {
              if (i == 2) {
                historicVisible = false;
                lastFixId = -1;
                lastStopSignature = 0;
                refresh();
                return;
              }
              historicVisible = true;
              io.execute(
                  () -> {
                    List<TrailDb.Fix> fixes = db.fixes(-1);
                    runOnUiThread(
                        () -> {
                          if (isFinishing() || downloads) return;
                          long recordedTime =
                              fixes.isEmpty() ? 0 : fixes.get(fixes.size() - 1).time();
                          if (preview != null
                              && preview.getTime() >= recordedTime
                              && System.currentTimeMillis() - preview.getTime() <= 300000)
                            nearbyCenter =
                                new GeoPoint(preview.getLatitude(), preview.getLongitude());
                          else if (!fixes.isEmpty()
                              && System.currentTimeMillis() - recordedTime <= 300000) {
                            TrailDb.Fix f = fixes.get(fixes.size() - 1);
                            nearbyCenter = new GeoPoint(f.lat(), f.lon());
                          } else {
                            toast("Locate yourself first to find nearby places");
                            locate();
                            return;
                          }
                          map.getController().setCenter(nearbyCenter);
                          map.getController().setZoom(10.5);
                          lastFixId = -1;
                          lastStopSignature = 0;
                          if (i == 0) fetchHistoricPlaces();
                          else refresh();
                        });
                  });
            })
        .setNegativeButton("Close", null)
        .show();
  }

  private void fetchHistoricPlaces() {
    if (historicLoading) {
      toast("Historic-place search is already running");
      return;
    }
    if (offlineOnly) {
      toast("Offline only: showing cached places. Turn it off to refresh.");
      refresh();
      return;
    }
    GeoPoint center = nearbyCenter;
    if (center == null) return;
    historicLoading = true;
    toast("Finding dated historic places within 25 km…");
    heritageIo.execute(
        () -> {
          String message;
          try (TrailDb cache = new TrailDb(getApplicationContext())) {
            int count = HistoricPlaces.fetch(cache, center.getLatitude(), center.getLongitude());
            message = count + " dated places found · showing up to 50 nearest";
            cache.log(
                "Historic places", "SUCCESS", count + " source-dated places cached within 25 km");
          } catch (Exception e) {
            message = "Lookup unavailable; cached places kept. " + e.getMessage();
          }
          String result = message;
          runOnUiThread(
              () -> {
                historicLoading = false;
                if (isFinishing()) return;
                toast(result);
                lastFixId = -1;
                lastStopSignature = 0;
                if (!downloads) refresh();
              });
        });
  }

  private void drawHistoricPlaces() {
    if (map == null) return;
    map.getOverlays()
        .removeIf(
            o ->
                o instanceof Marker
                    && ((Marker) o).getId() != null
                    && ((Marker) o).getId().startsWith("historic:"));
    if (!historicVisible) return;
    int count = 0, year = java.time.Year.now(java.time.ZoneOffset.UTC).getValue();
    for (HistoricPlaces.Place p : historicPlaces) {
      if (count++ >= 50) break;
      Marker marker = new Marker(map);
      String key = "historic:" + p.key();
      marker.setId(key);
      marker.setPosition(new GeoPoint(p.lat(), p.lon()));
      marker.setAnchor(.5f, 1);
      marker.setIcon(historicLabel(p.name()));
      marker.setTitle(p.name());
      String age =
          p.earliest() == p.latest()
              ? "" + (year - p.earliest())
              : (year - p.latest()) + "–" + (year - p.earliest());
      String details =
          "Source date: "
              + p.date()
              + "\n"
              + (p.approximate() ? "Approximate age: " : "Age from source date: ")
              + age
              + " years\n© OpenStreetMap contributors · not independently verified\nCached: "
              + clock(p.fetched())
              + "\n";
      String html =
          android.text.TextUtils.htmlEncode(details).replace("\n", "<br/>")
              + "<a href=\"https://www.openstreetmap.org/"
              + p.key()
              + "\">View source place</a>";
      marker.setSnippet(html);
      registerMarker(key, marker);
    }
    map.invalidate();
  }

  private android.graphics.drawable.Drawable historicLabel(String name) {
    String label = name.length() > 28 ? name.substring(0, 27) + "…" : name;
    android.graphics.Paint paint =
        new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    paint.setTextSize(dp(11));
    paint.setTypeface(Typeface.DEFAULT_BOLD);
    int w = (int) paint.measureText(label) + dp(20), h = dp(35);
    android.graphics.Bitmap bitmap =
        android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
    android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
    paint.setColor(0xfff3bf55);
    canvas.drawRoundRect(0, 0, w, h - dp(7), dp(7), dp(7), paint);
    canvas.drawCircle(w / 2f, h - dp(4), dp(4), paint);
    paint.setColor(INK);
    canvas.drawText(label, dp(10), dp(18), paint);
    return new android.graphics.drawable.BitmapDrawable(getResources(), bitmap);
  }

  private void history() {
    if (history) {
      history = false;
      follow = true;
      showMap();
      return;
    }
    List<Long> ids = db.trips();
    if (ids.isEmpty()) {
      toast("Waiting for the first GPS trail");
      return;
    }
    String[] names = new String[ids.size() + 1];
    names[0] = "Entire collected trail";
    for (int i = 0; i < ids.size(); i++) {
      List<TrailDb.Fix> f = db.fixes(ids.get(i));
      names[i + 1] =
          "Trail #"
              + ids.get(i)
              + (f.isEmpty()
                  ? " · waiting for GPS"
                  : " · " + clock(f.get(0).time()) + " · " + f.size() + " fixes");
    }
    new AlertDialog.Builder(this)
        .setTitle("Your journeys")
        .setItems(
            names,
            (d, i) -> {
              selectedTrip = i == 0 ? -1 : ids.get(i - 1);
              history = true;
              follow = true;
              showMap();
            })
        .setNegativeButton("Close", null)
        .show();
  }

  private void destination(GeoPoint p) {
    new AlertDialog.Builder(this)
        .setTitle(
            String.format(Locale.US, "Destination %.5f, %.5f", p.getLatitude(), p.getLongitude()))
        .setPositiveButton(
            "Preview in oTraiL",
            (d, w) ->
                navigation.plan(
                    new DestinationSearch.Result(
                        "Map destination", new RoutePlan.Point(p.getLatitude(), p.getLongitude()))))
        .setNeutralButton("Other apps", (d, w) -> externalDestination(p))
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void externalDestination(GeoPoint p) {
    new AlertDialog.Builder(this)
        .setTitle(
            String.format(Locale.US, "Navigate to %.5f, %.5f", p.getLatitude(), p.getLongitude()))
        .setItems(
            new String[] {"Google Maps", "OsmAnd", "Other maps"},
            (d, i) -> {
              String coords = p.getLatitude() + "," + p.getLongitude();
              Intent intent =
                  new Intent(
                      Intent.ACTION_VIEW,
                      Uri.parse(
                          i == 0
                              ? "google.navigation:q=" + coords
                              : i == 1 ? "osmand.navigation:ll=" + coords : "geo:0,0?q=" + coords));
              try {
                startActivity(intent);
              } catch (ActivityNotFoundException e) {
                toast("This navigation app is not installed");
              }
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void showDownloads() {
    downloads = true;
    if (map != null) {
      map.onPause();
      map.onDetach();
      map = null;
    }
    base("MAPS & DOWNLOADS  ·  India only");
    Button back = button("← Back to map", this::showMap);
    body.addView(back);
    ScrollView scroll = new ScrollView(this);
    downloadCards = new LinearLayout(this);
    downloadCards.setOrientation(LinearLayout.VERTICAL);
    downloadCards.setPadding(dp(12), 0, dp(12), dp(12));
    scroll.addView(downloadCards);
    body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView title = text("Take India with you.", 25, INK);
    title.setTypeface(null, Typeface.BOLD);
    downloadCards.addView(title);
    downloadCards.addView(
        text(
            "Map data downloads straight to phone storage. Wi-Fi or cellular. Completed datasets"
                + " stay in Downloads/oTraiL across reinstall; import the retained .map file"
                + " afterwards. Sizes are estimates until the server reports the total.",
            13,
            MUTED));
    cardUpdates.clear();
    for (MapPacks.Pack p : MapPacks.PACKS) packCard(p);
    downloadCards.addView(button("App updates · daily / check now", () -> updates.show()));
    downloadCards.addView(
        button(
            "Online routing server",
            () -> {
              EditText endpoint = new EditText(this);
              endpoint.setText(NavigationManager.get(this).server());
              new AlertDialog.Builder(this)
                  .setTitle("HTTPS OSRM driving endpoint")
                  .setView(endpoint)
                  .setPositiveButton(
                      "Save",
                      (d, w) -> {
                        try {
                          NavigationManager.get(this).server(endpoint.getText().toString());
                          toast("Routing endpoint saved");
                        } catch (Exception e) {
                          toast(e.getMessage());
                        }
                      })
                  .setNegativeButton("Cancel", null)
                  .show();
            }));
    downloadCards.addView(
        button(
            "Identify historical stop locations",
            () -> {
              StopGeocoder.backfill(this);
              toast("Historical place lookup queued · failed lookups retry after 24 hours");
            }));
    infoCard(
        "Always-on trail recording",
        TrackingStartup.rebootReady(this) ? "REBOOT PERMISSION READY" : "REBOOT SETUP NEEDED",
        "GPS request: every 5 seconds. Recording starts automatically after permission is granted"
            + " and restarts after boot/update when background location is allowed.");
    downloadCards.addView(button("Tracker permissions & reboot setup", this::trackerStatus));
    infoCard(
        "Trail backup · Google Drive or phone folder",
        "PERSISTENT BACKUP",
        new TrailBackup(this).status());
    downloadCards.addView(
        button(
            "Choose backup location · Google Drive / Files",
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("Choose your backup location")
                    .setMessage(
                        "In the Save picker, select Google Drive, open the folder you want and save"
                            + " otrail-backup.json. You can also use a persistent Documents folder"
                            + " on this phone. Enable Drive in the picker's side menu if available."
                            + " Automatic writes happen every 15 minutes; Drive manages network"
                            + " sync. After reinstall, use Restore to reopen the saved backup, then"
                            + " select the backup target again. A fresh backup target should be a"
                            + " new file.")
                    .setPositiveButton(
                        "Choose location",
                        (d, w) -> {
                          Intent i =
                              new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                  .setType("application/json")
                                  .addCategory(Intent.CATEGORY_OPENABLE)
                                  .putExtra(Intent.EXTRA_TITLE, "otrail-backup.json")
                                  .addFlags(
                                      Intent.FLAG_GRANT_READ_URI_PERMISSION
                                          | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                          | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                          startActivityForResult(i, 22);
                        })
                    .setNegativeButton("Close", null)
                    .show()));
    downloadCards.addView(button("Back up trail now", () -> backupNow()));
    downloadCards.addView(
        button(
            "Restore trail from Google Drive / Files",
            () -> {
              Intent i =
                  new Intent(Intent.ACTION_OPEN_DOCUMENT)
                      .setType("*/*")
                      .addCategory(Intent.CATEGORY_OPENABLE);
              startActivityForResult(i, 23);
            }));

    infoCard(
        "OpenStreetMap · roads",
        "ONLINE + VIEWED-TILE CACHE",
        "Road basemap with the same trails and stop details. © OpenStreetMap contributors. Public"
            + " tiles do not permit bulk/offline pack downloads.");
    infoCard(
        "Esri · satellite",
        "ONLINE ONLY",
        "Satellite context with your trail overlay. Tiles © Esri, Maxar, Earthstar Geographics and"
            + " contributors. Bulk offline download is not enabled.");
    infoCard(
        "In-app driving navigation",
        "TEXT DIRECTIONS · NO VOICE",
        "Use Navigate to search or hold a point on the map. Preview and start a driving route in"
            + " oTraiL. OSRM / FOSSGIS provides online routes; rerouting needs internet. No live"
            + " traffic or voice. Other apps remain optional.");
    downloadCards.addView(
        button(
            "Import an India .map file",
            () -> {
              Intent i =
                  new Intent(Intent.ACTION_OPEN_DOCUMENT)
                      .setType("*/*")
                      .addCategory(Intent.CATEGORY_OPENABLE);
              startActivityForResult(i, 20);
            }));
    downloadCards.addView(
        button(
            "Export all GPS logs · GeoJSON",
            () -> {
              Intent i =
                  new Intent(Intent.ACTION_CREATE_DOCUMENT)
                      .setType("application/geo+json")
                      .putExtra(
                          Intent.EXTRA_TITLE, "otrail-" + System.currentTimeMillis() + ".geojson");
              startActivityForResult(i, 21);
            }));
    downloadCards.addView(
        button(
            "Manual update history",
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("Update log")
                    .setMessage(db.logs())
                    .setPositiveButton("Close", null)
                    .show()));
    downloadCards.addView(
        button(
            "Location & battery settings",
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("Keep recording on your POCO")
                    .setMessage(
                        "Recording starts automatically with precise location permission. For"
                            + " reboot recovery, allow location all the time, enable Background"
                            + " autostart in HyperOS, and set Battery → No restrictions. Force-stop"
                            + " requires reopening the app.")
                    .setPositiveButton(
                        "App settings",
                        (d, w) ->
                            startActivity(
                                new Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Close", null)
                    .show()));
    for (Runnable r : cardUpdates) r.run();
  }

  private void infoCard(String name, String tag, String detail) {
    LinearLayout card = card();
    card.addView(text(tag, 10, TEAL));
    TextView title = text(name, 18, INK);
    title.setTypeface(null, Typeface.BOLD);
    card.addView(title);
    card.addView(text(detail, 12, MUTED));
  }

  private LinearLayout card() {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(0, dp(9), 0, dp(12));
    card.setBackground(bg(Color.WHITE, 16));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, dp(10), 0, dp(4));
    downloadCards.addView(card, params);
    return card;
  }

  private void packCard(MapPacks.Pack p) {
    LinearLayout card = card();
    card.addView(
        text(
            p.key().equals("mapsforge")
                ? "OFFLINE MAP  /  INDIA"
                : "OPTIONAL SOURCE DATA  /  INDIA",
            10,
            TEAL));
    TextView name = text(p.name(), 19, INK);
    name.setTypeface(null, Typeface.BOLD);
    card.addView(name);
    card.addView(text(p.description(), 12, MUTED));
    TextView state = text("Checking phone storage…", 13, INK);
    card.addView(state);
    ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setProgressTintList(android.content.res.ColorStateList.valueOf(TEAL));
    LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, dp(8));
    bar.setMargins(dp(16), dp(4), dp(16), dp(4));
    card.addView(progress, bar);
    TextView bytes = text("Estimated " + MapPacks.size(p.estimatedBytes()), 12, MUTED);
    card.addView(bytes);
    LinearLayout actions = new LinearLayout(this);
    Button action =
        button(
            "Download",
            () -> {
              try {
                packs.download(p);
                toast("Queued on phone · Wi-Fi or cellular");
                for (Runnable r : cardUpdates) r.run();
              } catch (Exception e) {
                toast(e.getMessage());
              }
            });
    Button cancel =
        button(
            "Cancel",
            () -> {
              packs.cancel(p);
              for (Runnable r : cardUpdates) r.run();
            });
    actions.addView(action, new LinearLayout.LayoutParams(0, dp(48), 1));
    actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1));
    card.addView(actions);
    final boolean[] checking = {false};
    cardUpdates.add(
        () -> {
          if (checking[0]) return;
          checking[0] = true;
          io.execute(
              () -> {
                MapPacks.State s = packs.state(p);
                runOnUiThread(
                    () -> {
                      checking[0] = false;
                      if (!downloads) return;
                      state.setText(s.status());
                      progress.setMax(1000);
                      progress.setProgress(
                          s.installed() && !s.pending()
                              ? 1000
                              : (int) Math.min(1000, s.bytes() * 1000 / Math.max(1, s.total())));
                      progress.setIndeterminate(s.pending() && s.bytes() == 0);
                      bytes.setText(
                          MapPacks.size(s.bytes())
                              + " / "
                              + MapPacks.size(s.total())
                              + (s.pending()
                                  ? " · "
                                      + Math.min(100, s.bytes() * 100 / Math.max(1, s.total()))
                                      + "%"
                                  : " · phone storage"));
                      action.setEnabled(!s.pending());
                      action.setText(
                          s.installed()
                              ? "Update manually"
                              : s.status().startsWith("Failed") ? "Retry download" : "Download");
                      cancel.setEnabled(s.pending());
                    });
              });
        });
  }

  private void backupNow() {
    if (!new TrailBackup(this).configured()) {
      toast("Choose a backup location first");
      return;
    }
    toast("Saving trail backup…");
    io.execute(
        () -> {
          try {
            new TrailBackup(this).save(db, true);
            runOnUiThread(
                () -> {
                  toast("Backup written · Drive handles upload/sync");
                  if (downloads) showDownloads();
                });
          } catch (Exception e) {
            runOnUiThread(() -> toast("Backup failed: " + e.getMessage()));
          }
        });
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    if (request == 22) {
      try {
        new TrailBackup(this).configure(uri, data.getFlags());
        backupNow();
      } catch (Exception e) {
        toast("Cannot retain backup access: " + e.getMessage());
      }
      return;
    }
    if (request == 23) {
      toast("Restoring trail snapshot…");
      io.execute(
          () -> {
            try {
              new TrailBackup(this).restore(db, uri);
              runOnUiThread(
                  () -> {
                    TrackingStartup.start(this, false);
                    provider = getPreferences(0).getInt("provider", 0);
                    offlineOnly = getPreferences(0).getBoolean("offline", false);
                    lastFixId = -1;
                    lastStopSignature = 0;
                    StopGeocoder.backfill(this);
                    toast(
                        "Trail history restored. Re-select the backup target to resume automatic"
                            + " backups.");
                    if (downloads) showDownloads();
                  });
            } catch (Exception e) {
              runOnUiThread(() -> toast("Restore failed: " + e.getMessage()));
            }
          });
      return;
    }
    toast(request == 20 ? "Importing and validating India map…" : "Exporting GPS logs…");
    io.execute(
        () -> {
          try {
            if (request == 20) {
              try (InputStream in = getContentResolver().openInputStream(uri)) {
                packs.install(in);
              }
            } else if (request == 21) {
              try (Writer w =
                  new OutputStreamWriter(
                      getContentResolver().openOutputStream(uri),
                      java.nio.charset.StandardCharsets.UTF_8)) {
                db.export(w);
              }
            }
            runOnUiThread(
                () -> {
                  toast(request == 20 ? "India pack imported" : "GeoJSON export saved");
                  if (downloads) for (Runnable r : cardUpdates) r.run();
                });
          } catch (Exception e) {
            db.log("File operation", "FAILED", e.toString());
            runOnUiThread(() -> toast(e.getMessage()));
          }
        });
  }

  private Location navigationOrigin() {
    long now = System.currentTimeMillis();
    Location best = preview;
    TrailDb.Fix fix = db.latestFix();
    if (fix != null && (best == null || fix.time() > best.getTime())) {
      best = new Location("trail");
      best.setLatitude(fix.lat());
      best.setLongitude(fix.lon());
      best.setTime(fix.time());
      best.setAccuracy(fix.accuracy());
      best.setSpeed(fix.speed());
    }
    if (best == null
        || !best.hasAccuracy()
        || best.getAccuracy() > 50
        || now - best.getTime() > 30000
        || now - best.getTime() < -10000) return null;
    return best;
  }

  private void navigationChanged() {
    routeFitRevision = -1;
    if ("ACTIVE".equals(NavigationManager.get(this).state().mode())) {
      navigationFollow = true;
      if (history) {
        history = false;
        showMap();
      }
    }
    navigationTick();
    lastFixId = -1;
    refresh();
  }

  private void navigationTick() {
    if (downloads || map == null) return;
    navigation.refresh(map);
    NavigationManager.State state = NavigationManager.get(this).state();
    if (!history && state.route() != null && routeFitRevision != state.revision()) {
      routeFitRevision = state.revision();
      if ("PREVIEW".equals(state.mode())) navigation.fit(map);
    }
    if (!history && "ACTIVE".equals(state.mode()) && navigationFollow) {
      Location fix = navigationOrigin();
      if (fix != null) {
        map.getController().setCenter(new GeoPoint(fix.getLatitude(), fix.getLongitude()));
        if (map.getZoomLevelDouble() < 14) map.getController().setZoom(16.0);
      }
    }
  }

  private String clock(long t) {
    return new SimpleDateFormat("dd MMM, HH:mm:ss", Locale.getDefault()).format(new Date(t));
  }

  private String duration(long ms) {
    long secs = Math.max(0, ms / 1000);
    return secs < 60
        ? secs + "s"
        : secs < 3600
            ? secs / 60 + "m " + secs % 60 + "s"
            : secs / 3600 + "h " + secs / 60 % 60 + "m";
  }

  private void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  @Override
  protected void onResume() {
    super.onResume();
    TrackingStartup.start(this, false);
    StopGeocoder.backfill(this);
    if (map != null) map.onResume();
    updates.resume();
    handler.post(ticker);
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(ticker);
    if (map != null) map.onPause();
    super.onPause();
  }

  @Override
  public void onBackPressed() {
    if (downloads) {
      showMap();
      return;
    }
    super.onBackPressed();
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacks(ticker);
    if (map != null) map.onDetach();
    io.shutdown();
    heritageIo.shutdownNow();
    navigation.close();
    super.onDestroy();
  }
}
