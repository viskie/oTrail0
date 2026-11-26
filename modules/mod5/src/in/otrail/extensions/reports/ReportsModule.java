package in.otrail.extensions.reports;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import in.otrail.DriveMetrics;
import in.otrail.StopDetector;
import in.otrail.TrailDb;
import in.otrail.modules.ModuleV1;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ReportsModule implements ModuleV1 {
  private static final int BG = 0xff050509, PANEL = 0xff101018, TEXT = 0xfff8fff8,
      MUTED = 0xff93a0b0, CYAN = 0xff00ffff, LIME = 0xff4dff9a, ORANGE = 0xffffa733;
  private static final long DAY = 24L * 60 * 60 * 1000;
  private Activity activity;
  private LinearLayout summary, insights, bars;
  private TextView rangeLabel, status;
  private Button daily, weekly, monthly, custom;
  private HorizontalScrollView barScroll;
  private int mode;
  private long start, end;
  private final Calendar anchor = Calendar.getInstance();
  private final SimpleDateFormat rangeFormat =
      new SimpleDateFormat("EEE, d MMM yyyy", Locale.US);
  private final SimpleDateFormat shortFormat = new SimpleDateFormat("d MMM", Locale.US);

  @Override public String id() { return "mod5"; }

  private int dp(int n) {
    return Math.round(n * activity.getResources().getDisplayMetrics().density);
  }

  private GradientDrawable panel(int fill, int stroke) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(fill);
    d.setCornerRadius(dp(9));
    d.setStroke(dp(1), stroke);
    return d;
  }

  private TextView text(String value, int sp, int color) {
    TextView v = new TextView(activity);
    v.setText(value);
    v.setTextSize(sp);
    v.setTextColor(color);
    v.setPadding(0, dp(2), 0, dp(2));
    return v;
  }

  private Button button(String value) {
    Button b = new Button(activity);
    b.setText(value);
    b.setTextSize(13);
    b.setAllCaps(false);
    b.setTextColor(CYAN);
    b.setBackground(panel(0xff0b0b12, 0xff2b6f86));
    b.setMinHeight(0);
    b.setMinimumHeight(0);
    b.setPadding(dp(8), dp(7), dp(8), dp(7));
    return b;
  }

  private LinearLayout row() {
    LinearLayout r = new LinearLayout(activity);
    r.setOrientation(LinearLayout.HORIZONTAL);
    r.setGravity(Gravity.CENTER_VERTICAL);
    return r;
  }

  private void addSummaryCard(String value, String label) {
    LinearLayout card = new LinearLayout(activity);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setGravity(Gravity.CENTER);
    card.setPadding(dp(8), dp(8), dp(8), dp(8));
    card.setBackground(panel(PANEL, 0xff263747));
    TextView big = text(value, 19, TEXT);
    big.setGravity(Gravity.CENTER);
    TextView small = text(label, 10, MUTED);
    small.setGravity(Gravity.CENTER);
    card.addView(big);
    card.addView(small);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
    p.setMargins(dp(3), dp(3), dp(3), dp(3));
    summary.addView(card, p);
  }

  private void addInsight(String value) {
    TextView v = text("• " + value, 12, LIME);
    v.setPadding(0, dp(3), 0, dp(3));
    insights.addView(v);
  }

  @Override
  public View create(Activity activity, Host host) throws Exception {
    this.activity = activity;
    ScrollView scroll = new ScrollView(activity);
    scroll.setBackgroundColor(BG);
    scroll.setFillViewport(true);
    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(12), dp(12), dp(12), dp(24));
    scroll.addView(root);

    root.addView(text("Reports", 24, CYAN));
    TextView hint = text(
        "Daily, weekly, monthly and custom trail reports from the recorded GPS data. Tap a bar to drill into that interval.",
        12, MUTED);
    hint.setPadding(0, 0, 0, dp(8));
    root.addView(hint);

    LinearLayout tabs = row();
    daily = button("Daily");
    weekly = button("Weekly");
    monthly = button("Monthly");
    custom = button("Custom");
    daily.setOnClickListener(v -> selectMode(0));
    weekly.setOnClickListener(v -> selectMode(1));
    monthly.setOnClickListener(v -> selectMode(2));
    custom.setOnClickListener(v -> chooseCustomRange());
    for (Button b : new Button[] {daily, weekly, monthly, custom}) {
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
      p.setMargins(dp(2), 0, dp(2), 0);
      tabs.addView(b, p);
    }
    root.addView(tabs);

    LinearLayout rangeRow = row();
    Button previous = button("‹");
    Button next = button("›");
    rangeLabel = text("", 14, TEXT);
    rangeLabel.setGravity(Gravity.CENTER);
    previous.setOnClickListener(v -> shift(-1));
    next.setOnClickListener(v -> shift(1));
    rangeRow.addView(previous, new LinearLayout.LayoutParams(dp(48), -2));
    rangeRow.addView(rangeLabel, new LinearLayout.LayoutParams(0, -2, 1));
    rangeRow.addView(next, new LinearLayout.LayoutParams(dp(48), -2));
    LinearLayout.LayoutParams rangeParams = new LinearLayout.LayoutParams(-1, -2);
    rangeParams.setMargins(0, dp(6), 0, dp(8));
    root.addView(rangeRow, rangeParams);

    status = text("", 11, MUTED);
    status.setPadding(0, 0, 0, dp(6));
    root.addView(status);

    summary = new LinearLayout(activity);
    summary.setOrientation(LinearLayout.VERTICAL);
    root.addView(summary);

    TextView activityHeading = text("Distance activity", 15, ORANGE);
    activityHeading.setPadding(0, dp(12), 0, dp(4));
    root.addView(activityHeading);
    barScroll = new HorizontalScrollView(activity);
    barScroll.setHorizontalScrollBarEnabled(false);
    bars = new LinearLayout(activity);
    bars.setOrientation(LinearLayout.HORIZONTAL);
    bars.setGravity(Gravity.BOTTOM);
    barScroll.addView(bars, new HorizontalScrollView.LayoutParams(-2, -2));
    root.addView(barScroll, new LinearLayout.LayoutParams(-1, dp(170)));

    TextView insightHeading = text("Interesting findings", 15, ORANGE);
    insightHeading.setPadding(0, dp(12), 0, dp(4));
    root.addView(insightHeading);
    insights = new LinearLayout(activity);
    insights.setOrientation(LinearLayout.VERTICAL);
    root.addView(insights);

    selectMode(0);
    return scroll;
  }

  private void selectMode(int value) {
    mode = value;
    if (value != 3) anchor.setTimeInMillis(System.currentTimeMillis());
    updateTabStyles();
    computeRange();
    focusRangeWithData();
    updateRangeLabel();
    reload();
  }

  /**
   * A daily/weekly/monthly view that opens on a period with no recorded fixes reads as all zeroes.
   * Fall back to the period holding the newest fix so the default view always shows real data.
   */
  private void focusRangeWithData() {
    if (mode == 3) return;
    try (TrailDb db = new TrailDb(activity)) {
      if (hasFix(db, start, end)) return;
      long latest = latestFix(db);
      if (latest <= 0) return;
      anchor.setTimeInMillis(latest);
      computeRange();
    } catch (Exception ignored) {
      // Keep the default range when the database cannot be inspected.
    }
  }

  private boolean hasFix(TrailDb db, long from, long to) throws Exception {
    try (Cursor c = db.getReadableDatabase().rawQuery(
        "SELECT 1 FROM fixes WHERE time_ms>=? AND time_ms<? LIMIT 1",
        new String[] {"" + from, "" + to})) {
      return c.moveToFirst();
    }
  }

  private long latestFix(TrailDb db) throws Exception {
    try (Cursor c = db.getReadableDatabase().rawQuery("SELECT MAX(time_ms) FROM fixes", null)) {
      return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : 0;
    }
  }

  private void updateTabStyles() {
    Button[] all = {daily, weekly, monthly, custom};
    for (int i = 0; i < all.length; i++) {
      Button b = all[i];
      boolean selected = i == mode;
      b.setTextColor(selected ? 0xff050509 : CYAN);
      b.setBackground(selected ? panel(LIME, LIME) : panel(0xff0b0b12, 0xff2b6f86));
    }
  }

  private void shift(int direction) {
    if (mode == 0) anchor.add(Calendar.DAY_OF_YEAR, direction);
    else if (mode == 1) anchor.add(Calendar.WEEK_OF_YEAR, direction);
    else if (mode == 2) anchor.add(Calendar.MONTH, direction);
    else {
      long span = Math.max(DAY, end - start);
      start += direction * span;
      end = start + span;
      updateRangeLabel();
      reload();
      return;
    }
    computeRange();
    reload();
  }

  private void chooseCustomRange() {
    Calendar first = Calendar.getInstance();
    first.setTimeInMillis(start);
    new DatePickerDialog(activity, (view, y, m, d) -> {
      Calendar from = Calendar.getInstance();
      from.set(y, m, d, 0, 0, 0);
      from.set(Calendar.MILLISECOND, 0);
      Calendar initial = Calendar.getInstance();
      initial.setTimeInMillis(Math.max(from.getTimeInMillis(), end - 1));
      new DatePickerDialog(activity, (view2, y2, m2, d2) -> {
        Calendar to = Calendar.getInstance();
        to.set(y2, m2, d2, 0, 0, 0);
        to.set(Calendar.MILLISECOND, 0);
        if (to.before(from)) to.setTimeInMillis(from.getTimeInMillis());
        start = from.getTimeInMillis();
        end = to.getTimeInMillis() + DAY;
        mode = 3;
        updateTabStyles();
        updateRangeLabel();
        reload();
      }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH),
          initial.get(Calendar.DAY_OF_MONTH)).show();
    }, first.get(Calendar.YEAR), first.get(Calendar.MONTH),
        first.get(Calendar.DAY_OF_MONTH)).show();
  }

  private void computeRange() {
    Calendar c = (Calendar) anchor.clone();
    c.set(Calendar.HOUR_OF_DAY, 0);
    c.set(Calendar.MINUTE, 0);
    c.set(Calendar.SECOND, 0);
    c.set(Calendar.MILLISECOND, 0);
    if (mode == 0) {
      start = c.getTimeInMillis();
      end = start + DAY;
    } else if (mode == 1) {
      c.set(Calendar.DAY_OF_WEEK, c.getFirstDayOfWeek());
      start = c.getTimeInMillis();
      end = start + 7 * DAY;
    } else if (mode == 2) {
      c.set(Calendar.DAY_OF_MONTH, 1);
      start = c.getTimeInMillis();
      c.add(Calendar.MONTH, 1);
      end = c.getTimeInMillis();
    } else if (end <= start) {
      start = c.getTimeInMillis();
      end = start + DAY;
    }
    updateRangeLabel();
  }

  private void updateRangeLabel() {
    rangeLabel.setText(rangeFormat.format(new Date(start)) + " → "
        + rangeFormat.format(new Date(end - 1)));
  }

  private void reload() {
    if (activity == null || summary == null) return;
    summary.removeAllViews();
    insights.removeAllViews();
    bars.removeAllViews();
    try (TrailDb db = new TrailDb(activity)) {
      Report report = report(db);
      status.setText(report.fixes.size() + " fixes · " + report.stops.size() + " stops · "
          + report.activeDays + " active day" + (report.activeDays == 1 ? "" : "s"));
      addSummaryRow(
          km(report.meters), "distance",
          report.movingMs <= 0 ? "—" : duration(report.movingMs), "moving");
      addSummaryRow(
          Integer.toString(report.stops.size()), "stops",
          report.movingMs <= 0 ? "—" : String.format(Locale.US, "%.1f km/h", report.avgKmh),
          "moving average");
      addSummaryRow(
          Integer.toString(report.fixes.size()), "GPS fixes",
          Integer.toString(report.activeDays), "active days");
      addSummaryRow(
          report.longestStopMs <= 0 ? "—" : duration(report.longestStopMs), "longest stop",
          report.topPlace.isEmpty() ? "—" : report.topPlace, "most visited");
      renderBars(report.buckets);
      if (report.buckets.isEmpty() || report.fixes.isEmpty()) {
        addInsight("No recorded GPS data in this range yet.");
      } else {
        Bucket busiest = report.buckets.get(0);
        for (Bucket b : report.buckets) if (b.meters > busiest.meters) busiest = b;
        addInsight("Busiest interval: " + busiest.label + " · " + km(busiest.meters));
        if (report.longestLegMeters > 0)
          addInsight("Longest drive leg: " + km(report.longestLegMeters));
        if (report.mostActiveHour >= 0)
          addInsight("Most active hour: " + String.format(Locale.US, "%02d:00", report.mostActiveHour));
        if (report.farthestMeters > 0)
          addInsight("Farthest point from start: " + km(report.farthestMeters));
        if (report.longestStopMs > 0)
          addInsight("Longest stop lasted " + duration(report.longestStopMs)
              + (report.longestStopPlace.isEmpty() ? "" : " at " + report.longestStopPlace));
        if (!report.topPlace.isEmpty())
          addInsight("Most visited place: " + report.topPlace + " ("
              + report.topPlaceCount + " stops)");
        addInsight(report.stops.size() == 0
            ? "No stationary stops were logged in this interval."
            : "Average stop duration: " + duration(report.stopMs / Math.max(1, report.stops.size())));
      }
    } catch (Exception e) {
      status.setText("Reports failed: " + e.getMessage());
      addInsight("Could not read the trail database. " + e.getMessage());
    }
  }

  private void addSummaryRow(String a, String al, String b, String bl) {
    LinearLayout r = row();
    summary.addView(r);
    LinearLayout before = summary;
    summary = r;
    addSummaryCard(a, al);
    addSummaryCard(b, bl);
    summary = before;
  }

  private String km(double meters) {
    return String.format(Locale.US, "%.2f km", meters / 1000);
  }

  private String duration(long millis) {
    long minutes = Math.max(0, millis) / 60000;
    if (minutes >= 60) return (minutes / 60) + "h " + (minutes % 60) + "m";
    return minutes + "m";
  }

  private void renderBars(List<Bucket> buckets) {
    bars.removeAllViews();
    double max = 1;
    for (Bucket b : buckets) max = Math.max(max, b.meters);
    View newest = null;
    for (Bucket b : buckets) {
      LinearLayout column = new LinearLayout(activity);
      column.setOrientation(LinearLayout.VERTICAL);
      column.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
      column.setPadding(dp(3), 0, dp(3), 0);
      // Every interval with recorded distance keeps a readable value, even below 1 km.
      TextView value = text(b.meters <= 0 ? "" : b.meters >= 1000
          ? String.format(Locale.US, "%.1f", b.meters / 1000)
          : String.format(Locale.US, "%.0fm", b.meters), 9, MUTED);
      value.setGravity(Gravity.CENTER);
      View bar = new View(activity);
      int height = b.meters <= 0 ? dp(2) : Math.max(dp(8), (int) (dp(110) * b.meters / max));
      bar.setBackground(panel(b.meters <= 0 ? 0xff25303a : LIME, b.meters <= 0 ? 0xff25303a : LIME));
      TextView label = text(b.label, 9, MUTED);
      label.setGravity(Gravity.CENTER);
      column.addView(value, new LinearLayout.LayoutParams(-1, dp(14)));
      column.addView(bar, new LinearLayout.LayoutParams(dp(22), height));
      column.addView(label, new LinearLayout.LayoutParams(-1, dp(22)));
      column.setOnClickListener(v -> {
        if (b.meters <= 0) return;
        start = b.start;
        end = b.end;
        mode = 3;
        updateTabStyles();
        updateRangeLabel();
        reload();
      });
      if (b.meters > 0) newest = column;
      bars.addView(column, new LinearLayout.LayoutParams(dp(46), -2));
    }
    // Hours and days are laid out oldest-first; scroll to the most recent interval with data.
    final View target = newest;
    bars.post(() -> {
      if (barScroll == null) return;
      if (target == null) barScroll.fullScroll(View.FOCUS_RIGHT);
      else barScroll.smoothScrollTo(Math.max(0, target.getLeft() - dp(24)), 0);
    });
  }

  private Report report(TrailDb db) throws Exception {
    Report r = new Report();
    r.fixes = fixes(db, start, end);
    r.stops = stops(db, start, end);
    List<DriveMetrics.Point> points = new ArrayList<>();
    for (TrailDb.Fix f : r.fixes)
      points.add(new DriveMetrics.Point(f.time(), f.lat(), f.lon(), f.accuracy(), f.speed()));
    List<DriveMetrics.Stop> dwell = new ArrayList<>();
    for (TrailDb.Stop s : r.stops)
      dwell.add(new DriveMetrics.Stop(s.id(), s.arrival(), s.departure(), s.observed()));
    DriveMetrics.Result metrics = DriveMetrics.calculate(points, dwell);
    r.meters = metrics.totalMeters();
    Set<String> days = new LinkedHashSet<>();
    SimpleDateFormat dayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    int[] hours = new int[24];
    long previousTime = 0;
    float previousAccuracy = 0, previousSpeed = 0;
    for (TrailDb.Fix f : r.fixes) {
      days.add(dayKey.format(new Date(f.time())));
      hours[new Date(f.time()).getHours()]++;
      if (previousTime > 0) {
        long dt = f.time() - previousTime;
        if (dt > 0 && dt <= StopDetector.MAX_GAP_MS && previousAccuracy <= 50 && f.accuracy() <= 50
            && previousSpeed >= .8f && f.speed() >= .8f) {
          r.movingMs += dt;
        }
      }
      previousTime = f.time();
      previousAccuracy = f.accuracy();
      previousSpeed = f.speed();
    }
    for (int i = 0; i < hours.length; i++)
      if (hours[i] > (r.mostActiveHour < 0 ? 0 : hours[r.mostActiveHour])) r.mostActiveHour = i;
    r.activeDays = days.size();
    r.avgKmh = r.movingMs > 0 ? r.meters * 3.6 / (r.movingMs / 1000.0) : 0;
    Map<String, Integer> placeCounts = new LinkedHashMap<>();
    for (TrailDb.Stop s : r.stops) {
      long stopEnd = s.departure() > 0 ? s.departure() : s.observed();
      long stopDuration = Math.max(0, Math.min(stopEnd, end) - Math.max(s.arrival(), start));
      r.stopMs += stopDuration;
      if (stopDuration > r.longestStopMs) {
        r.longestStopMs = stopDuration;
        r.longestStopPlace = place(s);
      }
      String key = place(s);
      if (!key.isEmpty()) {
        placeCounts.put(key, placeCounts.getOrDefault(key, 0) + 1);
      }
    }
    for (Map.Entry<String, Integer> entry : placeCounts.entrySet()) {
      if (entry.getValue() > r.topPlaceCount) {
        r.topPlace = entry.getKey();
        r.topPlaceCount = entry.getValue();
      }
    }
    if (!r.fixes.isEmpty()) {
      TrailDb.Fix first = r.fixes.get(0);
      for (TrailDb.Fix f : r.fixes)
        r.farthestMeters = Math.max(r.farthestMeters,
            StopDetector.distance(first.lat(), first.lon(), f.lat(), f.lon()));
    }
    for (DriveMetrics.Leg leg : metrics.stops().values())
      r.longestLegMeters = Math.max(r.longestLegMeters, leg.meters());
    if (metrics.current() != null)
      r.longestLegMeters = Math.max(r.longestLegMeters, metrics.current().meters());
    r.buckets = buckets(r.fixes);
    return r;
  }

  private List<TrailDb.Fix> fixes(TrailDb db, long from, long to) {
    List<TrailDb.Fix> out = new ArrayList<>();
    try (Cursor c = db.getReadableDatabase().rawQuery(
        "SELECT id,trip_id,time_ms,latitude,longitude,accuracy_m,speed_mps FROM fixes"
            + " WHERE time_ms>=? AND time_ms<? ORDER BY time_ms,id",
        new String[] {"" + from, "" + to})) {
      while (c.moveToNext())
        out.add(new TrailDb.Fix(c.getLong(0), c.getLong(1), c.getLong(2), c.getDouble(3),
            c.getDouble(4), c.getFloat(5), c.getFloat(6)));
    }
    return out;
  }

  private List<TrailDb.Stop> stops(TrailDb db, long from, long to) {
    List<TrailDb.Stop> out = new ArrayList<>();
    try (Cursor c = db.getReadableDatabase().rawQuery(
        "SELECT id,trip_id,arrival_ms,departure_ms,observed_ms,latitude,longitude,place_name,"
            + "locality,district,state,country,postal_code,road,geocode_status,geocode_attempt_ms"
            + " FROM stops WHERE arrival_ms<? AND COALESCE(departure_ms,observed_ms)>?"
            + " ORDER BY arrival_ms,id",
        new String[] {"" + to, "" + from})) {
      while (c.moveToNext())
        out.add(new TrailDb.Stop(c.getLong(0), c.getLong(1), c.getLong(2),
            c.isNull(3) ? 0 : c.getLong(3), c.getLong(4), c.getDouble(5), c.getDouble(6),
            c.getString(7), c.getString(8), c.getString(9), c.getString(10), c.getString(11),
            c.getString(12), c.getString(13), c.getString(14), c.getLong(15)));
    }
    return out;
  }

  private String place(TrailDb.Stop s) {
    if (s.placeName() != null && !s.placeName().isBlank()) return s.placeName().trim();
    if (s.locality() != null && !s.locality().isBlank()) return s.locality().trim();
    if (s.road() != null && !s.road().isBlank()) return s.road().trim();
    return "";
  }

  private List<Bucket> buckets(List<TrailDb.Fix> fixes) {
    List<Bucket> out = new ArrayList<>();
    // Calendar stepping keeps hour and day edges correct across DST changes and short months.
    Calendar c = Calendar.getInstance();
    c.setTimeInMillis(start);
    if (mode == 0) {
      c.set(Calendar.MINUTE, 0);
      c.set(Calendar.SECOND, 0);
      c.set(Calendar.MILLISECOND, 0);
      for (int i = 0; i < 24; i++) {
        Bucket b = new Bucket();
        b.start = c.getTimeInMillis();
        c.add(Calendar.HOUR_OF_DAY, 1);
        b.end = c.getTimeInMillis();
        b.label = String.format(Locale.US, "%02d", i);
        out.add(b);
      }
    } else {
      c.set(Calendar.HOUR_OF_DAY, 0);
      c.set(Calendar.MINUTE, 0);
      c.set(Calendar.SECOND, 0);
      c.set(Calendar.MILLISECOND, 0);
      long days = Math.max(1, (end - start + DAY - 1) / DAY);
      int step = days > 45 ? 7 : 1;
      while (c.getTimeInMillis() < end) {
        Bucket b = new Bucket();
        b.start = Math.max(start, c.getTimeInMillis());
        c.add(Calendar.DAY_OF_MONTH, step);
        b.end = Math.min(end, c.getTimeInMillis());
        b.label = shortFormat.format(new Date(b.start));
        out.add(b);
      }
    }
    for (int i = 1; i < fixes.size(); i++) {
      TrailDb.Fix a = fixes.get(i - 1), b = fixes.get(i);
      long dt = b.time() - a.time();
      if (dt <= 0 || dt > StopDetector.MAX_GAP_MS || a.accuracy() > 50 || b.accuracy() > 50)
        continue;
      double meters = StopDetector.distance(a.lat(), a.lon(), b.lat(), b.lon());
      if (meters / (dt / 1000.0) > 60) continue;
      if (a.speed() < .8f && b.speed() < .8f
          && meters < Math.max(5, (a.accuracy() + b.accuracy()) / 2)) continue;
      // Split the drive across hour/day edges instead of dropping or misattributing it.
      for (Bucket bucket : out) {
        long from = Math.max(a.time(), bucket.start);
        long to = Math.min(b.time(), bucket.end);
        if (to > from) bucket.meters += meters * (to - from) / (double) dt;
      }
    }
    return out;
  }

  @Override public void resume() {
    if (activity != null && summary != null) reload();
  }
  @Override public void pause() {}
  @Override public void close() {}

  private static final class Bucket {
    long start, end;
    double meters;
    String label = "";
  }

  private static final class Report {
    List<TrailDb.Fix> fixes = List.of();
    List<TrailDb.Stop> stops = List.of();
    List<Bucket> buckets = List.of();
    double meters, farthestMeters, longestLegMeters, avgKmh;
    long movingMs, stopMs, longestStopMs;
    int activeDays, mostActiveHour = -1, topPlaceCount;
    String topPlace = "", longestStopPlace = "";
  }
}
