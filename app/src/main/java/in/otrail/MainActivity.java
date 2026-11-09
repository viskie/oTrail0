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
  private static final int INK = AppStyle.INK,
      TEAL = AppStyle.ACCENT_TEXT,
      MUTED = AppStyle.MUTED,
      PAPER = AppStyle.BACKGROUND;
  private TrailDb db;
  private NavigationControls navigation;
  private UpdateControls updates;
  private boolean navigationFollow = true;
  private boolean wtfEnabled,wtfRecentering;
  private float wtfCourse=Float.NaN;
  private GeoPoint wtfTarget;
  private WtfButton wtfButton;
  private WtfNorthView wtfNorth;
  private android.animation.ValueAnimator wtfTurn;
  private long routeFitRevision = -1;
  private MapPacks packs;
  private MapView map;
  private final CityPlaces cityPlaces=new CityPlaces();
  private StopLabelsOverlay stopLabels;
  private FrameLayout mapFrame;
  private LinearLayout root, body, downloadCards;
  private TextView status, stats, mode, packSummary;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final ExecutorService heritageIo = Executors.newSingleThreadExecutor();
  private RoutingControls routingControls;
  private RoadControls roadControls;
  private LiveLocationContext liveContext;
  private GpsBubble liveBubble;
  private MapMenu mapMenu;
  private TextView lookupNotes;
  private GeoPoint liveBubblePoint;
  private TextView roadInfo;
  private DriveMetrics.Result driveMetrics;
  private ModuleHost moduleHost;
  private boolean camVisible;
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
    roadControls = new RoadControls(this);
    liveContext=new LiveLocationContext(this,this::refreshLiveBubble);
    packs = new MapPacks(this, db);
    navigation =
        new NavigationControls(this, this::navigationOrigin, this::locate, this::navigationChanged);
    navigation.roadTap = point -> roadControls.tapped(point.getLatitude(), point.getLongitude(), offlineOnly);
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
    wtfEnabled=getPreferences(0).getBoolean("wtf_mode",false);
    wtfCourse=getPreferences(0).getFloat("wtf_course",Float.NaN);
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
    AppStyle.text(v);
    v.setTextSize(UiConfig.textSize(size));
    v.setTextColor(color);
    v.setPadding(dp(10), dp(3), dp(10), dp(3));
    return v;
  }

  private Button button(String s, Runnable click) {
    Button b = new Button(this);
    b.setText(s);
    b.setTextSize(12);
    b.setAllCaps(false);
    AppStyle.button(b, s.equals("Navigate") ? 1 : s.equals("Settings") ? 2 : 0);
    b.setOnClickListener(v -> click.run());
    return b;
  }

  private void base(String subtitle) {
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackground(AppStyle.canvas());
    root.setFitsSystemWindows(false);
    root.setPadding(dp(4), dp(2), dp(4), dp(5));
    root.setOnApplyWindowInsetsListener((v, insets) -> {
      int keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom;
      v.setPadding(dp(4), dp(2), dp(4), Math.max(dp(5), keyboard));
      return insets;
    });
    setContentView(root);
    LinearLayout head = new LinearLayout(this);
    head.setGravity(Gravity.CENTER_VERTICAL);
    head.setPadding(dp(4), dp(8), dp(8), dp(8));
    head.setBackgroundColor(INK);
    TextView title = text(UiConfig.text("otrail"), 27, Color.WHITE);
    title.setTypeface(Typeface.create(UiConfig.HEADING_FONT, UiConfig.HEADING_FONT_STYLE));
    head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    TextView region = text(UiConfig.text("india_in"), 12, UiConfig.REGION_LABEL_TEXT);
    head.addView(region);
    head.setVisibility(View.GONE);
    root.addView(head);
    TextView sub = text(subtitle, 12, MUTED);
    sub.setVisibility(View.GONE);
    root.addView(sub);
    body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
  }

  private void showMap() {
    if(liveBubble!=null){liveBubble.close();liveBubble=null;}liveBubblePoint=null;
    if(wtfTurn!=null){wtfTurn.cancel();wtfTurn=null;}wtfTarget=null;
    if(moduleHost!=null){moduleHost.close();moduleHost=null;}
    if (map != null) {
      org.osmdroid.views.overlay.infowindow.InfoWindow.closeAllInfoWindowsOn(map);
      MapView old = map;
      map = null;
      old.setDestroyMode(false);
      old.onPause();
      if (old.getParent() instanceof android.view.ViewGroup)
        ((android.view.ViewGroup)old.getParent()).removeView(old);
      old.onDetach();
    }
    fitTrail = true;
    routeFitRevision = -1;
    lastHeritageSignature = 0;
    openMarkerKey = null;
    visibleMarkers.clear();
    downloads = false;
    cardUpdates.clear();
    base(UiConfig.text("your_road_your_record_personal_navigation"));
    LinearLayout row = new LinearLayout(this);
    mode = text(history ? UiConfig.text("history") : UiConfig.text("live_trail"), 13, TEAL);
    mode.setTypeface(Typeface.create(UiConfig.HEADING_FONT, UiConfig.HEADING_FONT_STYLE));
    mode.setTextColor(AppStyle.HEADING);
    mode.setLetterSpacing(.06f);
    AppStyle.decoratePanel(mode,AppStyle.HEADING);
    row.addView(mode, new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(button(UiConfig.text("navigate"), () -> navigation.search()));
    camVisible=new ModuleStore(this).enabled("mod2");

    AppStyle.spaceButtons(row);
    body.addView(row);
    LinearLayout tools = new LinearLayout(this);
    AppStyle.decoratePanel(tools,AppStyle.CYAN);
    String[] names = {UiConfig.text("osm_roads"), UiConfig.text("esri_satellite"), UiConfig.text("india_offline")};
    Spinner selector = new Spinner(this);
    selector.setAdapter(
        AppStyle.choices(this, java.util.Arrays.asList(names),UiConfig.MAP_SELECTOR_SIZE_SP));
    selector.setSelection(provider);
    tools.addView(selector, new LinearLayout.LayoutParams(0, dp(40), 1));
    Switch network = new Switch(this);
    network.setText(UiConfig.text("offline"));
    network.setTextSize(UiConfig.BODY_SIZE_SP);
    AppStyle.nativeTextTree(network);
    AppStyle.toggle(network);
    network.setMinHeight(dp(44));
    network.setChecked(offlineOnly);
    tools.addView(network);
    AppStyle.spaceButtons(tools);
    body.addView(tools);
    roadInfo = text(UiConfig.text("road_landmarks_cache_first"), 12, MUTED);
    AppStyle.decoratePanel(roadInfo,AppStyle.ORANGE);
    roadInfo.setSingleLine(true);
    roadInfo.setEllipsize(android.text.TextUtils.TruncateAt.END);
    roadInfo.setVisibility(View.GONE);
    body.addView(roadInfo);
    body.addView(navigation.panel());
    map = new MapView(this);
    stopLabels=new StopLabelsOverlay();
    map.setDestroyMode(false); // Lifecycle is owned here; avoid a second teardown on window detach.
    roadControls.attach(roadInfo, map);
    map.setMultiTouchControls(true);
    map.setOnTouchListener(
        (v, event) -> {
          if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
              || event.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) {
            if(!wtfActive()){navigationFollow = false;follow=false;}
          }
          return false;
        });
    map.getController().setZoom(wtfActive()?getPreferences(0).getFloat("wtf_zoom",16f):5.0);
    map.getController().setCenter(new GeoPoint(22.5, 79));
    map.setMinZoomLevel(4.0);
    map.setScrollableAreaLimitDouble(new BoundingBox(38, 98, 6, 68));
    mapFrame = new FrameLayout(this);
    mapFrame.addView(map, new FrameLayout.LayoutParams(-1, -1));
    LocateLogoButton locateButton=new LocateLogoButton(this,this::locate);
    locateButton.setContentDescription(UiConfig.text("locate_me_and_follow_gps"));
    LinearLayout mapControls=new LinearLayout(this);mapControls.setGravity(Gravity.CENTER_VERTICAL);
    wtfButton=new WtfButton(this,wtfActive(),this::toggleWtf);
    LinearLayout.LayoutParams wtfPosition=new LinearLayout.LayoutParams(dp(UiConfig.LOCATE_SIZE_DP),dp(UiConfig.LOCATE_SIZE_DP));wtfPosition.setMargins(0,0,dp(UiConfig.MAP_ICON_GAP_DP),0);mapControls.addView(wtfButton,wtfPosition);
    if(camVisible){
      CameraLogoButton camera=new CameraLogoButton(this,()->openCam("camera",-1));
      LinearLayout.LayoutParams cameraPosition=new LinearLayout.LayoutParams(dp(UiConfig.LOCATE_SIZE_DP),dp(UiConfig.LOCATE_SIZE_DP));cameraPosition.setMargins(0,0,dp(UiConfig.MAP_ICON_GAP_DP),0);mapControls.addView(camera,cameraPosition);
    }
    mapControls.addView(locateButton,new LinearLayout.LayoutParams(dp(UiConfig.LOCATE_SIZE_DP),dp(UiConfig.LOCATE_SIZE_DP)));
    HorizontalScrollView mapIconBar=new HorizontalScrollView(this);mapIconBar.setHorizontalScrollBarEnabled(false);mapIconBar.setFillViewport(true);mapIconBar.addView(mapControls,new HorizontalScrollView.LayoutParams(-2,-2));mapControls.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
    FrameLayout.LayoutParams controlPosition=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END);controlPosition.setMargins(dp(76),dp(10),dp(10),dp(8));mapFrame.addView(mapIconBar,controlPosition);
    mapIconBar.post(()->mapIconBar.fullScroll(View.FOCUS_RIGHT));
    wtfNorth=new WtfNorthView(this);wtfNorth.setVisibility(wtfActive()?View.VISIBLE:View.GONE);
    FrameLayout.LayoutParams northPosition=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.BOTTOM|Gravity.START);northPosition.setMargins(dp(10),0,0,dp(10));mapFrame.addView(wtfNorth,northPosition);
    map.addMapListener(new MapListener(){public boolean onScroll(ScrollEvent event){if(wtfActive()&&wtfTarget!=null&&!wtfRecentering){GeoPoint center=(GeoPoint)map.getMapCenter();if(Math.abs(center.getLatitude()-wtfTarget.getLatitude())>1e-7||Math.abs(center.getLongitude()-wtfTarget.getLongitude())>1e-7)wtfCenter();}return false;}public boolean onZoom(ZoomEvent event){if(wtfActive())map.post(()->{if(wtfActive())wtfCenter();});return false;}});
    if(wtfActive()&&Float.isFinite(wtfCourse))map.setMapOrientation(WtfCamera.rotation(wtfCourse));
    liveBubble=new GpsBubble(this,map);mapFrame.addView(liveBubble,new FrameLayout.LayoutParams(dp(UiConfig.LIVE_BUBBLE_WIDTH_DP),-2));
    mapMenu=new MapMenu(this,history,this::trackerStatus,this::oldPlaces,this::showStops,this::history,this::showDownloads);mapFrame.addView(mapMenu,new FrameLayout.LayoutParams(-1,-1));
    body.addView(mapFrame, new LinearLayout.LayoutParams(-1, 0, 1));
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
                    roadControls.tapped(p.getLatitude(), p.getLongitude(), offlineOnly);
                    return false;
                  }

                  public boolean longPressHelper(GeoPoint p) {
                    destination(p);
                    return true;
                  }
                }));
    TextView tip = text(UiConfig.text("hold_the_map_to_navigate_tap_stops_for_arrival_duration"), 11, MUTED);
    tip.setVisibility(View.GONE);
    body.addView(tip);
    status = text(UiConfig.text("ready_when_you_are"), UiConfig.STATUS_SIZE_SP, INK);
    status.setTypeface(android.graphics.Typeface.create(UiConfig.STATUS_FONT, UiConfig.STATUS_FONT_STYLE));
    status.setTextColor(UiConfig.STATUS_TEXT_COLOR);
    status.setVisibility(history?View.VISIBLE:View.GONE);
    AppStyle.decoratePanel(status,AppStyle.CYAN);
    GradientDrawable statusBackground=bg(UiConfig.STATUS_BACKGROUND,9);
    statusBackground.setStroke(dp(1),UiConfig.STATUS_TEXT_COLOR);
    status.setBackground(statusBackground);
    status.setMaxLines(2);
    status.setEllipsize(android.text.TextUtils.TruncateAt.END);
    body.addView(status);
    lookupNotes=text("",UiConfig.BODY_SIZE_SP,UiConfig.STATUS_TEXT_COLOR);GradientDrawable notesBackground=bg(UiConfig.STATUS_BACKGROUND,9);notesBackground.setStroke(dp(1),UiConfig.STATUS_TEXT_COLOR);lookupNotes.setBackground(notesBackground);lookupNotes.setVisibility(View.GONE);body.addView(lookupNotes);
    moduleHost=new ModuleHost(this);body.addView(moduleHost.view());
    stats = text(UiConfig.text("always_on_gps_every_5_seconds"), 12, UiConfig.TOTAL_STATS_TEXT_COLOR);
    stats.setTypeface(android.graphics.Typeface.create(UiConfig.BODY_FONT,UiConfig.TOTAL_STATS_FONT_STYLE));
    stats.setMaxLines(2);
    AppStyle.decoratePanel(stats,AppStyle.CYAN);
    GradientDrawable totalBackground=bg(UiConfig.TOTAL_STATS_BACKGROUND,9);
    totalBackground.setStroke(dp(1),UiConfig.TOTAL_STATS_TEXT_COLOR);
    stats.setBackground(totalBackground);
    body.addView(stats);
    TextView legend =
        text(UiConfig.text("green_ready_blue_moving_turquoise_stopped_cyan_history"), 10, MUTED);
    legend.setVisibility(View.GONE);
    body.addView(legend);
    if (getResources().getConfiguration().orientation
        == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
      root.getChildAt(0).setVisibility(View.GONE);
      root.getChildAt(1).setVisibility(View.GONE);
      ArrayList<View> controls = new ArrayList<>();
      for (int i = 0; i < body.getChildCount(); i++) {
        View child = body.getChildAt(i);
        if (child != mapFrame) controls.add(child);
      }
      body.removeAllViews();
      body.setOrientation(LinearLayout.HORIZONTAL);
      body.addView(mapFrame, new LinearLayout.LayoutParams(0, -1, 1));
      LinearLayout sidebar=new LinearLayout(this);
      sidebar.setOrientation(LinearLayout.VERTICAL);
      sidebar.setPadding(dp(UiConfig.SIDEBAR_INSET_DP),0,dp(UiConfig.SIDEBAR_INSET_DP),dp(UiConfig.SIDEBAR_INSET_DP));
      ScrollView panel=new ScrollView(this);
      LinearLayout stack=new LinearLayout(this);
      stack.setOrientation(LinearLayout.VERTICAL);
      stack.setPadding(0,dp(UiConfig.PANEL_GAP_DP),0,dp(UiConfig.PANEL_GAP_DP));
      for(View child:controls) {
        if(child==stats)continue;
        LinearLayout.LayoutParams space=new LinearLayout.LayoutParams(-1,-2);
        space.setMargins(0,dp(UiConfig.PANEL_GAP_DP)/2,0,dp(UiConfig.PANEL_GAP_DP)/2);
        stack.addView(child,space);
      }
      panel.addView(stack);
      sidebar.addView(panel,new LinearLayout.LayoutParams(-1,0,1));
      LinearLayout.LayoutParams footer=new LinearLayout.LayoutParams(-1,-2);
      footer.setMargins(0,dp(UiConfig.PANEL_GAP_DP),0,0);
      sidebar.addView(stats,footer);
      body.addView(sidebar,new LinearLayout.LayoutParams(dp(UiConfig.SIDEBAR_WIDTH_DP),-1));
    }
    lastFixId = -1;
    lastStopSignature = 0;
    refresh();
  }

  private void applyProvider() {
    if (map == null) { return; }
    try {
      if (provider == 2) {
        if (!packs.active().exists()) {
          map.setUseDataConnection(false);
          toast(UiConfig.text("download_the_india_pack_in_maps_downloads_first"));
          return;
        }
        // The bridge renders fixed 256px tiles; MapView already applies screen density.
        // Applying Mapsforge's device density as well makes city captions enormous.
        org.mapsforge.map.model.DisplayModel.setDeviceScaleFactor(1f);
        org.mapsforge.map.model.DisplayModel.textScale=UiConfig.OFFLINE_MAP_TEXT_SCALE;
        MapsForgeTileSource source =
            MapsForgeTileSource.createFromFiles(
                new File[] {packs.active()}, new OfflineMapTheme(), "India-render-v4-" + packs.active().lastModified() + "-text-" + UiConfig.OFFLINE_MAP_TEXT_SCALE + "-city-" + OfflineMapTheme.cacheKey());
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
                    UiConfig.text("tiles_esri_maxar_earthstar_geographics_and_contributors")) {
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
      CopyrightOverlay attribution=new CopyrightOverlay(this);
      attribution.setTextColor(UiConfig.INK);
      map.getOverlays().add(attribution);
      map.invalidate();
    } catch (Exception e) {
      toast(UiConfig.text("map_unavailable") + e.getMessage());
    }
  }

  private void refresh() {
    if (busy || map == null) return;
    busy = true;
    final boolean viewHistory = history;
    final long chosen = selectedTrip;
    final BoundingBox cityBox=map.getBoundingBox();
    final int cityZoom=(int)map.getZoomLevelDouble();
    io.execute(
        () -> {
          try {
            long active = db.activeTrip();
            long trip = viewHistory ? chosen : -1;
            List<TrailDb.Fix> fixes = db.fixes(trip);
            List<TrailDb.Stop> stops = db.stops(trip);
            Float course=!viewHistory&&!fixes.isEmpty()?db.course(fixes.get(fixes.size()-1).id()):null;
            List<TrailSegments.Sample> samples = new ArrayList<>();
            for (TrailDb.Fix f : fixes)
              samples.add(new TrailSegments.Sample(f.id(), f.trip(), f.time(), f.lat(), f.lon()));
            List<TrailSegments.Segment> segments = TrailSegments.build(samples, 6000);
            List<TrailPalette.Stroke> colored = TrailPalette.build(segments);
            DriveMetrics.Result metrics = db.driveMetrics();
            double viewMeters = metrics.totalMeters();
            if (trip >= 0) {
              List<DriveMetrics.Point> viewPoints=new ArrayList<>();
              for(TrailDb.Fix f:fixes)viewPoints.add(new DriveMetrics.Point(f.time(),f.lat(),f.lon(),f.accuracy(),f.speed()));
              List<DriveMetrics.Stop> viewStops=new ArrayList<>();
              for(TrailDb.Stop stop:stops)viewStops.add(new DriveMetrics.Stop(stop.id(),stop.arrival(),stop.departure(),stop.observed()));
              viewMeters=DriveMetrics.calculate(viewPoints,viewStops).totalMeters();
            }
            final double trailMeters=viewMeters;
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
            List<CityPlaces.Place> visibleCities=cityPlaces.read(packs.active(),cityBox,cityZoom);
            runOnUiThread(
                () -> {
                  busy = false;
                  if (downloads || history != viewHistory || viewHistory && selectedTrip != chosen)
                    return;
                  if (map == null || isFinishing() || isDestroyed()) return;
                  try { driveMetrics = metrics; stopLabels.update(stops,metrics,visibleCities); render(active, trip, fixes, stops, segments, places, colored, trailMeters,course); }
                  catch (RuntimeException e) {
                    CrashReports.save(this, "Trail render · provider=" + provider + UiConfig.text("history_2") + history, e);
                    android.util.Log.e("oTraiL", "Trail render failed", e);
                    io.execute(() -> db.log(UiConfig.text("history_display"), "ERROR", android.util.Log.getStackTraceString(e)));
                    toast(UiConfig.text("trail_display_could_not_finish_your_recorded_data_is_safe"));
                  }
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
      List<HistoricPlaces.Place> places,
      List<TrailPalette.Stroke> colored,
      double trailMeters, Float course) {
    historicPlaces = places;
    if (fixes.isEmpty()) {
      status.setVisibility(View.VISIBLE);
      if (lastHeritageSignature != places.hashCode()) {
        lastHeritageSignature = places.hashCode();
        org.osmdroid.views.overlay.infowindow.InfoWindow.closeAllInfoWindowsOn(map);
        openMarkerKey = null;
        drawHistoricPlaces();
      }
      status.setText(
          active == -1
              ? UiConfig.text("setup_needed_grant_location_access")
              : UiConfig.text("always_recording_waiting_for_gps"));
      stats.setText(UiConfig.text("gps_needs_a_clear_view_of_the_sky_stops_logged_after_2_min"));
      return;
    }
    TrailDb.Fix last = fixes.get(fixes.size() - 1);
    long age = System.currentTimeMillis() - last.time();
    boolean stale = age > 30000;
    android.content.SharedPreferences signal=getSharedPreferences("tracking_signal",0);
    boolean liveSignal=!history&&signal.getLong("fix_ms",0)==last.time();
    long heldArrival=liveSignal?signal.getLong("stop_arrival_ms",0):0;
    long heldId=liveSignal?signal.getLong("stop_id",-1):-1;
    TrailDb.Stop current=stops.isEmpty()?null:stops.get(stops.size()-1);
    if(heldId>0)for(TrailDb.Stop stop:stops)if(stop.id()==heldId){current=stop;break;}
    boolean stopped=heldArrival>0||current!=null&&current.departure()==0;
    boolean uncertain=liveSignal&&signal.getBoolean("uncertain",false)||last.accuracy()>30;
    boolean recorded=history||active==-1;
    int color=recorded?UiConfig.HISTORY_GPS_DOT:stale||uncertain?UiConfig.STALE_GPS_DOT
        :stopped?UiConfig.STOPPED_GPS_DOT:last.speed()>1.5?UiConfig.MOVING_GPS_DOT:UiConfig.READY_GPS_DOT;
    long arrived=heldArrival>0?heldArrival:current==null?0:current.arrival();
    String state=recorded?UiConfig.text("history")
        :stopped?UiConfig.text("stopped")+duration(Math.max(0,System.currentTimeMillis()-arrived))
        :stale?UiConfig.text("gps_signal_stale")
        :uncertain?UiConfig.text("gps_uncertain")
        :last.speed()>1.5?UiConfig.text("on_the_move"):UiConfig.text("recording");
    String drive=driveMetrics==null?"":String.format(Locale.US,UiConfig.text("drive_2f_km"),driveMetrics.current().meters()/1000);
    String speed=stopped?String.format(Locale.US,UiConfig.text("0f_m_hr"),0.0)
        :stale||uncertain?UiConfig.text("speed_unknown_m_hr")
        :String.format(Locale.US,UiConfig.text("0f_m_hr"),last.speed()*3600);
    String quality=stopped&&(uncertain||stale)?"\n"+UiConfig.text("stop_signal_uncertain"):"";
    android.text.SpannableString statusText=new android.text.SpannableString(state+speed+(!recorded&&!stopped?drive:"")+quality);
    statusText.setSpan(new android.text.style.ForegroundColorSpan(UiConfig.STATUS_SPEED_COLOR),state.length(),state.length()+speed.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    status.setVisibility(history?View.VISIBLE:View.GONE);
    status.setText(statusText);
    if(!history){liveBubblePoint=stopped&&uncertain&&current!=null?new GeoPoint(current.lat(),current.lon()):new GeoPoint(last.lat(),last.lon());liveContext.update(liveBubblePoint.getLatitude(),liveBubblePoint.getLongitude(),roadControls.roadName());refreshLiveBubble();}
    else if(liveBubble!=null)liveBubble.setVisibility(View.GONE);
    stopLabels.liveBubble(!history);
    roadControls.update(last, fixes.size()>1?fixes.get(fixes.size()-2):null, !stale && !uncertain && !stopped && last.speed()>1.5, offlineOnly, history);
    stats.setText(
        (driveMetrics == null ? "" : String.format(Locale.US, UiConfig.text("total_2f_km"), driveMetrics.totalMeters()/1000))
            + (trip < 0 ? UiConfig.text("full_trail") : String.format(Locale.US,UiConfig.text("trail_2f_km"),trailMeters/1000))
            + clock(fixes.get(0).time())
            + " · "
            + fixes.size()
            + UiConfig.text("fixes")
            + stops.size()
            + UiConfig.text("stops_2")
            + Math.round(last.accuracy())
            + UiConfig.text("m")
            + (stale ? UiConfig.text("last_fix") + duration(age) + UiConfig.text("ago") : clock(last.time())));
    if(moduleHost!=null)moduleHost.update(new TrailModule.Snapshot(System.currentTimeMillis(),last.time(),last.lat(),last.lon(),last.accuracy(),driveMetrics==null?0:driveMetrics.totalMeters(),stops.size(),history));
    if(wtfActive()){
      wtfTarget=stopped&&uncertain&&current!=null?new GeoPoint(current.lat(),current.lon()):new GeoPoint(last.lat(),last.lon());
      if(!uncertain&&WtfCamera.reliable(System.currentTimeMillis(),last.time(),last.accuracy(),last.speed(),stopped)){
        Float reliableCourse=course;
        if(reliableCourse==null||!Float.isFinite(reliableCourse))for(int i=fixes.size()-2;i>=0;i--){TrailDb.Fix previous=fixes.get(i);if(last.time()-previous.time()>60000)break;
          reliableCourse=WtfCamera.displacementCourse(last.time(),previous.time(),last.lat(),last.lon(),previous.lat(),previous.lon(),last.accuracy(),previous.accuracy());if(reliableCourse!=null)break;
        }
        if(reliableCourse!=null&&Float.isFinite(reliableCourse)&&(!Float.isFinite(wtfCourse)||Math.abs(WtfCamera.shortestTurn(wtfCourse,reliableCourse))>=1)){
          wtfCourse=WtfCamera.normalize(reliableCourse);getPreferences(0).edit().putFloat("wtf_course",wtfCourse).apply();wtfRotate(WtfCamera.rotation(wtfCourse));
        }
      }
      fitTrail=false;follow=false;wtfCenter();
    }
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
                        || (!((Marker) o).getId().startsWith("navigation:") && !((Marker) o).getId().startsWith("roadpoi:")))
                    && (!(o instanceof Polyline)
                        || ((Polyline) o).getId() == null
                        || !((Polyline) o).getId().startsWith("navigation:")));
    long liveRunStart=driveMetrics==null?last.time():driveMetrics.current().start();
    if(stopped&&current!=null&&driveMetrics!=null){
      DriveMetrics.Leg arrivedLeg=driveMetrics.stops().get(current.id());
      if(arrivedLeg!=null)liveRunStart=arrivedLeg.start();
    }
    for (TrailPalette.Stroke stroke : recorded?colored:TrailPalette.highlightCurrent(colored,liveRunStart)) {
      ArrayList<GeoPoint> points = new ArrayList<>();
      for (TrailSegments.Sample p : stroke.points()) points.add(new GeoPoint(p.lat(), p.lon()));
      line(points, stroke.color());
    }
    for (TrailDb.Stop s : stops) {
      Marker m = new Marker(map);
      m.setPosition(new GeoPoint(s.lat(), s.lon()));
      m.setAnchor(.5f, .5f);
      m.setIcon(dot(StopLabelsOverlay.longStop(s)?UiConfig.LONG_STOP_COLOR:UiConfig.SAVED_STOP_DOT,StopLabelsOverlay.longStop(s)?18:14));
      String place =
          s.placeName() == null || s.placeName().isBlank()
              ? UiConfig.text("location_not_yet_identified")
              : s.placeName();
      DriveMetrics.Leg stopLeg=driveMetrics==null?null:driveMetrics.stops().get(s.id());
      m.setTitle(place+(stopLeg==null?"":String.format(Locale.US," · %.2f km",stopLeg.meters()/1000)));
      StringBuilder details = new StringBuilder();
      if (s.locality() != null && !s.locality().equals(place))
        details.append(s.locality()).append("\n");
      addressLine(details, "Road", s.road());
      addressLine(details, "District", s.district());
      addressLine(details, "State", s.state());
      addressLine(details, "Country", s.country());
      addressLine(details, "PIN", s.postalCode());
      details
          .append(UiConfig.text("arrived"))
          .append(clock(s.arrival()))
          .append(UiConfig.text("observed_duration"))
          .append(duration(s.observed() - s.arrival()))
          .append(s.departure() == 0 ? UiConfig.text("last_observation") : UiConfig.text("departed"))
          .append(clock(s.departure() == 0 ? s.observed() : s.departure()));
      details.append(driveDetails(s.id()));
      details.append(String.format(Locale.US, UiConfig.text("gps_6f_6f"), s.lat(), s.lon()));
      if (!"RESOLVED".equals(s.geocodeStatus())) {
        details
            .append(UiConfig.text("place_lookup"))
            .append(
                "FAILED".equals(s.geocodeStatus()) ? UiConfig.text("unavailable_retry_after_24h") : "pending");
      }
      m.setSnippet(android.text.TextUtils.htmlEncode(details.toString()).replace("\n", "<br/>"));
      registerMarker("stop:" + s.id(), m);
    }
    Marker dot = new Marker(map);
    dot.setPosition(stopped&&uncertain&&current!=null?new GeoPoint(current.lat(),current.lon()):new GeoPoint(last.lat(), last.lon()));
    dot.setAnchor(.5f, .5f);
    LayerDrawable liveIcon=dot(color,22);if(!recorded&&!stale&&!uncertain&&last.accuracy()>0&&last.accuracy()<=UiConfig.GPS_SMILE_MAX_ACCURACY_M)liveIcon.addLayer(new HappyGpsFace());dot.setIcon(liveIcon);
    dot.setTitle(state);
    dot.setSnippet(
        String.format(
            Locale.US,
            UiConfig.text("6f_6f_1f_km_h_0f_m_s"),
            last.lat(),
            last.lon(),
            last.speed() * 3.6,
            last.accuracy(),
            clock(last.time())));
    registerMarker("live", dot);
    map.getOverlays().remove(stopLabels);map.getOverlays().add(stopLabels);
    drawHistoricPlaces();
    Marker reopen = visibleMarkers.get(openMarkerKey);
    if (reopen != null) MarkerPopups.openOnly(map,reopen);
    if (NavigationManager.get(this).state().route() != null && !history) fitTrail = false;
    if (wtfActive()) { fitTrail=false;wtfTarget=dot.getPosition();wtfCenter(); } else if (fitTrail) {
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
        if (north - south < .001) { north += .0005; south -= .0005; }
        if (east - west < .001) { east += .0005; west -= .0005; }
        BoundingBox bounds = new BoundingBox(north, east, south, west);
        MapView view = map;
        fitBounds(view, bounds);
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
          if (key.startsWith("stop:")) {
            android.app.AlertDialog.Builder info=new AppDialogs(this).setTitle(m.getTitle())
                .setMessage(android.text.Html.fromHtml(m.getSnippet(), android.text.Html.FROM_HTML_MODE_LEGACY))
                .setPositiveButton(UiConfig.text("close"), null);
            if(new ModuleStore(this).enabled("mod2"))try{long stop=Long.parseLong(key.substring(5));info.setNeutralButton("Photos",(dialog,which)->openCam("gallery",stop));}catch(NumberFormatException ignored){}
            info.show();
            openMarkerKey = null;
          } else MarkerPopups.openOnly(view, m);
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
    p.setOnClickListener((poly, view, point) -> {
      roadControls.tapped(point.getLatitude(), point.getLongitude(), offlineOnly);
      return true;
    });
    p.getOutlinePaint().setColor(color);
    p.getOutlinePaint().setStrokeWidth(dp(4));
    android.graphics.Paint stroke = p.getOutlinePaint();
    stroke.setStyle(android.graphics.Paint.Style.STROKE);stroke.setStrokeCap(android.graphics.Paint.Cap.ROUND);stroke.setStrokeJoin(android.graphics.Paint.Join.ROUND);stroke.setAntiAlias(true);stroke.setPathEffect(null);
    android.graphics.Paint halo = new android.graphics.Paint(stroke);
    halo.setColor(UiConfig.TRAIL_OUTLINE); halo.setStrokeWidth(dp(6));
    p.getOutlinePaintLists().add(new org.osmdroid.views.overlay.advancedpolyline.MonochromaticPaintList(halo));
    p.getOutlinePaintLists().add(new org.osmdroid.views.overlay.advancedpolyline.MonochromaticPaintList(stroke));
    map.getOverlays().add(p);
  }

  private LayerDrawable dot(int color, int size) {
    GradientDrawable inner = bg(color, 99);
    inner.setShape(GradientDrawable.OVAL);
    inner.setStroke(dp(UiConfig.DOT_OUTLINE_WIDTH_DP), UiConfig.DOT_OUTLINE_COLOR);
    inner.setSize(dp(size), dp(size));
    return new LayerDrawable(new android.graphics.drawable.Drawable[] {inner});
  }

  private void trackerStatus() {
    new AppDialogs(this)
        .setTitle(UiConfig.text("always_on_recording"))
        .setMessage(
            UiConfig.text("gps_request_interval_5_seconds_recording_runs_independently_of_l")
                + (TrackingStartup.rebootReady(this)
                    ? UiConfig.text("reboot_location_permission_is_ready")
                    : UiConfig.text("setup_needed_grant_precise_location_and_select_allow_all_the_tim")
                        + UiConfig.text("recording_can_restart_after_reboot"))
                + "\n\n"
                + UiConfig.text("on_poco_enable_background_autostart_and_set_battery_no_restricti")
                + UiConfig.text("force_stop_prevents_automatic_restart_until_you_reopen_otrail"))
        .setPositiveButton(
            UiConfig.text("location_permissions"),
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
        .setNegativeButton(UiConfig.text("close"), null)
        .show();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] p, int[] results) {
    super.onRequestPermissionsResult(request, p, results);
    if (request == 1) {
      if (TrackingStartup.hasLocation(this)) {
        TrackingStartup.start(this, false);
        trackerStatus();
      } else toast(UiConfig.text("precise_location_is_required_for_automatic_gps_recording"));
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
    toast(UiConfig.text("finding_gps_location"));
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
          m.setIcon(dot(UiConfig.READY_GPS_DOT, 22));
          m.setTitle(UiConfig.text("current_gps_location"));
          m.setSnippet("±" + Math.round(l.getAccuracy()) + UiConfig.text("m_2"));
          registerMarker("live", m);
          map.getController().setZoom(16.0);
          if(wtfActive()){wtfTarget=m.getPosition();wtfCenter();}else map.getController().setCenter(m.getPosition());
        });
  }

  private void oldPlaces() {
    new AppDialogs(this)
        .setTitle(UiConfig.text("old_places_200_600_years_25_km"))
        .setItems(
            new String[] {
              UiConfig.text("find_refresh_nearby_places_osm"),
              UiConfig.text("show_cached_nearby_places"),
              UiConfig.text("hide_historic_labels")
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
                            toast(UiConfig.text("locate_yourself_first_to_find_nearby_places"));
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
        .setNegativeButton(UiConfig.text("close"), null)
        .show();
  }

  private void fetchHistoricPlaces() {
    if (historicLoading) {
      toast(UiConfig.text("historic_place_search_is_already_running"));
      return;
    }
    if (offlineOnly) {
      toast(UiConfig.text("offline_only_showing_cached_places_turn_it_off_to_refresh"));
      refresh();
      return;
    }
    GeoPoint center = nearbyCenter;
    if (center == null) return;
    historicLoading = true;
    toast(UiConfig.text("finding_dated_historic_places_within_25_km"));
    heritageIo.execute(
        () -> {
          String message;
          try (TrailDb cache = new TrailDb(getApplicationContext())) {
            int count = HistoricPlaces.fetch(cache, center.getLatitude(), center.getLongitude());
            message = count + UiConfig.text("dated_places_found_showing_up_to_50_nearest");
            cache.log(
                UiConfig.text("historic_places"), "SUCCESS", count + UiConfig.text("source_dated_places_cached_within_25_km"));
          } catch (Exception e) {
            message = UiConfig.text("lookup_unavailable_cached_places_kept") + e.getMessage();
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
    if (map == null) { return; }
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
          UiConfig.text("source_date")
              + p.date()
              + "\n"
              + (p.approximate() ? UiConfig.text("approximate_age") : UiConfig.text("age_from_source_date"))
              + age
              + UiConfig.text("years_openstreetmap_contributors_not_independently_verified_cach")
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
    paint.setTextSize(getResources().getDisplayMetrics().scaledDensity * UiConfig.MAP_LABEL_SIZE_SP);
    paint.setTypeface(Typeface.create(UiConfig.MAP_LABEL_FONT, UiConfig.MAP_LABEL_STYLE));
    int w = (int) paint.measureText(label) + dp(20), h = dp(35);
    android.graphics.Bitmap bitmap =
        android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
    android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
    paint.setColor(UiConfig.MAP_LABEL_BACKGROUND);
    canvas.drawRoundRect(0, 0, w, h - dp(7), dp(7), dp(7), paint);
    canvas.drawCircle(w / 2f, h - dp(4), dp(4), paint);
    paint.setColor(UiConfig.MAP_LABEL_TEXT);
    canvas.drawText(label, dp(10), dp(18), paint);
    return new android.graphics.drawable.BitmapDrawable(getResources(), bitmap);
  }

  private String driveDetails(long id) {
    DriveMetrics.Leg leg=driveMetrics==null?null:driveMetrics.stops().get(id);
    return leg==null?UiConfig.text("stop_distance_unavailable"):String.format(Locale.US,UiConfig.text("since_previous_stop_2f_km_average_since_departure_1f_km_h_s"),leg.meters()/1000,leg.averageKmh(),leg.missingMs()>60000?UiConfig.text("gps_gaps_present_recorded_distance_only"):"");
  }

  private void openCam(String page,long stop){
    if(new ModuleStore(this).enabled("mod2"))startActivity(new Intent(this,ModuleActivity.class).putExtra("id","mod2").putExtra("page",page).putExtra("stop_id",stop));
    else toast("Activate CAM in Extension modules first");
  }
  private void showStops() {
    final long chosen = history ? selectedTrip : -1;
    io.execute(() -> {
      try {
        List<TrailDb.Stop> stops = db.stops(chosen);
        DriveMetrics.Result metrics = db.driveMetrics();
        Collections.reverse(stops);
        CharSequence[] labels = new CharSequence[stops.size()];
        for(int i=0;i<stops.size();i++) {
          TrailDb.Stop s=stops.get(i);
          DriveMetrics.Leg leg=metrics.stops().get(s.id());
          String label=(s.placeName()==null?UiConfig.text("stop")+s.id():s.placeName())+"\n"+clock(s.arrival())+" · "+duration(s.observed()-s.arrival())
              +(leg==null?UiConfig.text("stop_distance_unavailable"):"\n"+String.format(Locale.US,UiConfig.text("stop_leg_summary"),leg.meters()/1000,leg.averageKmh()));
          android.text.SpannableString styled=new android.text.SpannableString(label);
          if(StopLabelsOverlay.longStop(s)){
            styled.setSpan(new android.text.style.ForegroundColorSpan(UiConfig.LONG_STOP_COLOR),0,styled.length(),0);
            styled.setSpan(new android.text.style.StyleSpan(Typeface.ITALIC),0,styled.length(),0);
          }
          labels[i]=styled;
        }
        runOnUiThread(() -> {
          if(isFinishing() || isDestroyed())return;
          new AppDialogs(this).setTitle(UiConfig.text("recorded_stops")+stops.size())
            .setItems(labels,(d,index)->{
              TrailDb.Stop s=stops.get(index);
              driveMetrics = metrics;
              String details=UiConfig.text("arrived")+clock(s.arrival())+UiConfig.text("observed_duration")+duration(s.observed()-s.arrival())
                +"\n"+(s.departure()==0?UiConfig.text("last_observation_2")+clock(s.observed()):UiConfig.text("departed_2")+clock(s.departure()))
                +"\n"+String.format(Locale.US,UiConfig.text("gps_6f_6f_2"),s.lat(),s.lon());
              details += driveDetails(s.id());
              for(String value:new String[]{s.locality(),s.road(),s.district(),s.state(),s.country(),s.postalCode()})
                if(value!=null&&!value.isBlank())details+="\n"+value;
              android.app.AlertDialog.Builder detail=new AppDialogs(this).setTitle(s.placeName()==null?UiConfig.text("stop")+s.id():s.placeName()).setMessage(details).setPositiveButton(UiConfig.text("close"),null);
              if(new ModuleStore(this).enabled("mod2"))detail.setNeutralButton("Photos",(dialog,which)->openCam("gallery",s.id()));
              detail.show();
            }).setPositiveButton(UiConfig.text("close"),null).show();
        });
      }catch(Exception e){CrashReports.save(this,UiConfig.text("stop_list"),e);runOnUiThread(()->toast(UiConfig.text("cannot_read_stop_list")+e.getMessage()));}
    });
  }

  private void showPalette() {
    LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);
    for(int i=0;i<TrailPalette.COLORS.length;i++) {
      LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(3),dp(12),dp(3));
      View swatch=new View(this);swatch.setBackgroundColor(TrailPalette.COLORS[i]);row.addView(swatch,new LinearLayout.LayoutParams(dp(36),dp(22)));
      row.addView(text(String.format(Locale.US,UiConfig.text("pass_02d_06x"),i+1,TrailPalette.COLORS[i]&0xffffff),14,INK));rows.addView(row);
    }
    rows.addView(text(String.format(Locale.US,UiConfig.text("continuity_06x"),TrailPalette.CONTINUITY&0xffffff),14,INK));ScrollView scroll=new ScrollView(this);scroll.addView(rows);
    new AppDialogs(this).setTitle(UiConfig.text("repeated_road_passes_31_colors")).setView(scroll).setPositiveButton(UiConfig.text("close"),null).show();
  }

  private void history() {
    if (history) {
      history = false;
      follow = true;
      showMap();
      return;
    }
    toast(UiConfig.text("loading_journey_list"));
    io.execute(() -> {
      try {
        List<TrailDb.TripSummary> trips = db.tripSummaries();
        String[] names = new String[trips.size() + 1];
        names[0] = UiConfig.text("entire_collected_trail");
        for (int i = 0; i < trips.size(); i++) {
          TrailDb.TripSummary t = trips.get(i);
          names[i + 1] = UiConfig.text("trail") + t.id() + (t.count() == 0 ? UiConfig.text("waiting_for_gps")
              : " · " + clock(t.first()) + " · " + t.count() + UiConfig.text("fixes_2"));
        }
        runOnUiThread(() -> {
          if (isFinishing() || isDestroyed() || downloads) return;
          new AppDialogs(this).setTitle(UiConfig.text("your_journeys")).setItems(names, (d, i) -> {
            selectedTrip = i == 0 ? -1 : trips.get(i - 1).id();
            history = true; follow = true; showMap();
          }).setNegativeButton(UiConfig.text("close"), null).show();
        });
      } catch (Exception e) { runOnUiThread(() -> toast(UiConfig.text("history_unavailable") + e.getMessage())); }
    });
  }

  private void fitBounds(MapView view, BoundingBox bounds) {
    Runnable fit = () -> {
      if (wtfActive() || view != map || view.getWidth() <= dp(60) || view.getHeight() <= dp(60)) return;
      try { view.zoomToBoundingBox(bounds, false, dp(30)); }
      catch (RuntimeException e) {
        android.util.Log.e("oTraiL", "Trail viewport failed", e);
        view.getController().setCenter(bounds.getCenterWithDateLine());
        view.getController().setZoom(12.0);
      }
    };
    if (view.getWidth() > dp(60) && view.getHeight() > dp(60)) view.post(fit);
    else view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
      @Override public void onLayoutChange(View v,int l,int t,int r,int b,int ol,int ot,int or,int ob) {
        if (view != map) { view.removeOnLayoutChangeListener(this); return; }
        if (r-l > dp(60) && b-t > dp(60)) { view.removeOnLayoutChangeListener(this); view.post(fit); }
      }
    });
  }

  private void destination(GeoPoint p) {
    new AppDialogs(this)
        .setTitle(
            String.format(Locale.US, UiConfig.text("destination_5f_5f"), p.getLatitude(), p.getLongitude()))
        .setPositiveButton(
            UiConfig.text("preview_in_otrail"),
            (d, w) ->
                navigation.plan(
                    new DestinationSearch.Result(
                        UiConfig.text("map_destination"), new RoutePlan.Point(p.getLatitude(), p.getLongitude()))))
        .setNeutralButton(UiConfig.text("other_apps"), (d, w) -> externalDestination(p))
        .setNegativeButton(UiConfig.text("cancel"), null)
        .show();
  }

  private void externalDestination(GeoPoint p) {
    new AppDialogs(this)
        .setTitle(
            String.format(Locale.US, UiConfig.text("navigate_to_5f_5f"), p.getLatitude(), p.getLongitude()))
        .setItems(
            new String[] {UiConfig.text("google_maps"), "OsmAnd", UiConfig.text("other_maps")},
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
                toast(UiConfig.text("this_navigation_app_is_not_installed"));
              }
            })
        .setNegativeButton(UiConfig.text("cancel"), null)
        .show();
  }

  private void showDownloads() {
    if(liveBubble!=null){liveBubble.close();liveBubble=null;}liveBubblePoint=null;mapMenu=null;
    if(wtfTurn!=null){wtfTurn.cancel();wtfTurn=null;}
    if(moduleHost!=null){moduleHost.close();moduleHost=null;}
    downloads = true;
    if (map != null) {
      MapView old = map;
      map = null;
      old.setDestroyMode(false);
      old.onPause();
      if (old.getParent() instanceof android.view.ViewGroup)
        ((android.view.ViewGroup)old.getParent()).removeView(old);
      old.onDetach();
    }
    base(UiConfig.text("maps_downloads_india_only"));
    Button back = button(UiConfig.text("back_to_map"), this::showMap);
    if(getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE){
      LinearLayout backRow=new LinearLayout(this);
      backRow.addView(back,new LinearLayout.LayoutParams(0,dp(44),1));
      for(int slot=1;slot<UiConfig.SETTINGS_COLUMNS_LANDSCAPE;slot++)backRow.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
      AppStyle.spaceButtons(backRow);body.addView(backRow);
    }else body.addView(back);
    ScrollView scroll = new ScrollView(this);
    downloadCards = new LinearLayout(this);
    downloadCards.setOrientation(LinearLayout.VERTICAL);
    downloadCards.setPadding(dp(12), 0, dp(12), dp(12));
    scroll.addView(downloadCards);
    body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView title = text(UiConfig.text("take_india_with_you"), 25, INK);
    title.setTypeface(Typeface.create(UiConfig.HEADING_FONT, UiConfig.HEADING_FONT_STYLE));
    downloadCards.addView(title);
    downloadCards.addView(
        text(
            UiConfig.text("map_data_downloads_straight_to_phone_storage_wi_fi_or_cellular_c")
                + UiConfig.text("stay_in_downloads_otrail_across_reinstall_import_the_retained_ma")
                + UiConfig.text("afterwards_sizes_are_estimates_until_the_server_reports_the_tota"),
            13,
            MUTED));
    cardUpdates.clear();
    downloadCards.addView(button(UiConfig.text("modules_title"),()->ModuleHost.settings(this)));
    if(new ModuleStore(this).available("mod2"))downloadCards.addView(button("CAM · Photo folder & camera settings",()->openCam("settings",-1)));
    for (MapPacks.Pack p : MapPacks.PACKS) packCard(p);
    if (routingControls == null) routingControls = new RoutingControls(this);
    routingControls.addTo(downloadCards, cardUpdates);
    downloadCards.addView(button(UiConfig.text("app_updates_daily_check_now"), () -> updates.show()));
    downloadCards.addView(button(UiConfig.text("last_app_crash_view_copy"), () -> CrashReports.show(this)));
    downloadCards.addView(button(UiConfig.text("trail_palette_31_pass_colors"), this::showPalette));
    downloadCards.addView(
        button(
            UiConfig.text("online_routing_server"),
            () -> {
              EditText endpoint = new EditText(this);
              endpoint.setText(NavigationManager.get(this).server());
              new AppDialogs(this)
                  .setTitle(UiConfig.text("https_osrm_driving_endpoint"))
                  .setView(endpoint)
                  .setPositiveButton(
                      UiConfig.text("save"),
                      (d, w) -> {
                        try {
                          NavigationManager.get(this).server(endpoint.getText().toString());
                          toast(UiConfig.text("routing_endpoint_saved"));
                        } catch (Exception e) {
                          toast(e.getMessage());
                        }
                      })
                  .setNegativeButton(UiConfig.text("cancel"), null)
                  .show();
            }));
    downloadCards.addView(
        button(
            UiConfig.text("identify_historical_stop_locations"),
            () -> {
              StopGeocoder.backfill(this);
              toast(UiConfig.text("historical_place_lookup_queued_failed_lookups_retry_after_24_hou"));
            }));
    infoCard(
        UiConfig.text("always_on_trail_recording"),
        TrackingStartup.rebootReady(this) ? UiConfig.text("reboot_permission_ready") : UiConfig.text("reboot_setup_needed"),
        UiConfig.text("gps_request_every_5_seconds_recording_starts_automatically_after")
            + UiConfig.text("and_restarts_after_boot_update_when_background_location_is_allow"));
    downloadCards.addView(button(UiConfig.text("tracker_permissions_reboot_setup"), this::trackerStatus));
    infoCard(
        UiConfig.text("trail_backup_google_drive_or_phone_folder"),
        UiConfig.text("persistent_backup"),
        new TrailBackup(this).status());
    downloadCards.addView(
        button(
            UiConfig.text("choose_backup_location_google_drive_files"),
            () ->
                new AppDialogs(this)
                    .setTitle(UiConfig.text("choose_your_backup_location"))
                    .setMessage(
                        UiConfig.text("in_the_save_picker_select_google_drive_open_the_folder_you_want_")
                            + UiConfig.text("otrail_backup_json_you_can_also_use_a_persistent_documents_folde")
                            + UiConfig.text("on_this_phone_enable_drive_in_the_picker_s_side_menu_if_availabl")
                            + UiConfig.text("automatic_writes_happen_every_15_minutes_drive_manages_network")
                            + UiConfig.text("sync_after_reinstall_use_restore_to_reopen_the_saved_backup_then")
                            + UiConfig.text("select_the_backup_target_again_a_fresh_backup_target_should_be_a")
                            + UiConfig.text("new_file"))
                    .setPositiveButton(
                        UiConfig.text("choose_location"),
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
                    .setNegativeButton(UiConfig.text("close"), null)
                    .show()));
    downloadCards.addView(button(UiConfig.text("back_up_trail_now"), () -> backupNow()));
    downloadCards.addView(
        button(
            UiConfig.text("restore_trail_from_google_drive_files"),
            () -> {
              Intent i =
                  new Intent(Intent.ACTION_OPEN_DOCUMENT)
                      .setType("*/*")
                      .addCategory(Intent.CATEGORY_OPENABLE);
              startActivityForResult(i, 23);
            }));

    infoCard(
        UiConfig.text("openstreetmap_roads"),
        UiConfig.text("online_viewed_tile_cache"),
        UiConfig.text("road_basemap_with_the_same_trails_and_stop_details_openstreetmap")
            + UiConfig.text("tiles_do_not_permit_bulk_offline_pack_downloads"));
    infoCard(
        UiConfig.text("esri_satellite_2"),
        UiConfig.text("online_only"),
        UiConfig.text("satellite_context_with_your_trail_overlay_tiles_esri_maxar_earth")
            + UiConfig.text("contributors_bulk_offline_download_is_not_enabled"));
    infoCard(
        UiConfig.text("in_app_driving_navigation"),
        UiConfig.text("text_directions_no_voice"),
        UiConfig.text("use_navigate_to_search_or_hold_a_point_on_the_map_preview_and_st")
            + UiConfig.text("otrail_osrm_fossgis_provides_online_routes_rerouting_needs_inter")
            + UiConfig.text("traffic_or_voice_other_apps_remain_optional"));
    downloadCards.addView(
        button(
            UiConfig.text("import_an_india_map_file"),
            () -> {
              Intent i =
                  new Intent(Intent.ACTION_OPEN_DOCUMENT)
                      .setType("*/*")
                      .addCategory(Intent.CATEGORY_OPENABLE);
              startActivityForResult(i, 20);
            }));
    downloadCards.addView(
        button(
            UiConfig.text("export_all_gps_logs_geojson"),
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
            UiConfig.text("manual_update_history"),
            () ->
                new AppDialogs(this)
                    .setTitle(UiConfig.text("update_log"))
                    .setMessage(db.logs())
                    .setPositiveButton(UiConfig.text("close"), null)
                    .show()));
    downloadCards.addView(
        button(
            UiConfig.text("location_battery_settings"),
            () ->
                new AppDialogs(this)
                    .setTitle(UiConfig.text("keep_recording_on_your_poco"))
                    .setMessage(
                        UiConfig.text("recording_starts_automatically_with_precise_location_permission_")
                            + UiConfig.text("reboot_recovery_allow_location_all_the_time_enable_background")
                            + UiConfig.text("autostart_in_hyperos_and_set_battery_no_restrictions_force_stop")
                            + UiConfig.text("requires_reopening_the_app"))
                    .setPositiveButton(
                        UiConfig.text("app_settings"),
                        (d, w) ->
                            startActivity(
                                new Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton(UiConfig.text("close"), null)
                    .show()));
    if(getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE)
      AppStyle.compactSettings(downloadCards,UiConfig.SETTINGS_COLUMNS_LANDSCAPE);
    for (Runnable r : cardUpdates) r.run();
  }

  private void infoCard(String name, String tag, String detail) {
    LinearLayout card = card();
    card.addView(text(tag, 10, TEAL));
    TextView title = text(name, 18, INK);
    title.setTypeface(Typeface.create(UiConfig.HEADING_FONT, UiConfig.HEADING_FONT_STYLE));
    card.addView(title);
    card.addView(text(detail, 12, MUTED));
  }

  private LinearLayout card() {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(0, dp(9), 0, dp(12));
    AppStyle.decoratePanel(card,UiConfig.PANEL_ACCENTS[downloadCards.getChildCount()%UiConfig.PANEL_ACCENTS.length]);
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
                ? UiConfig.text("offline_map_india")
                : UiConfig.text("optional_source_data_india"),
            10,
            TEAL));
    TextView name = text(p.name(), 19, INK);
    name.setTypeface(Typeface.create(UiConfig.HEADING_FONT, UiConfig.HEADING_FONT_STYLE));
    card.addView(name);
    card.addView(text(p.description(), 12, MUTED));
    TextView state = text(UiConfig.text("checking_phone_storage"), 13, INK);
    card.addView(state);
    ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setProgressTintList(android.content.res.ColorStateList.valueOf(AppStyle.BLUE));
    LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, dp(8));
    bar.setMargins(dp(16), dp(4), dp(16), dp(4));
    card.addView(progress, bar);
    TextView bytes = text(UiConfig.text("estimated") + MapPacks.size(p.estimatedBytes()), 12, MUTED);
    card.addView(bytes);
    LinearLayout actions = new LinearLayout(this);
    Button action =
        button(
            UiConfig.text("download"),
            () -> {
              try {
                packs.download(p);
                toast(UiConfig.text("queued_on_phone_wi_fi_or_cellular"));
                for (Runnable r : cardUpdates) r.run();
              } catch (Exception e) {
                toast(e.getMessage());
              }
            });
    Button cancel =
        button(
            UiConfig.text("cancel"),
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
          if (checking[0]) { return; }
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
                                  : UiConfig.text("phone_storage")));
                      action.setEnabled(!s.pending());
                      action.setText(
                          s.installed()
                              ? UiConfig.text("update_manually")
                              : s.status().startsWith("Failed") ? UiConfig.text("retry_download") : "Download");
                      cancel.setEnabled(s.pending());
                    });
              });
        });
  }

  private void backupNow() {
    if (!new TrailBackup(this).configured()) {
      toast(UiConfig.text("choose_a_backup_location_first"));
      return;
    }
    toast(UiConfig.text("saving_trail_backup"));
    io.execute(
        () -> {
          try {
            new TrailBackup(this).save(db, true);
            runOnUiThread(
                () -> {
                  toast(UiConfig.text("backup_written_drive_handles_upload_sync"));
                  if (downloads) showDownloads();
                });
          } catch (Exception e) {
            runOnUiThread(() -> toast(UiConfig.text("backup_failed") + e.getMessage()));
          }
        });
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    if (request == 31) {
      if (routingControls == null) routingControls = new RoutingControls(this);
      routingControls.imported(uri);
      return;
    }
    if (request == 22) {
      try {
        new TrailBackup(this).configure(uri, data.getFlags());
        backupNow();
      } catch (Exception e) {
        toast(UiConfig.text("cannot_retain_backup_access") + e.getMessage());
      }
      return;
    }
    if (request == 23) {
      toast(UiConfig.text("restoring_trail_snapshot"));
      io.execute(
          () -> {
            try {
              new TrailBackup(this).restore(db, uri);
              runOnUiThread(
                  () -> {
                    TrackingStartup.start(this, false);
                    provider = getPreferences(0).getInt("provider", 0);
    wtfEnabled=getPreferences(0).getBoolean("wtf_mode",false);
    wtfCourse=getPreferences(0).getFloat("wtf_course",Float.NaN);
                    offlineOnly = getPreferences(0).getBoolean("offline", false);
                    lastFixId = -1;
                    lastStopSignature = 0;
                    StopGeocoder.backfill(this);
                    toast(
                        UiConfig.text("trail_history_restored_re_select_the_backup_target_to_resume_aut")
                            + UiConfig.text("backups"));
                    if (downloads) showDownloads();
                  });
            } catch (Exception e) {
              runOnUiThread(() -> toast(UiConfig.text("restore_failed") + e.getMessage()));
            }
          });
      return;
    }
    toast(request == 20 ? UiConfig.text("importing_and_validating_india_map") : UiConfig.text("exporting_gps_logs"));
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
                  toast(request == 20 ? UiConfig.text("india_pack_imported") : UiConfig.text("geojson_export_saved"));
                  if (downloads) for (Runnable r : cardUpdates) r.run();
                });
          } catch (Exception e) {
            db.log(UiConfig.text("file_operation"), "FAILED", e.toString());
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

  private void refreshLiveBubble(){if(liveBubble!=null&&liveBubblePoint!=null&&!history&&!downloads&&status!=null){
    String address=liveContext.address(),addressNote="";if(LivePopupText.addressPending(address)){addressNote=address;address="";}String road=roadControls.roadName();if(!road.isBlank()&&!address.contains(road))address+=(address.isBlank()?"":"\n")+road;
    LivePopupText.Parts worship=LivePopupText.worship(liveContext.worship(liveBubblePoint.getLatitude(),liveBubblePoint.getLongitude()));liveBubble.update(liveBubblePoint,status.getText(),address,worship.values());
    if(lookupNotes!=null){String note=(addressNote.isBlank()?"":addressNote+"\n")+worship.notes();lookupNotes.setText(note.trim());lookupNotes.setVisibility(note.isBlank()?View.GONE:View.VISIBLE);}
  }}
  private boolean wtfActive(){return wtfEnabled&&!history;}
  private void toggleWtf(){
    wtfEnabled=history||!wtfEnabled;getPreferences(0).edit().putBoolean("wtf_mode",wtfEnabled).apply();
    if(wtfEnabled&&history){history=false;showMap();}
    if(wtfButton!=null)wtfButton.update(wtfActive());if(wtfNorth!=null)wtfNorth.setVisibility(wtfActive()?View.VISIBLE:View.GONE);
    if(wtfEnabled){fitTrail=false;follow=true;navigationFollow=true;if(Float.isFinite(wtfCourse))wtfRotate(WtfCamera.rotation(wtfCourse));if(map.getZoomLevelDouble()<14)map.getController().setZoom(16.0);}
    else{wtfTarget=null;if(wtfTurn!=null){wtfTurn.cancel();wtfTurn=null;}map.setMapOrientation(0);map.invalidate();}
    lastFixId=-1;refresh();navigationTick();toast(wtfEnabled?"WTF! enabled · GPS centered · travel follows diagonal":"WTF! disabled · north-up restored");
  }
  private void wtfCenter(){
    if(!wtfActive()||map==null||wtfTarget==null||wtfRecentering)return;
    wtfRecentering=true;try{map.getController().setCenter(wtfTarget);if(wtfNorth!=null)wtfNorth.rotation(map.getMapOrientation());}finally{wtfRecentering=false;}
  }
  private void wtfRotate(float target){
    if(!wtfActive()||map==null)return;if(wtfTurn!=null)wtfTurn.cancel();MapView view=map;float start=view.getMapOrientation(),turn=WtfCamera.shortestTurn(start,target);
    wtfTurn=android.animation.ValueAnimator.ofFloat(start,start+turn);wtfTurn.setDuration(UiConfig.WTF_TURN_ANIMATION_MS);wtfTurn.addUpdateListener(animation->{if(view!=map||!wtfActive())return;float angle=WtfCamera.normalize((Float)animation.getAnimatedValue());view.setMapOrientation(angle);wtfCenter();view.invalidate();});wtfTurn.start();
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
    if (!wtfActive()&&!history && state.route() != null && routeFitRevision != state.revision()) {
      if (!"PREVIEW".equals(state.mode()) || navigation.fit(map))
        routeFitRevision = state.revision();
    }
    if(wtfActive()){Marker live=visibleMarkers.get("live");if(live!=null)wtfTarget=live.getPosition();wtfCenter();return;}
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
            ? secs / 60 + UiConfig.text("m_3") + secs % 60 + "s"
            : secs / 3600 + UiConfig.text("h") + secs / 60 % 60 + "m";
  }

  private void fullscreen() {
    getWindow().setDecorFitsSystemWindows(false);
    WindowInsetsController controller = getWindow().getDecorView().getWindowInsetsController();
    if (controller != null) {
      controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
      controller.hide(WindowInsets.Type.systemBars());
    }
  }

  @Override public void onWindowFocusChanged(boolean focused) {
    super.onWindowFocusChanged(focused);
    if (focused) fullscreen();
  }

  private void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  @Override
  protected void onResume() {
    super.onResume();
    fullscreen();
    TrackingStartup.start(this, false);
    StopGeocoder.backfill(this);
    if (map != null) map.onResume();
    if(wtfActive()&&Float.isFinite(wtfCourse))wtfRotate(WtfCamera.rotation(wtfCourse));
    boolean camNow=new ModuleStore(this).enabled("mod2");if(map!=null&&!downloads&&camNow!=camVisible){camVisible=camNow;showMap();}
    updates.resume();
    handler.post(ticker);
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(ticker);
    if(wtfTurn!=null){wtfTurn.cancel();wtfTurn=null;}
    if(wtfActive()&&map!=null)getPreferences(0).edit().putFloat("wtf_zoom",(float)map.getZoomLevelDouble()).apply();
    if (map != null) map.onPause();
    super.onPause();
  }

  @Override
  public void onBackPressed() {
    if(mapMenu!=null&&mapMenu.closeIfOpen())return;
    if (downloads) {
      showMap();
      return;
    }
    super.onBackPressed();
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacks(ticker);
    if(wtfTurn!=null){wtfTurn.cancel();wtfTurn=null;}
    if (map != null) map.onDetach();
    io.shutdown();
    heritageIo.shutdownNow();
    if(moduleHost!=null)moduleHost.close();
    navigation.close();
    roadControls.close();
    if(liveContext!=null)liveContext.close();if(liveBubble!=null)liveBubble.close();
    if (routingControls != null) routingControls.close();
    super.onDestroy();
  }
}
