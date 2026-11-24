package in.otrail.extensions.logger;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import in.otrail.UiConfig;
import in.otrail.modules.ModuleV1;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** In-app viewer and exporter for host-observed module events and crash reports. */
public final class LoggerModule implements ModuleV1 {
  private static final int EXPORT = 601;
  private Activity activity;
  private Host host;
  private TextView status;
  private TextView output;
  private EditText search;
  private Button errorsOnly;
  private boolean onlyErrors;
  private String lastText = "";
  private String lastExport = "";
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final SimpleDateFormat stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

  @Override
  public String id() {
    return "mod6";
  }

  @Override
  public View create(Activity activity, Host host) {
    this.activity = activity;
    this.host = host;
    ScrollView screen = new ScrollView(activity);
    screen.setFillViewport(true);
    screen.setBackgroundColor(UiConfig.MODULE_BACKGROUND);

    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(10), dp(8), dp(10), dp(12));
    screen.addView(root, new ScrollView.LayoutParams(-1, -2));

    LinearLayout header = new LinearLayout(activity);
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = label("LOGGER", 19, UiConfig.MODULE_ACCENT);
    title.setTypeface(Typeface.create(UiConfig.STATUS_FONT, Typeface.BOLD));
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    TextView hint = label("module events · errors · crashes", 9, UiConfig.MODULE_MUTED);
    hint.setGravity(Gravity.END);
    header.addView(hint, new LinearLayout.LayoutParams(-2, -2));
    root.addView(header);

    status = label("Reading module log…", 10, UiConfig.MODULE_MUTED);
    status.setMaxLines(3);
    status.setTextIsSelectable(true);
    root.addView(status);

    LinearLayout actions = new LinearLayout(activity);
    actions.addView(chip("REFRESH", this::refresh), weight());
    actions.addView(chip("EXPORT", this::export), weight());
    actions.addView(chip("CLEAR", this::clear), weight());
    root.addView(actions);

    LinearLayout filters = new LinearLayout(activity);
    filters.setGravity(Gravity.CENTER_VERTICAL);
    search = new EditText(activity);
    search.setHint("Filter module, action or detail");
    search.setHintTextColor(UiConfig.MODULE_MUTED);
    search.setTextColor(UiConfig.MODULE_TEXT);
    search.setTextSize(10);
    search.setSingleLine(true);
    search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
    filters.addView(search, new LinearLayout.LayoutParams(0, dp(34), 1));
    errorsOnly = chip("ERRORS", this::toggleErrors);
    filters.addView(errorsOnly, new LinearLayout.LayoutParams(dp(82), dp(34)));
    root.addView(filters);

    output = label("", 10, UiConfig.MODULE_TEXT);
    output.setTypeface(Typeface.MONOSPACE);
    output.setTextIsSelectable(true);
    output.setLineSpacing(0, 1.12f);
    output.setPadding(dp(6), dp(6), dp(6), dp(6));
    output.setBackground(panelBackground(UiConfig.MODULE_DIVIDER));
    root.addView(output, new LinearLayout.LayoutParams(-1, -2));

    refresh();
    return screen;
  }

  private void refresh() {
    final Host current = host;
    final Activity currentActivity = activity;
    if (current == null || currentActivity == null) return;
    String query = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.US);
    boolean errors = onlyErrors;
    status.setText("Reading module log…");
    worker.execute(() -> {
      String events = current.logs(2000);
      String crashes = current.crashLog();
      String text = render(events, crashes, query, errors);
      String full = render(events, crashes, "", false);
      currentActivity.runOnUiThread(() -> {
        if (activity == null) return;
        lastText = text;
        lastExport = full;
        output.setText(text);
        status.setText(countText(events) + " · " + (text.length() > 180000
            ? "showing newest 180000 characters" : "newest first"));
      });
    });
  }

  private String render(String events, String crashes, String query, boolean errorsOnlyFilter) {
    StringBuilder text = new StringBuilder();
    try {
      JSONArray array = new JSONArray(events);
      int shown = 0;
      for (int index = 0; index < array.length(); index++) {
        JSONObject item = array.getJSONObject(index);
        String module = item.optString("module", "host");
        String action = item.optString("action", "event");
        String detail = item.optString("detail", "");
        boolean error = action.contains("fail") || action.contains("error")
            || action.contains("crash") || detail.contains("Exception")
            || detail.contains("Error");
        if (errorsOnlyFilter && !error) continue;
        String haystack = (module + " " + action + " " + detail).toLowerCase(Locale.US);
        if (!query.isEmpty() && !haystack.contains(query)) continue;
        text.append(stamp.format(new Date(item.optLong("time"))))
            .append("  ")
            .append(module)
            .append(" / ")
            .append(action)
            .append('\n');
        if (!detail.isBlank()) {
          String clipped = detail.length() > 1600
              ? detail.substring(0, 1600) + "…" : detail;
          text.append("    ").append(clipped.replace("\n", "\n    ")).append('\n');
        }
        if (++shown >= 1000) break;
      }
      if (shown == 0) text.append("No matching module events.\n");
    } catch (Exception error) {
      text.append("Could not parse module events: ").append(error).append('\n');
    }
    if (crashes != null && !crashes.isBlank()) {
      text.append("\n========== CRASH / ANDROID EXIT REPORT ==========\n");
      int start = Math.max(0, crashes.length() - 24000);
      if (start > 0) text.append("…older lines omitted…\n");
      text.append(crashes.substring(start));
    }
    if (text.length() > 180000) text.delete(0, text.length() - 180000);
    return text.toString();
  }

  private String countText(String events) {
    try {
      return new JSONArray(events).length() + " event(s)";
    } catch (Exception error) {
      return "event count unavailable";
    }
  }

  private void toggleErrors() {
    onlyErrors = !onlyErrors;
    errorsOnly.setText(onlyErrors ? "ERRORS ON" : "ERRORS");
    errorsOnly.setTextColor(onlyErrors ? 0xff000000 : UiConfig.MODULE_TEXT);
    GradientDrawable background = new GradientDrawable();
    background.setColor(onlyErrors ? UiConfig.MODULE_ACCENT : UiConfig.MODULE_SURFACE_ALT);
    background.setCornerRadius(dp(6));
    background.setStroke(dp(1), onlyErrors ? UiConfig.MODULE_ACCENT : UiConfig.MODULE_DIVIDER);
    errorsOnly.setBackground(background);
    refresh();
  }

  private void export() {
    if (activity == null) return;
    if (lastExport.isEmpty()) lastExport = lastText;
    try {
      String name = "oTrail-module-logs-"
          + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".txt";
      activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
          .setType("text/plain")
          .addCategory(Intent.CATEGORY_OPENABLE)
          .putExtra(Intent.EXTRA_TITLE, name), EXPORT);
      status.setText("Choose where to save the log export.");
    } catch (Exception error) {
      status.setText("Could not open the export picker: " + error.getMessage());
    }
  }

  private void clear() {
    host.clearLogs();
    lastText = "";
    refresh();
  }

  @Override
  public void activityResult(int request, int result, Intent data) {
    if (request != EXPORT || result != Activity.RESULT_OK || data == null) return;
    Uri target = data.getData();
    if (target == null) return;
    try (OutputStream out = activity.getContentResolver().openOutputStream(target, "wt")) {
      if (out == null) throw new Exception("Cannot open export destination");
      out.write(lastExport.getBytes(StandardCharsets.UTF_8));
      out.flush();
      status.setText("Exported module events and crash report.");
    } catch (Exception error) {
      status.setText("Export failed: " + error.getMessage());
    }
  }

  @Override
  public void resume() {
    refresh();
  }

  @Override
  public void close() {
    worker.shutdownNow();
    activity = null;
    host = null;
  }

  private TextView label(String text, int size, int color) {
    TextView view = new TextView(activity);
    view.setText(text);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setIncludeFontPadding(false);
    view.setPadding(dp(3), dp(2), dp(3), dp(2));
    return view;
  }

  private Button chip(String text, Runnable action) {
    Button button = new Button(activity);
    button.setText(text);
    button.setTextSize(9);
    button.setTextColor(UiConfig.MODULE_TEXT);
    button.setAllCaps(false);
    button.setSingleLine(true);
    button.setMinHeight(0);
    button.setMinimumHeight(0);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(dp(3), 0, dp(3), 0);
    button.setBackgroundTintList(null);
    button.setBackground(panelBackground(UiConfig.MODULE_DIVIDER));
    button.setOnClickListener(view -> action.run());
    return button;
  }

  private GradientDrawable panelBackground(int stroke) {
    GradientDrawable background = new GradientDrawable();
    background.setColor(UiConfig.MODULE_SURFACE_ALT);
    background.setCornerRadius(dp(6));
    background.setStroke(dp(1), stroke);
    return background;
  }

  private LinearLayout.LayoutParams weight() {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(32), 1);
    params.setMargins(dp(2), dp(4), dp(2), dp(4));
    return params;
  }

  private int dp(int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }
}
