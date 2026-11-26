package in.otrail.extensions.mod1;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import in.otrail.UiConfig;
import in.otrail.modules.ModuleV1;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Shell management interface. The Debian image belongs to GOD (mod1); this
 * module owns the map launcher and keeps several live PTY sessions.
 */
public final class DebianShell implements ModuleV1 {
  private static final int KEY_NORMAL = 0;
  private static final int KEY_MOD = 1;
  private static final int KEY_ACTION = 2;
  private static final int KEY_ACTIVE = 3;
  private static final int KEYBOARD_ROW_HEIGHT_DP = 26;

  private Activity activity;
  private Host host;
  private LinearLayout root;
  private LinearLayout tabsRow;
  private LinearLayout keyboardRows;
  private TextView status;
  private android.webkit.WebView terminal;
  private boolean pageReady;
  private final List<String> waitingScripts = new ArrayList<>();
  private final Set<Integer> hydrated = new HashSet<>();
  private android.content.SharedPreferences prefs;
  private View tools, keypad;
  private TextView debugText;


  private final List<Session> sessions = new ArrayList<>();
  private Session active;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newCachedThreadPool();
  private final ExecutorService inputQueue = Executors.newSingleThreadExecutor();

  private boolean caps;
  private boolean symbols;
  private boolean ctrl;
  private boolean closed;
  private boolean setupRequested;
  private boolean terminalInput;

  private int nextTab;

  @Override public String id() {
    return "mod3";
  }

  @Override public View create(Activity activity, Host host) {
    if (root != null) detach();
    this.activity = activity;
    this.host = host;
    this.closed = false;
    try {
      activity.getWindow().setSoftInputMode(
          WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
              | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    } catch (Exception ignored) {
      // A few OEM windows reject soft-input hints.
    }

    root = new KeyRoot(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(UiConfig.MODULE_BACKGROUND);
    root.setPadding(dp(8), dp(6), dp(8), dp(6));
    root.setFocusableInTouchMode(true);

    root.addView(header());
    status = label("Preparing shared Debian image…", UiConfig.MODULE_LABEL_SIZE_SP, UiConfig.MODULE_MUTED);
    status.setMaxLines(2);
    status.setEllipsize(TextUtils.TruncateAt.END);
    root.addView(status);
    root.addView(tabsBar(), new LinearLayout.LayoutParams(-1, dp(32)));
    root.addView(terminalPanel(), new LinearLayout.LayoutParams(-1, 0, 1));
    tools = clipboardRow();
    tools.setVisibility(prefs.getBoolean("tools", true) ? View.VISIBLE : View.GONE);
    root.addView(tools, new LinearLayout.LayoutParams(-1, -2));


    keypad = keyboardPanel();
    root.addView(keypad);
    keypad.setVisibility(prefs.getBoolean("keypad", true) ? View.VISIBLE : View.GONE);
    debugText = label("ready", 9, UiConfig.MODULE_MUTED);
    debugText.setSingleLine(true);
    debugText.setEllipsize(TextUtils.TruncateAt.START);
    root.addView(debugText);
    root.addView(navRow());

    int restoredActive = restoreState();
    buildTabs();
    if (active == null && !sessions.isEmpty()) {
      Session initial = null;
      for (Session session : sessions) {
        if (session.number == restoredActive) initial = session;
      }
      switchSession(initial == null ? sessions.get(0) : initial);
    }
    root.requestFocus();
    host.keepProcessAlive(true);
    setup(false);
    handler.post(poll);
    return root;
  }

  private View header() {
    LinearLayout bar = row();
    bar.setBackgroundColor(UiConfig.MODULE_HEADER_BACKGROUND);
    bar.setPadding(dp(6), 0, dp(4), 0);

    TextView dot = label("●", 12, UiConfig.MODULE_DOT_ONLINE);
    dot.setPadding(dp(2), 0, dp(5), 0);
    bar.addView(dot);

    TextView title = label("Debian shell manager", UiConfig.MODULE_HEADER_SIZE_SP, UiConfig.MODULE_TEXT);
    title.setTypeface(Typeface.create(UiConfig.STATUS_FONT, Typeface.BOLD));
    bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    bar.addView(chip("Aa", this::appearance));
    bar.addView(chip("REPAIR", this::repair),
        new LinearLayout.LayoutParams(-2, dp(UiConfig.MODULE_KEY_HEIGHT_DP)));
    bar.addView(chip("HALT", this::exitAll),
        new LinearLayout.LayoutParams(-2, dp(UiConfig.MODULE_KEY_HEIGHT_DP)));

    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(-1, dp(UiConfig.MODULE_HEADER_HEIGHT_DP));
    params.setMargins(0, 0, 0, dp(2));
    bar.setLayoutParams(params);
    return bar;
  }

  private View tabsBar() {
    HorizontalScrollView scroll = new HorizontalScrollView(activity);
    scroll.setHorizontalScrollBarEnabled(false);
    tabsRow = new LinearLayout(activity);
    tabsRow.setOrientation(LinearLayout.HORIZONTAL);
    tabsRow.setGravity(Gravity.CENTER_VERTICAL);
    scroll.addView(tabsRow, new HorizontalScrollView.LayoutParams(-2, -1));
    return scroll;
  }

  private View terminalPanel() {
    prefs = activity.getSharedPreferences("debian-terminal", Context.MODE_PRIVATE);
    terminal = new android.webkit.WebView(activity) {
      @Override public android.view.inputmethod.InputConnection onCreateInputConnection(EditorInfo info) {
        android.view.inputmethod.InputConnection connection = super.onCreateInputConnection(info);
        info.imeOptions |= EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        return connection;
      }
    };
    terminal.setBackgroundColor(UiConfig.TERMINAL_BACKGROUND);
    android.webkit.WebSettings settings = terminal.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setBlockNetworkLoads(true);
    terminal.setWebViewClient(new android.webkit.WebViewClient() {
      @Override public boolean shouldOverrideUrlLoading(android.webkit.WebView view,
          android.webkit.WebResourceRequest request) { return true; }
      @Override public void onPageFinished(android.webkit.WebView view, String url) {
        if (closed) return;
        pageReady = true;
        waitingScripts.clear();
        hydrated.clear();
        applyAppearance();
        buildTabs();
        if (active != null) {
          js("select(" + active.number + ")");
          replay(active);
        }
      }
    });
    terminal.setOnFocusChangeListener((view, focused) -> {
      if (focused) terminalInput = true;
    });
    terminal.addJavascriptInterface(new Bridge(), "Shell");
    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(host.packageFile());
         InputStream stream = zip.getInputStream(zip.getEntry("terminal.html"))) {
      java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int count;
      while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
      terminal.loadData(android.util.Base64.encodeToString(bytes.toByteArray(),
          android.util.Base64.NO_WRAP), "text/html", "base64");
    } catch (Exception failure) {
      throw new IllegalStateException("Unable to load packaged terminal", failure);
    }
    return terminal;
  }

  private void js(String script) {
    if (closed || terminal == null) return;
    if (pageReady) terminal.evaluateJavascript(script, null);
    else waitingScripts.add(script);
  }

  public final class Bridge {
    @android.webkit.JavascriptInterface public boolean hardwareKeyboard() {
      for (int id : android.view.InputDevice.getDeviceIds()) {
        android.view.InputDevice device = android.view.InputDevice.getDevice(id);
        if (device != null && !device.isVirtual()
            && device.getKeyboardType() == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC) return true;
      }
      return false;
    }
    @android.webkit.JavascriptInterface public void focused(int id) {
      ui(() -> {
        if (active != null && active.number == id) terminalInput = true;
      });
    }
    @android.webkit.JavascriptInterface public void input(int id, String data) {
      ui(() -> {
        for (Session session : sessions) {
          if (session.number != id) continue;
          if ("\r".equals(data)) recordCommand(session);
          else if ("\u007f".equals(data) && session.line.length() > 0)
            session.line.setLength(session.line.length() - 1);
          else if (!data.isEmpty() && data.charAt(0) >= ' ') session.line.append(data);
          send(session, data);
          break;
        }
      });
    }
    @android.webkit.JavascriptInterface public void size(int id, int cols, int rows) {
      ui(() -> {
        if (active == null || active.number != id) return;
        active.cols = Math.max(2, Math.min(1000, cols));
        active.rows = Math.max(1, Math.min(1000, rows));
        resize(active);
      });
    }
  }

  private void resize(Session session) {
    if (closed || !session.running) return;
    final int cols = session.cols, rows = session.rows;
    io.execute(() -> {
      synchronized (session.resizeLock) {
        if (!session.running || !session.ttyFile.isFile()) return;
        if (session.lastCols == cols && session.lastRows == rows) return;
        try {
          String path = new String(java.nio.file.Files.readAllBytes(session.ttyFile.toPath()),
              StandardCharsets.US_ASCII).trim();
          if (!path.matches("/dev/pts/[0-9]+")) return;
          Process resize = new ProcessBuilder("/system/bin/stty", "-F", path,
              "rows", Integer.toString(rows), "cols", Integer.toString(cols)).start();
          if (!resize.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
            resize.destroyForcibly();
            if (session.running) debug("Resize timeout · " + path);
            return;
          }
          if (resize.exitValue() == 0) {
            session.lastCols = cols;
            session.lastRows = rows;
          } else if (session.running) {
            debug("Resize failed · " + path);
          }
        } catch (Exception failure) {
          if (session.running) debug("Resize · " + failure.getMessage());
        }
      }
    });
  }

  private void applyAppearance() {
    js("configure(" + prefs.getInt("size",13) + ","
        + JSONObject.quote(prefs.getBoolean("jetbrains",false) ? "JetBrains, monospace" : "monospace")
        + "," + prefs.getInt("theme",0) + ")");
  }

  private void appearance() {
    String[] themes = {"oTrail Dark", "AMOLED", "High contrast"};
    String[] items = {"Colors · " + themes[prefs.getInt("theme",0)],
        "Font · " + (prefs.getBoolean("jetbrains",false) ? "JetBrains Mono" : "System Mono"),
        "Size · " + prefs.getInt("size",13), "Toggle clipboard controls", "Toggle shell keyboard"};
    new AlertDialog.Builder(activity).setTitle("Terminal appearance").setItems(items,(d,i) -> {
      if (i == 0) new AlertDialog.Builder(activity).setItems(themes,(dialog,n) -> {
        prefs.edit().putInt("theme",n).apply(); applyAppearance();
      }).show();
      if (i == 1) {
        prefs.edit().putBoolean("jetbrains",!prefs.getBoolean("jetbrains",false)).apply(); applyAppearance();
      }
      if (i == 2) {
        String[] sizes = new String[15];
        for (int n=0;n<sizes.length;n++) sizes[n] = Integer.toString(10+n);
        new AlertDialog.Builder(activity).setTitle("Font size").setItems(sizes,(dialog,n) -> {
          prefs.edit().putInt("size",10+n).apply(); applyAppearance();
        }).show();
      }
      if (i == 4) {
        boolean visible = keypad.getVisibility() != View.VISIBLE;
        prefs.edit().putBoolean("keypad",visible).apply();
        keypad.setVisibility(visible ? View.VISIBLE : View.GONE);
      }
      if (i == 3) {
        boolean visible = tools.getVisibility() != View.VISIBLE;
        prefs.edit().putBoolean("tools",visible).apply();
        tools.setVisibility(visible ? View.VISIBLE : View.GONE);
      }
    }).show();
  }

  private View clipboardRow() {
    LinearLayout row = row();
    row.setPadding(dp(2), 0, dp(2), 0);
    row.addView(chip("COPY", this::copyTerminal), new LinearLayout.LayoutParams(0, -1, 1));
    row.addView(chip("PASTE", this::pasteTerminal), new LinearLayout.LayoutParams(0, -1, 1));
    row.addView(chip("SEL ALL", this::selectAll), new LinearLayout.LayoutParams(0, -1, 1));
    row.addView(chip("HIST", this::showHistory), new LinearLayout.LayoutParams(0, -1, 1));
    return row;
  }

  /** Keys are deliberately short, but tall enough for reliable touch input. */
  private View keyboardPanel() {
    LinearLayout panel = new LinearLayout(activity);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(3), dp(2), dp(3), dp(2));

    GradientDrawable background = new GradientDrawable();
    background.setColor(UiConfig.MODULE_SURFACE);
    background.setCornerRadius(dp(10));
    background.setStroke(dp(1), UiConfig.MODULE_DIVIDER);
    panel.setBackground(background);

    keyboardRows = new LinearLayout(activity);
    keyboardRows.setOrientation(LinearLayout.VERTICAL);
    panel.addView(keyboardRows, new LinearLayout.LayoutParams(-1, -2));
    buildKeyboard();
    return panel;
  }

  private void buildKeyboard() {
    if (keyboardRows == null) return;
    keyboardRows.removeAllViews();
    addUtilityRow();
    if (symbols) {
      addTextRow("~!@#$%^&*()");
      addTextRow("-_=+[]{}\\|");
      addTextRow(";:'\"<>/?,.");
      addTextRow("`€£§°±×÷©®");
      addBottomRow(true);
    } else {
      addTextRow("1234567890");
      addLetterRow("qwertyuiop");
      addLetterRow("asdfghjkl");
      addShiftRow();
      addBottomRow(false);
    }
  }

  private void addUtilityRow() {
    LinearLayout row = newRow();
    addKey(row, "ESC", 1, KEY_MOD, () -> sendKey("\u001b"));
    addKey(row, "TAB", 1, KEY_MOD, () -> sendKey("\t"));
    addKey(row, "^C", 1, KEY_MOD, () -> sendKey("\u0003"));
    addKey(row, "^D", 1, KEY_MOD, () -> sendKey("\u0004"));
    addKey(row, "^L", 1, KEY_MOD, () -> sendKey("\u000c"));
    addKey(row, "←", 1, KEY_MOD, () -> sendKey("\u001b[D"));
    addKey(row, "↑", 1, KEY_MOD, () -> sendKey("\u001b[A"));
    addKey(row, "↓", 1, KEY_MOD, () -> sendKey("\u001b[B"));
    addKey(row, "→", 1, KEY_MOD, () -> sendKey("\u001b[C"));
    addKeyboardRow(row);
  }

  private void addTextRow(String text) {
    LinearLayout row = newRow();
    for (char value : text.toCharArray()) {
      String keyText = String.valueOf(value);
      addKey(row, keyText, 1, KEY_NORMAL, () -> typeText(keyText));
    }
    addKeyboardRow(row);
  }

  private void addLetterRow(String letters) {
    LinearLayout row = newRow();
    for (char value : letters.toCharArray()) {
      String keyText = caps ? String.valueOf(Character.toUpperCase(value)) : String.valueOf(value);
      addKey(row, keyText, 1, KEY_NORMAL, () -> typeLetter(value));
    }
    addKeyboardRow(row);
  }

  private void addShiftRow() {
    LinearLayout row = newRow();
    addKey(row, "CAPS", 1.5f, caps ? KEY_ACTIVE : KEY_MOD, () -> {
      caps = !caps;
      rebuildKeyboard();
    });
    for (char value : "zxcvbnm".toCharArray()) {
      String keyText = caps ? String.valueOf(Character.toUpperCase(value)) : String.valueOf(value);
      addKey(row, keyText, 1, KEY_NORMAL, () -> typeLetter(value));
    }
    addKey(row, ",", 1, KEY_NORMAL, () -> typeText(","));
    addKey(row, ".", 1, KEY_NORMAL, () -> typeText("."));
    addKey(row, "?", 1, KEY_NORMAL, () -> typeText("?"));
    addKeyboardRow(row);
  }

  private void addBottomRow(boolean symbolsLayer) {
    LinearLayout row = newRow();
    addKey(row, symbolsLayer ? "ABC" : "?123", 1, KEY_MOD, () -> {
      symbols = !symbols;
      ctrl = false;
      rebuildKeyboard();
    });
    addKey(row, "CTRL", 1, ctrl ? KEY_ACTIVE : KEY_MOD, () -> {
      ctrl = !ctrl;
      rebuildKeyboard();
    });
    if (!symbolsLayer) addKey(row, "'", 1, KEY_NORMAL, () -> typeText("'"));
    addKey(row, "SPACE", 3, KEY_MOD, () -> typeText(" "));
    addKey(row, "⌫", 1, KEY_MOD, this::backspace);
    addKey(row, "ENTER", 2, KEY_ACTION, this::enter);
    addKeyboardRow(row);
  }

  private LinearLayout newRow() {
    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    return row;
  }

  private void addKeyboardRow(LinearLayout row) {
    keyboardRows.addView(row, new LinearLayout.LayoutParams(-1, dp(KEYBOARD_ROW_HEIGHT_DP)));
  }

  private void addKey(LinearLayout row, String text, float weight, int kind, Runnable action) {
    Button key = new Button(activity);
    key.setText(text);
    key.setAllCaps(false);
    key.setSingleLine(true);
    key.setGravity(Gravity.CENTER);
    key.setTextSize(11);
    key.setTextColor(kind == KEY_MOD ? UiConfig.MODULE_TEXT : 0xff000000);
    key.setTypeface(Typeface.create(UiConfig.BODY_FONT, Typeface.BOLD));
    key.setIncludeFontPadding(false);
    key.setStateListAnimator(null);
    key.setMinHeight(0);
    key.setMinimumHeight(0);
    key.setMinWidth(0);
    key.setMinimumWidth(0);
    key.setPadding(0, 0, 0, 0);
    key.setBackgroundTintList(null);

    int fill = keyColor(kind);
    GradientDrawable frame = new GradientDrawable();
    frame.setColor(fill);
    frame.setCornerRadius(dp(5));
    frame.setStroke(dp(1), kind == KEY_NORMAL ? UiConfig.MODULE_DIVIDER : fill);
    key.setBackground(new android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(kind == KEY_NORMAL ? 0x33000000 : 0x55ffffff),
        frame, null));
    key.setOnClickListener(view -> {
      try {
        action.run();
      } catch (Throwable error) {
        debug("FAIL · key · " + error);
      }
    });

    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, weight);
    params.setMargins(dp(1), dp(1), dp(1), dp(1));
    row.addView(key, params);
  }

  private int keyColor(int kind) {
    if (kind == KEY_MOD) return UiConfig.MODULE_SURFACE_ALT;
    if (kind == KEY_ACTION) return UiConfig.MODULE_ACCENT;
    if (kind == KEY_ACTIVE) return UiConfig.MODULE_ACCENT_ALT;
    return UiConfig.TERMINAL_KEY_BACKGROUND;
  }

  private void typeLetter(char value) {
    typeText(String.valueOf(value));
  }

  private void typeText(String text) {
    terminalInput = false;
    if (ctrl && text.length() == 1) {
      char code = control(text.charAt(0));
      ctrl = false;
      rebuildKeyboard();
      if (code != 0) send(active, String.valueOf(code));
      return;
    }
    if (caps && text.length() == 1 && Character.isLetter(text.charAt(0))) {
      text = text.toUpperCase(Locale.US);
    }
    if (active != null) active.line.append(text);
    send(active, text);
  }

  private char control(char value) {
    if (value >= 'a' && value <= 'z') return (char) (value - 'a' + 1);
    if (value >= 'A' && value <= 'Z') return (char) (value - 'A' + 1);
    if (value == ' ') return 0;
    if (value == '[') return 0x1b;
    if (value == '\\') return 0x1c;
    if (value == ']') return 0x1d;
    if (value == '^') return 0x1e;
    if (value == '_') return 0x1f;
    return value;
  }

  private void rebuildKeyboard() {
    if (keyboardRows != null) keyboardRows.post(this::buildKeyboard);
  }

  private void clearCtrl() {
    if (!ctrl) return;
    ctrl = false;
    rebuildKeyboard();
  }

  private void backspace() {
    terminalInput = false;
    clearCtrl();
    if (active != null && active.line.length() > 0) {
      active.line.deleteCharAt(active.line.length() - 1);
    }
    send(active, "\u007f");
  }

  private void enter() {
    terminalInput = false;
    clearCtrl();
    Session session = active;
    if (session != null) recordCommand(session);
    send(session, "\n");
  }

  private void sendKey(String code) {
    terminalInput = false;
    clearCtrl();
    send(active, code);
  }

  private void recordCommand(Session session) {
    String command = session.line.toString().trim();
    session.line.setLength(0);
    if (command.isEmpty()) return;
    if (!session.history.isEmpty() && session.history.get(session.history.size() - 1).equals(command)) return;
    session.history.add(command);
    scheduleSave();
  }

  private int dp(int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }

  private LinearLayout row() {
    LinearLayout layout = new LinearLayout(activity);
    layout.setGravity(Gravity.CENTER_VERTICAL);
    return layout;
  }

  private TextView label(String text, int size, int color) {
    TextView view = new TextView(activity);
    view.setText(text);
    view.setTextColor(color);
    view.setTextSize(size);
    view.setPadding(dp(4), dp(2), dp(4), dp(2));
    return view;
  }

  private Button chip(String text, Runnable action) {
    Button button = new Button(activity);
    button.setText(text);
    button.setTextSize(9);
    button.setTextColor(UiConfig.MODULE_BUTTON_TEXT);
    button.setIncludeFontPadding(false);
    button.setAllCaps(false);
    button.setSingleLine(true);
    button.setEllipsize(TextUtils.TruncateAt.END);
    button.setMinHeight(0);
    button.setMinimumHeight(0);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(dp(5), 0, dp(5), 0);
    button.setBackgroundTintList(null);

    GradientDrawable frame = new GradientDrawable();
    frame.setColor(UiConfig.TERMINAL_KEY_BACKGROUND);
    frame.setCornerRadius(dp(6));
    frame.setStroke(dp(1), UiConfig.MODULE_DIVIDER);
    button.setBackground(new android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(0x33ffffff), frame, null));
    button.setOnClickListener(view -> {
      debug("RUN · " + text);
      try {
        action.run();
      } catch (Throwable error) {
        debug("FAIL · " + text + " · " + error.getClass().getSimpleName()
            + ": " + String.valueOf(error.getMessage()));
      }
    });
    return button;
  }

  private void debug(String message) {
    ui(() -> {
      if (debugText == null) return;
      String time = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date());
      debugText.setText(time + " · " + message);
    });
  }

  private View navRow() {
    LinearLayout nav = row();
    String[][] items = {
        {"▣ FILES", "scp"},
        {"</> GOD", "god"},
        {">_ START", "start"},
        {"⚙ STORAGE", "storage"},
        {"↻ REINSTALL", "repair"}
    };
    for (String[] item : items) {
      Button button = chip(item[0], () -> runNav(item[1]));
      button.setTextSize(8);
      nav.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }
    return nav;
  }

  private void runNav(String action) {
    switch (action) {
      case "scp":
        scpSync();
        break;
      case "god":
        activity.finish();
        break;
      case "start":
        startShell();
        break;
      case "storage":
        storage();
        break;
      default:
        repair();
        break;
    }
  }

  private File godDirectory() {
    return godDirectory(host);
  }

  private File godDirectory(Host ownerHost) {
    try {
      return ownerHost.moduleDirectory("mod1");
    } catch (Exception error) {
      Activity owner = activity;
      return new File(owner == null ? new File("/") : owner.getFilesDir(), "modules/data/mod1");
    }
  }

  private File rootfs() {
    return new File(godDirectory(), "debian/rootfs");
  }

  private File rootfs(Host ownerHost) {
    return new File(godDirectory(ownerHost), "debian/rootfs");
  }

  private File bash() {
    return new File(rootfs(), "usr/bin/bash");
  }

  private File bash(Host ownerHost) {
    return new File(rootfs(ownerHost), "usr/bin/bash");
  }

  private void setup(boolean force) {
    io.execute(() -> {
      try {
        if (!force && bash().isFile()) {
          ui(this::ensureShells);
          return;
        }
        if (host.moduleVersion("mod1").isBlank()) {
          show("Install and activate GOD first — it now carries the Debian image.");
          return;
        }
        JSONObject state = new JSONObject(host.moduleTaskStatus("mod1"));
        if (!state.optBoolean("busy")) {
          show("Installing the Debian image from GOD…");
          host.startModuleTask("mod1", new JSONObject()
              .put("action", "shellSetup")
              .put("force", force)
              .toString());
        } else {
          show("Installing image · " + state.optInt("percent") + "%");
        }
        setupRequested = true;
      } catch (Exception error) {
        show("Setup unavailable: " + error.getMessage());
        debug("FAIL · setup · " + error);
      }
    });
  }

  private void repair() {
    closeSessions();
    setup(true);
  }

  private void startShell() {
    if (!bash().isFile()) {
      setup(false);
      return;
    }
    Session session = active;
    if (session == null) {
      newSession();
      return;
    }
    if (session.process != null) {
      show("This tab is already running");
      return;
    }
    session.exited = false;
    launch(session);
  }

  private final Runnable poll = this::pollNow;
  private final Runnable save = this::saveState;

  private void pollNow() {
    if (closed || activity == null) return;
    try {
      if (bash().isFile()) {
        setupRequested = false;
        ensureShells();
      } else {
        JSONObject state = new JSONObject(host.moduleTaskStatus("mod1"));
        String phase = state.optString("phase", "Waiting for GOD");
        if (state.optBoolean("busy")) phase += " · " + state.optInt("percent") + "%";
        show(phase);
        if (!state.optBoolean("busy") && setupRequested) {
          String log = state.optString("log", "");
          if (!log.isBlank()) append(active, tail(log, 400));
          setupRequested = false;
        }
      }
    } catch (Exception error) {
      debug("FAIL · status poll · " + error);
    }
    if (activity != null) handler.postDelayed(poll, 1500);
  }

  private void scheduleSave() {
    handler.removeCallbacks(save);
    handler.postDelayed(save, 1200);
  }

  /** Snapshot tabs so a process restart can restore their transcripts and history. */
  private void saveState() {
    Host owner = host;
    if (owner == null) return;
    try {
      JSONObject state = new JSONObject();
      state.put("active", active == null ? 0 : active.number);
      state.put("next", nextTab);
      JSONArray tabs = new JSONArray();
      for (Session session : sessions) {
        JSONObject item = new JSONObject();
        item.put("number", session.number);
        item.put("cols", session.cols);
        item.put("rows", session.rows);
        synchronized (session.scrollback) {
          item.put("scrollback", session.scrollback.toString());
        }
        JSONArray history = new JSONArray();
        for (String command : session.history) history.put(command);
        item.put("history", history);
        tabs.put(item);
      }
      state.put("tabs", tabs);
      File file = new File(owner.privateDirectory(), "sessions.json");
      byte[] bytes = state.toString().getBytes(StandardCharsets.UTF_8);
      io.execute(() -> {
        File temporary = new File(file.getPath() + ".tmp");
        try {
          java.nio.file.Files.write(temporary.toPath(), bytes);
          if (!temporary.renameTo(file)) {
            java.nio.file.Files.write(file.toPath(), bytes);
            temporary.delete();
          }
        } catch (Exception error) {
          debug("State save · " + error.getMessage());
        }
      });
    } catch (Exception error) {
      debug("State snapshot · " + error.getMessage());
    }
  }

  private int restoreState() {
    if (host == null || !sessions.isEmpty()) return 0;
    File file = new File(host.privateDirectory(), "sessions.json");
    if (!file.isFile()) return 0;
    try {
      JSONObject state = new JSONObject(new String(
          java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
      JSONArray tabs = state.optJSONArray("tabs");
      if (tabs == null) return 0;
      int activeNumber = state.optInt("active", 0);
      int maximum = UiConfig.TERMINAL_SCROLLBACK_CHARS;
      for (int index = 0; index < tabs.length(); index++) {
        JSONObject item = tabs.optJSONObject(index);
        if (item == null) continue;
        int number = item.optInt("number", 0);
        if (number <= 0) number = ++nextTab;
        else nextTab = Math.max(nextTab, number);
        Session session = new Session(number);
        String text = item.optString("scrollback", "");
        if (text.length() > maximum) text = text.substring(text.length() - maximum);
        session.scrollback.append(text);
        JSONArray history = item.optJSONArray("history");
        if (history != null) {
          for (int entry = 0; entry < history.length(); entry++) {
            String command = history.optString(entry, "");
            if (!command.isEmpty()) session.history.add(command);
          }
        }
        session.cols = Math.max(2, Math.min(1000, item.optInt("cols", 80)));
        session.rows = Math.max(1, Math.min(1000, item.optInt("rows", 24)));
        sessions.add(session);
      }
      return activeNumber;
    } catch (Exception error) {
      debug("State restore · " + error.getMessage());
      return 0;
    }
  }

  private void ensureShells() {
    if (closed || !bash().isFile()) return;
    if (sessions.isEmpty()) {
      Session session = createSession();
      switchSession(session);
    }
    for (Session session : new ArrayList<>(sessions)) {
      if (!session.exited && session.process == null && !session.launching) launch(session);
    }
  }

  private Session createSession() {
    Session session = new Session(++nextTab);
    sessions.add(session);
    return session;
  }

  private void newSession() {
    if (!bash().isFile()) {
      setup(false);
      return;
    }
    Session session = createSession();
    switchSession(session);
    buildTabs();
    launch(session);
    scheduleSave();
  }

  private void switchSession(Session session) {
    if (session == null || session == active) {
      if (terminalInput && terminal != null) js("focusTerminal(" + session.number + ")");
      else root.requestFocus();
      return;
    }
    active = session;
    js("select(" + session.number + ")");
    replay(session);
    buildTabs();
    scheduleSave();
    if (terminalInput && terminal != null) js("focusTerminal(" + session.number + ")");
    else root.requestFocus();
  }

  private void closeSession(Session session) {
    if (session == null) return;
    session.exited = true;
    session.running = false;
    Process process = session.process;
    if (process != null) process.destroy();
    sessions.remove(session);
    js("drop(" + session.number + ")");
    scheduleSave();
    if (active == session) {
      active = null;
      if (!sessions.isEmpty()) {
        switchSession(sessions.get(0));
      }
    }
    buildTabs();
  }

  private void buildTabs() {
    if (tabsRow == null) return;
    tabsRow.removeAllViews();
    for (Session session : sessions) {
      String title = (session == active ? "● " : "") + session.number + (session.running ? "" : " ×");
      Button tab = chip(title, () -> switchSession(session));
      tab.setTextSize(9);
      tab.setContentDescription("Switch to shell tab " + session.number);
      tab.setOnLongClickListener(view -> {
        closeSession(session);
        return true;
      });
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(52), dp(28));
      params.setMargins(dp(1), 0, dp(1), 0);
      tabsRow.addView(tab, params);
    }
    Button add = chip("+ TAB", this::newSession);
    add.setTextSize(9);
    tabsRow.addView(add, new LinearLayout.LayoutParams(dp(64), dp(28)));
  }

  private void launch(Session session) {
    if (closed || session == null || session.exited) return;
    Activity owner = activity;
    Host ownerHost = host;
    if (owner == null || ownerHost == null) return;
    synchronized (session) {
      if (session.process != null || session.launching) return;
      session.launching = true;
    }
    io.execute(() -> {
      try {
        File proot = new File(owner.getApplicationInfo().nativeLibraryDir, "libproot.so");
        File loader = new File(owner.getApplicationInfo().nativeLibraryDir, "libprootloader.so");
        File filesystem = rootfs(ownerHost);
        if (!proot.canExecute()) throw new IOException("Executable PRoot is missing from oTraiL");
        if (!loader.canExecute()) throw new IOException("Executable PRoot loader is missing from oTraiL");
        if (!bash(ownerHost).isFile()) throw new IOException("Install the shared Debian image from GOD first");

        List<String> command = new ArrayList<>(Arrays.asList(
            proot.getPath(),
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", filesystem.getCanonicalPath(),
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys"));
        bind(command, new File("/system"), "/system");
        bind(command, new File("/vendor"), "/vendor");
        bind(command, new File("/apex"), "/apex");
        bind(command, new File(owner.getApplicationInfo().dataDir), "/app-data");
        bind(command, godDirectory(ownerHost), "/god-data");
        bind(command, ownerHost.privateDirectory(), "/shell-data");
        bind(command, owner.getExternalFilesDir(null), "/external-app");
        if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) {
          bind(command, Environment.getExternalStorageDirectory(), "/shared-storage");
        }
        try {
          bind(command, procStatFile(ownerHost), "/proc/stat");
        } catch (Exception error) {
          debug("proc-stat unavailable · " + error.getMessage());
        }
        command.add("-w");
        command.add("/root");
        command.add("/usr/bin/script");
        command.add("-qfec");
        command.add("stty rows " + session.rows + " cols " + session.cols
            + "; tty > /shell-data/" + session.ttyFile.getName()
            + "; exec /usr/bin/bash --noprofile --norc -i");
        command.add("/dev/null");

        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("HOME", "/root");
        builder.environment().put("SHELL", "/usr/bin/bash");
        builder.environment().put("TERM", "xterm-256color");
        builder.environment().put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        builder.environment().put("LANG", "C.UTF-8");
        builder.environment().put("PROOT_TMP_DIR", owner.getCacheDir().getPath());
        builder.environment().put("PROOT_LOADER", loader.getPath());

        Process process = builder.start();
        synchronized (session) {
          if (closed || session.exited) {
            process.destroy();
            return;
          }
          session.process = process;
          session.input = process.getOutputStream();
          session.running = true;
          session.launching = false;
        }
        append(session, "\n[Tab " + session.number
            + " ready · long-press a tab to close it · exit ends this shell]\n");
        ui(() -> {
          show("Ready · " + sessions.size() + " tab(s)");
          buildTabs();
        });
        io.execute(() -> read(session, process));
        io.execute(() -> waitFor(session, process));
      } catch (Exception error) {
        synchronized (session) {
          session.launching = false;
        }
        append(session, "\n[Launch failed: " + error.getMessage() + "]\n");
        show("Launch failed: " + error.getMessage());
        debug("FAIL · launch · " + error);
      }
    });
  }

  /**
   * Android kernels may hide /proc/stat's btime line from PRoot guests, which makes
   * procps print "unable to get system boot time". Bind a small compatible stat file.
   */
  private File procStatFile(Host ownerHost) throws IOException {
    File result = new File(ownerHost.privateDirectory(), "proc-stat");
    String stat = "";
    try {
      stat = new String(java.nio.file.Files.readAllBytes(
          java.nio.file.Paths.get("/proc/stat")), StandardCharsets.UTF_8);
    } catch (Exception ignored) {
      // The host may deny /proc/stat; the synthetic btime below is enough for procps.
    }
    if (!stat.contains("btime ")) {
      long boot = (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 1000L;
      if (stat.isEmpty()) stat = "cpu  0 0 0 0 0 0 0 0 0 0\n";
      if (!stat.endsWith("\n")) stat += "\n";
      stat += "btime " + boot + "\n";
    }
    java.nio.file.Files.write(result.toPath(), stat.getBytes(StandardCharsets.UTF_8));
    result.setReadable(true, false);
    return result;
  }

  private void bind(List<String> command, File file, String guest) throws IOException {
    if (file == null || !file.exists()) return;
    command.add("-b");
    command.add(file.getCanonicalPath() + ":" + guest);
  }

  private void read(Session session, Process process) {
    try (java.io.Reader input = new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
      char[] buffer = new char[8192];
      int count;
      while ((count = input.read(buffer)) >= 0) {
        if (!session.sizeSent && session.ttyFile.length() > 0) {
          session.sizeSent = true; resize(session);
        }
        append(session, new String(buffer, 0, count));
      }
    } catch (Exception error) {
      if (!closed && process.isAlive()) append(session, "\n[output error: " + error + "]\n");
      debug("FAIL · shell output · " + error);
    }
  }

  private void waitFor(Session session, Process process) {
    int code = -1;
    try {
      code = process.waitFor();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      debug("FAIL · shell wait interrupted");
    }
    synchronized (session) {
      if (session.process == process) {
        session.process = null;
        session.input = null;
        session.running = false;
        session.exited = true;
      }
    }
    session.ttyFile.delete();
    int exit = code;
    append(session, "\n[Shell exited " + exit
        + (exit == 159 ? " · Android blocked PRoot; tap REINSTALL" : "") + "]\n");
    ui(() -> {
      show("Tab " + session.number + " exited · " + exit);
      buildTabs();
    });
  }

  private void send(Session session, String text) {
    if (session == null) {
      show("Shell is not running · tap + TAB");
      return;
    }
    OutputStream output = session.input;
    if (output == null) {
      show("Tab " + session.number + " is not running · tap START");
      return;
    }
    inputQueue.execute(() -> {
      try {
        output.write(text.getBytes(StandardCharsets.UTF_8));
        output.flush();
      } catch (Exception error) {
        show("Send failed on tab " + session.number);
        debug("FAIL · send · " + error);
      }
    });
  }

  private void append(Session session, String chunk) {
    if (session == null || chunk == null || chunk.isEmpty()) return;
    synchronized (session.scrollback) {
      session.scrollback.append(chunk);
      int maximum = UiConfig.TERMINAL_SCROLLBACK_CHARS;
      if (session.scrollback.length() > maximum)
        session.scrollback.delete(0, session.scrollback.length() - maximum);
    }
    synchronized (session.pending) {
      session.pending.append(chunk);
      if (session.frameScheduled) return;
      session.frameScheduled = true;
    }
    handler.postDelayed(() -> {
      String frame;
      synchronized (session.pending) {
        frame = session.pending.toString();
        session.pending.setLength(0);
        session.frameScheduled = false;
      }
      if (sessions.contains(session) && hydrated.contains(session.number))
        js("tabs.get(" + session.number + ").term.write(" + JSONObject.quote(frame) + ")");
    }, 16);
  }

  /** Replay Java-side scrollback when a WebView tab is first shown after reattachment. */
  private void replay(Session session) {
    if (session == null || !hydrated.add(session.number)) return;
    String text;
    synchronized (session.scrollback) {
      text = session.scrollback.toString();
    }
    if (!text.isEmpty()) {
      js("tabs.get(" + session.number + ").term.write(" + JSONObject.quote(text) + ")");
    }
  }

  private void copyTerminal() {
    if (!pageReady) return;
    terminal.evaluateJavascript("selected()", value -> {
      if (closed) return;
      try {
        String text = new org.json.JSONArray("[" + value + "]").getString(0);
        if (text.isEmpty()) { show("Nothing to copy"); return; }
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("oTraiL shell", text));
        show("Copied " + text.length() + " characters");
      } catch (Exception failure) { debug("Copy failed · " + failure); }
    });
  }

  private void pasteTerminal() {
    Session session = active;
    if (session == null) return;
    ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
    if (!clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null) {
      show("Clipboard is empty");
      return;
    }
    ClipData clip = clipboard.getPrimaryClip();
    StringBuilder text = new StringBuilder();
    for (int index = 0; index < clip.getItemCount(); index++) {
      CharSequence item = clip.getItemAt(index).coerceToText(activity);
      if (item != null) text.append(item);
    }
    if (text.length() == 0) {
      show("Clipboard is empty");
      return;
    }
    session.line.append(text);
    send(session, text.toString());
    show("Pasted " + text.length() + " characters");
  }

  private void selectAll() {
    js("if(active) active.term.selectAll()");
  }

  private void showHistory() {
    Session session = active;
    if (session == null || session.history.isEmpty()) {
      show("No commands recorded in this tab");
      return;
    }
    String[] commands = session.history.toArray(new String[0]);
    new AlertDialog.Builder(activity)
        .setTitle("Tab " + session.number + " history")
        .setItems(commands, (dialog, index) -> {
          session.line.append(commands[index]);
          enter();
        })
        .setNegativeButton("Close", null)
        .show();
  }

  private void show(String message) {
    ui(() -> {
      if (status != null) status.setText(message);
    });
  }

  private void ui(Runnable action) {
    Activity current = activity;
    if (current == null || closed) return;
    current.runOnUiThread(() -> {
      if (!closed && !current.isDestroyed()) action.run();
    });
  }

  private void storage() {
    if (Build.VERSION.SDK_INT < 30) return;
    try {
      activity.startActivity(new Intent(
          Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
          Uri.parse("package:" + activity.getPackageName())));
    } catch (Exception error) {
      try {
        activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
      } catch (Exception ignored) {
        // The device may not expose the all-files settings page.
      }
    }
  }

  private void scpSync() {
    try {
      File config = new File(activity.getFilesDir(), "modules/data/mod1/scp.json");
      JSONObject settings = StudioFiles.json(config);
      String remote = settings.getString("user") + "@" + settings.getString("host")
          + ":" + settings.getString("path");
      String destination = "/app-data/files/modules/data/mod1/projects/scp-workspace";
      send(active, "mkdir -p " + quote(destination)
          + " && scp -r " + quote(remote) + "/. " + quote(destination)
          + "/ && printf '%s' '{\"path\":\"/data/data/" + activity.getPackageName()
          + "/files/modules/data/mod1/projects/scp-workspace\"}'"
          + " > /app-data/files/modules/data/mod1/project.json\n");
    } catch (Exception error) {
      show("Configure SCP in GOD first: " + error.getMessage());
      debug("FAIL · SCP · " + error);
    }
  }

  private String quote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  private void exitActive() {
    Session session = active;
    if (session == null) return;
    if (session.input == null) {
      closeSession(session);
      return;
    }
    send(session, "exit\n");
    io.execute(() -> {
      try {
        Process process = session.process;
        if (process == null) return;
        if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
          process.destroy();
          if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
    });
  }

  private void exitAll() {
    closeSessions();
    if (host != null) host.keepProcessAlive(false);
    if (activity != null) activity.finish();
  }

  private void closeSessions() {
    handler.removeCallbacks(save);
    if (host != null) {
      new File(host.privateDirectory(), "sessions.json").delete();
      new File(host.privateDirectory(), "sessions.json.tmp").delete();
    }
    for (Session session : new ArrayList<>(sessions)) {
      session.exited = true;
      session.running = false;
      Process process = session.process;
      if (process != null) process.destroy();
    }
    for (Session session : sessions) js("drop(" + session.number + ")");
    sessions.clear();
    active = null;
    buildTabs();
  }

  private static String tail(String text, int count) {
    return text.length() <= count ? text : text.substring(text.length() - count);
  }

  @Override public void task(File packageFile, File data, String request, Progress progress) throws Exception {
    StudioTasks.run(packageFile, data, request, progress);
  }

  /** Detach the current Activity/WebView while keeping PTYs, tabs and scrollback alive. */
  @Override public void detach() {
    handler.removeCallbacks(poll);
    handler.removeCallbacks(save);
    if (host != null) host.keepProcessAlive(!sessions.isEmpty());
    if (!sessions.isEmpty()) saveState();
    if (terminal != null) {
      if (root != null) root.removeView(terminal);
      terminal.removeJavascriptInterface("Shell");
      terminal.destroy();
    }
    terminal = null;
    pageReady = false;
    waitingScripts.clear();
    hydrated.clear();
    root = null;
    tabsRow = null;
    keyboardRows = null;
    status = null;
    tools = null;
    keypad = null;
    debugText = null;
    activity = null;
    host = null;
    terminalInput = false;
  }

  @Override public void pause() {
    if (host != null && !sessions.isEmpty()) host.keepProcessAlive(true);
    if (!sessions.isEmpty()) saveState();
  }

  @Override public void close() {
    closed = true;
    handler.removeCallbacks(poll);
    closeSessions();
    io.shutdownNow();
    inputQueue.shutdownNow();
    if (terminal != null) {
      if (root != null) root.removeView(terminal);
      terminal.removeJavascriptInterface("Shell");
      terminal.destroy();
    }
    if (host != null) host.keepProcessAlive(false);
    activity = null;
    host = null;
  }

  private final class Session {
    private final int number;
    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder scrollback = new StringBuilder();
    private final StringBuilder line = new StringBuilder();
    private final List<String> history = new ArrayList<>();
    private final File ttyFile;
    private final Object resizeLock = new Object();
    private volatile int cols = 80, rows = 24;
    private volatile boolean running;
    private boolean sizeSent, frameScheduled, exited, launching;
    private int lastCols = -1, lastRows = -1;
    private Process process;
    private OutputStream input;
    private Session(int number) {
      this.number = number;
      ttyFile = new File(host.privateDirectory(), "tty-" + java.util.UUID.randomUUID());
    }
  }

  /** Hardware keyboards get the same editing/cursor and clipboard shortcuts as the keypad. */
  private final class KeyRoot extends LinearLayout {
    private KeyRoot(Context context) {
      super(context);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
      if (event.getAction() != KeyEvent.ACTION_DOWN) {
        return super.dispatchKeyEvent(event);
      }
      int key = event.getKeyCode();
      boolean control = event.isCtrlPressed();
      boolean shift = event.isShiftPressed();

      if (control && shift && key == KeyEvent.KEYCODE_C) {
        copyTerminal();
        return true;
      }
      if (control && shift && key == KeyEvent.KEYCODE_V) {
        pasteTerminal();
        return true;
      }
      if (control && shift && key == KeyEvent.KEYCODE_A) {
        selectAll();
        return true;
      }
      if (terminal.hasFocus()) return super.dispatchKeyEvent(event);
      if (control && key >= KeyEvent.KEYCODE_A && key <= KeyEvent.KEYCODE_Z) {
        sendKey(String.valueOf((char) (key - KeyEvent.KEYCODE_A + 1)));
        return true;
      }

      switch (key) {
        case KeyEvent.KEYCODE_DEL:
          backspace();
          return true;
        case KeyEvent.KEYCODE_FORWARD_DEL:
          sendKey("\u001b[3~");
          return true;
        case KeyEvent.KEYCODE_ENTER:
        case KeyEvent.KEYCODE_NUMPAD_ENTER:
          enter();
          return true;
        case KeyEvent.KEYCODE_TAB:
          sendKey("\t");
          return true;
        case KeyEvent.KEYCODE_ESCAPE:
          sendKey("\u001b");
          return true;
        case KeyEvent.KEYCODE_DPAD_LEFT:
          sendKey("\u001b[D");
          return true;
        case KeyEvent.KEYCODE_DPAD_UP:
          sendKey("\u001b[A");
          return true;
        case KeyEvent.KEYCODE_DPAD_DOWN:
          sendKey("\u001b[B");
          return true;
        case KeyEvent.KEYCODE_DPAD_RIGHT:
          sendKey("\u001b[C");
          return true;
        case KeyEvent.KEYCODE_MOVE_HOME:
          sendKey("\u001b[H");
          return true;
        case KeyEvent.KEYCODE_MOVE_END:
          sendKey("\u001b[F");
          return true;
        default:
          break;
      }

      int unicode = event.getUnicodeChar(event.getMetaState());
      if (unicode >= 32 && unicode != 127) {
        typeText(String.valueOf((char) unicode));
        return true;
      }
      return super.dispatchKeyEvent(event);
    }
  }
}
