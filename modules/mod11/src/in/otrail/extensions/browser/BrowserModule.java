package in.otrail.extensions.browser;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import in.otrail.modules.ModuleV1;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Browser with a developer panel occupying the top 40% of the module view.
 * Uses the device's Android System WebView so no native engine ships in the DEX package.
 */
public final class BrowserModule implements ModuleV1 {
  private static final int BG = 0xff0b0e13, SURFACE = 0xff12161d, SURFACE_ALT = 0xff171c24,
      TEXT = 0xfff8fafc, MUTED = 0xff8494a3, CYAN = 0xff00d4ff, GREEN = 0xff55ff55,
      RED = 0xffff3838, AMBER = 0xffffb300, BORDER = 0xff2a3555;
  private static final String HOME = "https://www.google.com/";
  private static final int MAX_LINES = 400;

  private Activity activity;
  private Host host;
  private LinearLayout root, devPanel, devTabs, jsRow;
  private View consoleTab, networkTab, infoTab;
  private LinearLayout jsTab;
  private TextView consoleText, networkText, infoText, status, jsResult, progressText;
  private EditText urlField, jsInput;
  private WebView web;
  private boolean devVisible = true;
  private String devTab = "console";
  private final List<String> consoleLines = new ArrayList<>();
  private final List<String> networkLines = new ArrayList<>();
  private final Handler main = new Handler(Looper.getMainLooper());
  private java.util.Timer networkPoller;

  @Override public String id() {
    return "mod11";
  }

  @Override public View create(Activity activity, Host host) {
    this.activity = activity;
    this.host = host;
    root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(BG);
    root.setPadding(dp(6), dp(5), dp(6), dp(5));
    root.addView(toolbar(), new LinearLayout.LayoutParams(-1, -2));

    devPanel = buildDevPanel();
    root.addView(devPanel, devParams(true));

    web = new WebView(activity);
    configure(web);
    root.addView(web, webParams(true));

    web.loadUrl(HOME);
    devTab("console");
    return root;
  }

  @Override public boolean backPressed() {
    if (web != null && web.canGoBack()) {
      web.goBack();
      return true;
    }
    return false;
  }

  @Override public void resume() {
    if (web != null) web.onResume();
    startPolling();
  }

  @Override public void pause() {
    if (web != null) web.onPause();
    stopPolling();
  }

  @Override public void close() {
    stopPolling();
    if (web != null) {
      web.stopLoading();
      web.destroy();
      web = null;
    }
    activity = null;
    host = null;
  }

  // ---------------------------------------------------------------- layout

  private View toolbar() {
    LinearLayout top = new LinearLayout(activity);
    top.setOrientation(LinearLayout.VERTICAL);

    LinearLayout row = new LinearLayout(activity);
    row.setGravity(Gravity.CENTER_VERTICAL);
    urlField = new EditText(activity);
    urlField.setHint("https://…");
    urlField.setHintTextColor(MUTED);
    urlField.setTextColor(TEXT);
    urlField.setTextSize(12);
    urlField.setSingleLine(true);
    urlField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
    urlField.setBackground(panel(SURFACE_ALT, BORDER, 7));
    urlField.setPadding(dp(9), 0, dp(9), 0);
    row.addView(urlField, new LinearLayout.LayoutParams(0, dp(40), 1));
    row.addView(chip("GO", this::goTyped), new LinearLayout.LayoutParams(dp(48), dp(40)));
    row.addView(chip("DEV", this::toggleDev), new LinearLayout.LayoutParams(dp(48), dp(40)));
    top.addView(row);

    LinearLayout nav = new LinearLayout(activity);
    nav.addView(chip("←", () -> {
      if (web != null && web.canGoBack()) web.goBack();
    }), wide(1));
    nav.addView(chip("→", () -> {
      if (web != null && web.canGoForward()) web.goForward();
    }), wide(1));
    nav.addView(chip("⟳", () -> {
      if (web != null) web.reload();
    }), wide(1));
    nav.addView(chip("HOME", () -> load(HOME)), wide(1));
    nav.addView(chip("CLR", this::clearDev), wide(1));
    nav.addView(chip("EXTERNAL", this::openExternal), wide(1));
    top.addView(nav);

    LinearLayout meta = new LinearLayout(activity);
    meta.setGravity(Gravity.CENTER_VERTICAL);
    status = label("", 9, MUTED);
    status.setMaxLines(1);
    meta.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
    progressText = label("", 9, CYAN);
    meta.addView(progressText);
    top.addView(meta);
    return top;
  }

  private LinearLayout buildDevPanel() {
    LinearLayout panel = new LinearLayout(activity);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setBackground(panel(SURFACE, BORDER, 8));
    panel.setPadding(dp(4), dp(3), dp(4), dp(3));

    devTabs = new LinearLayout(activity);
    devTabs.addView(chip("CONSOLE", () -> devTab("console")), wide(1));
    devTabs.addView(chip("NETWORK", () -> devTab("network")), wide(1));
    devTabs.addView(chip("JS", () -> devTab("js")), wide(1));
    devTabs.addView(chip("INFO", () -> devTab("info")), wide(1));
    panel.addView(devTabs);

    consoleText = devText();
    networkText = devText();
    infoText = devText();
    consoleTab = wrap(consoleText);
    networkTab = wrap(networkText);
    infoTab = wrap(infoText);

    jsTab = new LinearLayout(activity);
    jsTab.setOrientation(LinearLayout.VERTICAL);
    jsRow = new LinearLayout(activity);
    jsRow.setGravity(Gravity.CENTER_VERTICAL);
    jsInput = new EditText(activity);
    jsInput.setHint("document.title");
    jsInput.setHintTextColor(MUTED);
    jsInput.setTextColor(TEXT);
    jsInput.setTextSize(11);
    jsInput.setTypeface(Typeface.MONOSPACE);
    jsInput.setSingleLine(true);
    jsInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    jsInput.setBackground(panel(SURFACE_ALT, BORDER, 6));
    jsInput.setPadding(dp(6), 0, dp(6), 0);
    jsRow.addView(jsInput, new LinearLayout.LayoutParams(0, dp(38), 1));
    jsRow.addView(chip("EVAL", this::evaluate), new LinearLayout.LayoutParams(dp(52), dp(38)));
    jsTab.addView(jsRow);
    jsResult = devText();
    ScrollView jsScroll = new ScrollView(activity);
    jsScroll.addView(jsResult, new ScrollView.LayoutParams(-1, -2));
    jsTab.addView(jsScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

    panel.addView(consoleTab, new LinearLayout.LayoutParams(-1, 0, 1f));
    panel.addView(networkTab, new LinearLayout.LayoutParams(-1, 0, 1f));
    panel.addView(jsTab, new LinearLayout.LayoutParams(-1, 0, 1f));
    panel.addView(infoTab, new LinearLayout.LayoutParams(-1, 0, 1f));
    return panel;
  }

  private View wrap(TextView content) {
    ScrollView scroll = new ScrollView(activity);
    content.setTextIsSelectable(true);
    scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
    return scroll;
  }

  private TextView devText() {
    TextView text = label("", 9, TEXT);
    text.setTypeface(Typeface.MONOSPACE);
    text.setTextIsSelectable(true);
    text.setLineSpacing(0, 1.1f);
    text.setPadding(dp(4), dp(3), dp(4), dp(3));
    return text;
  }

  private LinearLayout.LayoutParams devParams(boolean visible) {
    return new LinearLayout.LayoutParams(-1, 0, visible ? 4f : 0f);
  }

  private LinearLayout.LayoutParams webParams(boolean withDev) {
    return new LinearLayout.LayoutParams(-1, 0, withDev ? 6f : 1f);
  }

  private void toggleDev() {
    devVisible = !devVisible;
    devPanel.setVisibility(devVisible ? View.VISIBLE : View.GONE);
    devPanel.setLayoutParams(devParams(devVisible));
    web.setLayoutParams(webParams(devVisible));
    if (devVisible) refreshInfo();
  }

  private void devTab(String tab) {
    devTab = tab;
    consoleTab.setVisibility(tab.equals("console") ? View.VISIBLE : View.GONE);
    networkTab.setVisibility(tab.equals("network") ? View.VISIBLE : View.GONE);
    jsTab.setVisibility(tab.equals("js") ? View.VISIBLE : View.GONE);
    infoTab.setVisibility(tab.equals("info") ? View.VISIBLE : View.GONE);
    if (tab.equals("network")) refreshNetwork();
    else if (tab.equals("info")) refreshInfo();
  }

  // ---------------------------------------------------------------- webview

  private void configure(WebView view) {
    WebSettings settings = view.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setDatabaseEnabled(true);
    settings.setUseWideViewPort(true);
    settings.setLoadWithOverviewMode(true);
    settings.setBuiltInZoomControls(true);
    settings.setDisplayZoomControls(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
    settings.setMediaPlaybackRequiresUserGesture(false);
    settings.setJavaScriptCanOpenWindowsAutomatically(true);
    settings.setSupportMultipleWindows(false);
    settings.setUserAgentString(settings.getUserAgentString());
    CookieManager.getInstance().setAcceptCookie(true);
    CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
    try {
      WebView.setWebContentsDebuggingEnabled(true);
    } catch (Throwable ignored) {
      // Some OEM WebViews reject debugging; the in-app panel still works.
    }

    view.setWebViewClient(new WebViewClient() {
      @Override public boolean shouldOverrideUrlLoading(WebView target, WebResourceRequest request) {
        Uri uri = request == null ? null : request.getUrl();
        if (uri == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        if (scheme.equals("http") || scheme.equals("https") || scheme.equals("file")
            || scheme.equals("about") || scheme.equals("data")) return false;
        try {
          activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception error) {
          devLog("console", "no handler for " + uri);
          hostLog("scheme-failed", error);
        }
        return true;
      }

      @Override public void onPageStarted(WebView target, String url, android.graphics.Bitmap icon) {
        urlField.setText(url);
        setStatus("Loading…", AMBER);
        if (devVisible) refreshNetwork();
      }

      @Override public void onPageFinished(WebView target, String url) {
        urlField.setText(url);
        setStatus(url, GREEN);
        collectNetwork();
        if (devTab.equals("info")) refreshInfo();
      }

      @Override public void onReceivedError(WebView target, WebResourceRequest request,
          WebResourceError error) {
        String detail = (request == null ? "" : request.getUrl() + " · ")
            + (error == null ? "unknown" : error.getDescription() + " (" + error.getErrorCode() + ")");
        devLog("console", "ERROR " + detail);
        setStatus("Load error · " + detail, RED);
        hostLog("page-error", detail);
      }
    });

    view.setWebChromeClient(new WebChromeClient() {
      @Override public boolean onConsoleMessage(ConsoleMessage message) {
        if (message == null) return false;
        String level = message.messageLevel() == null ? "LOG"
            : message.messageLevel().name();
        String where = message.sourceId() == null ? "" : message.sourceId()
            + ":" + message.lineNumber();
        devLog("console", level + " · " + message.message() + (where.isEmpty() ? "" : "  " + where));
        return true;
      }

      @Override public void onProgressChanged(WebView target, int progress) {
        progressText.setText(progress < 100 ? progress + "%" : "");
      }

      @Override public boolean onCreateWindow(WebView target, boolean dialog,
          boolean userGesture, Message result) {
        return false;
      }
    });
  }

  private void goTyped() {
    String text = urlField.getText().toString().trim();
    if (text.isEmpty()) return;
    if (!text.contains("://") && !text.startsWith("about:") && !text.startsWith("file:"))
      text = "https://" + text;
    if (!text.contains(".") && !text.startsWith("http") && !text.contains(" "))
      text = "https://www.google.com/search?q=" + Uri.encode(text);
    load(text);
  }

  private void load(String url) {
    if (web == null) return;
    urlField.setText(url);
    web.loadUrl(url);
    setStatus(url, MUTED);
  }

  private void openExternal() {
    if (web == null) return;
    try {
      activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(web.getUrl())));
    } catch (Exception error) {
      hostLog("external-failed", error);
      setStatus("No app can open this page.", RED);
    }
  }

  // ---------------------------------------------------------------- dev tools

  private void devLog(String kind, String line) {
    List<String> target = kind.equals("network") ? networkLines : consoleLines;
    target.add(line);
    while (target.size() > MAX_LINES) target.remove(0);
    if (kind.equals("network")) {
      if (devTab.equals("network")) render(networkText, networkLines);
    } else if (devTab.equals("console")) {
      render(consoleText, consoleLines);
    }
  }

  private void render(TextView view, List<String> lines) {
    StringBuilder text = new StringBuilder();
    for (String line : lines) text.append(line).append('\n');
    view.setText(text.toString());
    View parent = (View) view.getParent();
    if (parent instanceof ScrollView) {
      ScrollView scroll = (ScrollView) parent;
      scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
  }

  private void collectNetwork() {
    if (web == null) return;
    web.evaluateJavascript("(function(){try{return JSON.stringify("
        + "performance.getEntriesByType('resource').map(function(e){return {n:e.name,"
        + "t:e.initiatorType,d:Math.round(e.duration),s:e.transferSize,"
        + "st:(e.responseStatus||0),ok:e.responseEnd>0};}));}catch(e){return '[]';}})()",
        value -> {
          if (value == null) return;
          try {
            String json = new JSONArray("[" + value + "]").getString(0);
            JSONArray entries = new JSONArray(json);
            synchronized (networkLines) {
              networkLines.clear();
              for (int index = 0; index < entries.length() && index < 150; index++) {
                JSONObject entry = entries.getJSONObject(index);
                String name = entry.optString("n", "");
                if (name.length() > 120) name = "…" + name.substring(name.length() - 118);
                networkLines.add(entry.optInt("st", 0) + " · " + entry.optInt("d", 0) + "ms · "
                    + entry.optInt("s", 0) + "B\n  " + entry.optString("t", "") + " · " + name);
              }
              if (networkLines.isEmpty()) networkLines.add("No resource timings yet.");
            }
            if (devTab.equals("network")) render(networkText, networkLines);
          } catch (Exception error) {
            devLog("console", "network parse · " + message(error));
          }
        });
  }

  private void refreshNetwork() {
    if (!networkLines.isEmpty() && !networkLines.get(0).equals("No resource timings yet.")) {
      render(networkText, networkLines);
    }
    collectNetwork();
  }

  private void refreshInfo() {
    if (web == null) return;
    web.evaluateJavascript("(function(){try{return JSON.stringify({"
        + "title:document.title,url:location.href,ua:navigator.userAgent,lang:navigator.language,"
        + "viewport:innerWidth+'x'+innerHeight,dpr:devicePixelRatio,"
        + "cookies:document.cookie.split(';').filter(function(v){return v.trim();}).length,"
        + "storage:(function(){try{return localStorage.length;}catch(e){return -1;}})()"
        + "});}catch(e){return '{}';}})()", value -> {
      if (value == null) return;
      try {
        String json = new JSONArray("[" + value + "]").getString(0);
        JSONObject info = new JSONObject(json);
        StringBuilder text = new StringBuilder();
        text.append("TITLE   ").append(info.optString("title", "")).append('\n');
        text.append("URL     ").append(info.optString("url", "")).append('\n');
        text.append("VIEWPORT ").append(info.optString("viewport", ""))
            .append("  dpr ").append(info.optDouble("dpr", 1)).append('\n');
        text.append("COOKIES ").append(info.optInt("cookies", 0))
            .append(" · localStorage keys ").append(info.optInt("storage", 0)).append('\n');
        text.append("WEBVIEW ").append(WebSettings.getDefaultUserAgent(activity)).append('\n');
        text.append("AGENT   ").append(info.optString("ua", "")).append('\n');
        text.append("LANG    ").append(info.optString("lang", "")).append('\n');
        infoText.setText(text.toString());
      } catch (Exception error) {
        infoText.setText("Could not read page info: " + message(error));
      }
    });
  }

  private void evaluate() {
    if (web == null) return;
    String code = jsInput.getText().toString().trim();
    if (code.isEmpty()) return;
    String script = "(function(){try{var r=eval(" + JSONObject.quote(code)
        + ");try{return JSON.stringify(r);}catch(e){return String(r);}}"
        + "catch(e){return 'ERROR: '+(e&&e.stack?e.stack:e);}})()";
    web.evaluateJavascript(script, value -> {
      try {
        String result = new JSONArray("[" + value + "]").getString(0);
        jsResult.setText("> " + code + "\n" + result);
      } catch (Exception error) {
        jsResult.setText("> " + code + "\n" + value);
      }
      devLog("console", "eval · " + code);
    });
  }

  private void clearDev() {
    consoleLines.clear();
    networkLines.clear();
    consoleText.setText("");
    networkText.setText("");
    jsResult.setText("");
    status.setText("Dev buffers cleared.");
  }

  private void startPolling() {
    stopPolling();
    if (!devVisible) return;
    networkPoller = new java.util.Timer("browser-dev", true);
    networkPoller.scheduleAtFixedRate(new java.util.TimerTask() {
      @Override public void run() {
        final boolean active = activity != null && devVisible && devTab.equals("network");
        if (!active) return;
        main.post(BrowserModule.this::collectNetwork);
      }
    }, 2500, 3500);
  }

  private void stopPolling() {
    if (networkPoller != null) {
      networkPoller.cancel();
      networkPoller = null;
    }
  }

  // ---------------------------------------------------------------- widgets

  private Button chip(String text, Runnable action) {
    Button button = new Button(activity);
    button.setText(text);
    button.setTextSize(9);
    button.setTextColor(TEXT);
    button.setAllCaps(false);
    button.setSingleLine(true);
    button.setMinHeight(0);
    button.setMinimumHeight(0);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(dp(3), 0, dp(3), 0);
    button.setBackground(panel(SURFACE_ALT, BORDER, 6));
    button.setOnClickListener(view -> action.run());
    return button;
  }

  private TextView label(String text, int size, int color) {
    TextView view = new TextView(activity);
    view.setText(text);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setIncludeFontPadding(false);
    view.setPadding(0, dp(1), 0, dp(1));
    return view;
  }

  private GradientDrawable panel(int color, int stroke, int radiusDp) {
    GradientDrawable background = new GradientDrawable();
    background.setColor(color);
    background.setCornerRadius(dp(radiusDp));
    background.setStroke(dp(1), stroke);
    return background;
  }

  private LinearLayout.LayoutParams wide(float weight) {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(30), weight);
    params.setMargins(dp(1), dp(2), dp(1), 0);
    return params;
  }

  private void setStatus(String text, int color) {
    if (status == null) return;
    status.setText(text);
    status.setTextColor(color);
  }

  private int dp(int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }

  private String message(Throwable error) {
    String message = error.getMessage();
    return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
  }

  private void hostLog(String action, Throwable error) {
    hostLog(action, error.getClass().getSimpleName()
        + (error.getMessage() == null ? "" : ": " + error.getMessage()));
  }

  private void hostLog(String action, String detail) {
    Host owner = host;
    if (owner == null) return;
    try {
      owner.log(action, detail);
    } catch (Throwable ignored) {
      // Logging must never break the module.
    }
  }
}
