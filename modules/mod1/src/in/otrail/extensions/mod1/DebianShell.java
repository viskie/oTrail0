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
import android.widget.ScrollView;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
  private static final int KEYBOARD_ROW_HEIGHT_DP = 20;

  private Activity activity;
  private Host host;
  private LinearLayout root;
  private LinearLayout tabsRow;
  private LinearLayout keyboardRows;
  private TextView status;
  private TextView terminal;
  private TextView debugText;
  private ScrollView terminalScroll;

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
  private boolean switchingTab;
  private int nextTab;

  @Override public String id() {
    return "mod3";
  }

  @Override public View create(Activity activity, Host host) {
    this.activity = activity;
    this.host = host;
    try {
      activity.getWindow().setSoftInputMode(
          WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
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
    root.addView(clipboardRow(), new LinearLayout.LayoutParams(-1, dp(24)));
    root.addView(keyboardPanel(), new LinearLayout.LayoutParams(-1, -2));

    debugText = label("ready", 9, UiConfig.MODULE_MUTED);
    debugText.setSingleLine(true);
    debugText.setEllipsize(TextUtils.TruncateAt.START);
    root.addView(debugText);
    root.addView(navRow());

    root.requestFocus();
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
    terminal = label("", UiConfig.TERMINAL_TEXT_SIZE_SP, UiConfig.TERMINAL_TEXT);
    terminal.setTypeface(Typeface.MONOSPACE);
    terminal.setTextIsSelectable(true);
    terminal.setLineSpacing(0, UiConfig.TERMINAL_LINE_SPACING);
    terminal.setText("Waiting for the shared Debian image…");

    terminalScroll = new ScrollView(activity);
    terminalScroll.setFillViewport(true);
    terminalScroll.setVerticalScrollBarEnabled(true);
    terminalScroll.addView(terminal, new ScrollView.LayoutParams(-1, -2));
    terminalScroll.setOnScrollChangeListener((view, scrollX, scrollY, oldX, oldY) -> {
      if (switchingTab || active == null) return;
      View child = terminalScroll.getChildAt(0);
      if (child == null) return;
      int maximum = Math.max(0, child.getHeight() - terminalScroll.getHeight());
      active.stickToBottom = maximum - scrollY <= dp(24);
      active.savedScroll = scrollY;
    });

    LinearLayout frame = new LinearLayout(activity);
    GradientDrawable background = new GradientDrawable();
    background.setColor(UiConfig.TERMINAL_BACKGROUND);
    background.setCornerRadius(dp(10));
    frame.setBackground(background);
    frame.setPadding(dp(7), dp(5), dp(7), dp(5));
    frame.addView(terminalScroll, new LinearLayout.LayoutParams(-1, -1));
    return frame;
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

  /** Keys are deliberately short; the reclaimed height belongs to the terminal. */
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
    key.setTextSize(8);
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
    clearCtrl();
    if (active != null && active.line.length() > 0) {
      active.line.deleteCharAt(active.line.length() - 1);
    }
    send(active, "\u007f");
  }

  private void enter() {
    clearCtrl();
    Session session = active;
    if (session != null) recordCommand(session);
    send(session, "\n");
  }

  private void sendKey(String code) {
    clearCtrl();
    send(active, code);
  }

  private void recordCommand(Session session) {
    String command = session.line.toString().trim();
    session.line.setLength(0);
    if (command.isEmpty()) return;
    if (!session.history.isEmpty() && session.history.get(session.history.size() - 1).equals(command)) return;
    session.history.add(command);
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
      nav.addView(button, new LinearLayout.LayoutParams(0, dp(UiConfig.MODULE_NAV_HEIGHT_DP), 1));
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
    try {
      return host.moduleDirectory("mod1");
    } catch (Exception error) {
      return new File(activity.getFilesDir(), "modules/data/mod1");
    }
  }

  private File rootfs() {
    return new File(godDirectory(), "debian/rootfs");
  }

  private File bash() {
    return new File(rootfs(), "usr/bin/bash");
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

  private void pollNow() {
    if (closed) return;
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
    handler.postDelayed(poll, 1500);
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
  }

  private void switchSession(Session session) {
    if (session == null || session == active) {
      root.requestFocus();
      return;
    }
    if (active != null) active.savedScroll = terminalScroll.getScrollY();
    active = session;
    switchingTab = true;
    terminal.setText(session.transcript.toString());
    terminalScroll.post(() -> {
      View child = terminalScroll.getChildAt(0);
      int maximum = child == null ? 0 : Math.max(0, child.getHeight() - terminalScroll.getHeight());
      terminalScroll.scrollTo(0, session.stickToBottom ? maximum : session.savedScroll);
      switchingTab = false;
    });
    buildTabs();
    root.requestFocus();
  }

  private void closeSession(Session session) {
    if (session == null) return;
    session.exited = true;
    session.running = false;
    Process process = session.process;
    if (process != null) process.destroy();
    sessions.remove(session);
    if (active == session) {
      active = null;
      if (sessions.isEmpty()) {
        terminal.setText("");
      } else {
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
    synchronized (session) {
      if (session.process != null || session.launching) return;
      session.launching = true;
    }
    io.execute(() -> {
      try {
        File proot = new File(activity.getApplicationInfo().nativeLibraryDir, "libproot.so");
        File loader = new File(activity.getApplicationInfo().nativeLibraryDir, "libprootloader.so");
        File filesystem = rootfs();
        if (!proot.canExecute()) throw new IOException("Executable PRoot is missing from oTraiL");
        if (!loader.canExecute()) throw new IOException("Executable PRoot loader is missing from oTraiL");
        if (!bash().isFile()) throw new IOException("Install the shared Debian image from GOD first");

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
        bind(command, new File(activity.getApplicationInfo().dataDir), "/app-data");
        bind(command, godDirectory(), "/god-data");
        bind(command, host.privateDirectory(), "/shell-data");
        bind(command, activity.getExternalFilesDir(null), "/external-app");
        if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) {
          bind(command, Environment.getExternalStorageDirectory(), "/shared-storage");
        }
        command.add("-w");
        command.add("/root");
        command.add("/usr/bin/script");
        command.add("-qfec");
        command.add("/usr/bin/bash --noprofile --norc -i");
        command.add("/dev/null");

        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("HOME", "/root");
        builder.environment().put("SHELL", "/usr/bin/bash");
        builder.environment().put("TERM", "xterm-256color");
        builder.environment().put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        builder.environment().put("LANG", "C.UTF-8");
        builder.environment().put("PROOT_TMP_DIR", activity.getCacheDir().getPath());
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

  private void bind(List<String> command, File file, String guest) throws IOException {
    if (file == null || !file.exists()) return;
    command.add("-b");
    command.add(file.getCanonicalPath() + ":" + guest);
  }

  private void read(Session session, Process process) {
    try (InputStream input = process.getInputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) >= 0) {
        append(session, new String(buffer, 0, count, StandardCharsets.UTF_8));
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
    if (session == null || chunk == null) return;
    String clean = clean(chunk);
    if (clean.isEmpty()) return;
    String display;
    boolean trimmed = false;
    synchronized (session.transcript) {
      session.transcript.append(clean);
      if (session.transcript.length() > UiConfig.TERMINAL_SCROLLBACK_CHARS) {
        session.transcript.delete(0, session.transcript.length() - UiConfig.TERMINAL_SCROLLBACK_CHARS);
        trimmed = true;
      }
      display = session.transcript.toString();
    }
    boolean replace = trimmed;
    boolean follow = session.stickToBottom;
    ui(() -> {
      if (closed || active != session) return;
      if (replace) {
        terminal.setText(display);
      } else {
        terminal.append(clean);
      }
      if (follow) {
        session.stickToBottom = true;
        scrollToEnd(session);
      }
    });
  }

  private String clean(String value) {
    String text = value
        .replaceAll("\u001B\\][^\u0007]*(?:\u0007|\u001B\\\\)", "")
        .replaceAll("\u001B\\[[0-?]*[ -/]*[@-~]", "")
        .replaceAll("\u001B[@-_]", "");
    return text.replace("\r\n", "\n").replace('\r', '\n');
  }

  private void scrollToEnd(Session session) {
    ScrollView scroll = terminalScroll;
    if (scroll == null) return;
    scroll.post(() -> {
      if (!closed && active == session && session.stickToBottom) {
        scroll.fullScroll(View.FOCUS_DOWN);
      }
    });
  }

  private void copyTerminal() {
    Session session = active;
    if (session == null) return;
    int start = terminal.getSelectionStart();
    int end = terminal.getSelectionEnd();
    CharSequence text;
    if (start >= 0 && end > start) {
      text = terminal.getText().subSequence(start, end);
    } else {
      text = session.transcript.toString();
    }
    if (text.length() == 0) {
      show("Nothing to copy");
      return;
    }
    ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(ClipData.newPlainText("oTraiL shell", text));
    show("Copied " + text.length() + " characters");
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
    if (terminal == null) return;
    CharSequence text = terminal.getText();
    if (text instanceof android.text.Spannable) {
      android.text.Selection.setSelection(
          (android.text.Spannable) text, 0, text.length());
    }
    terminal.requestFocus();
    show("Selected all terminal text");
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
    if (activity != null) activity.finish();
  }

  private void closeSessions() {
    for (Session session : new ArrayList<>(sessions)) {
      session.exited = true;
      session.running = false;
      Process process = session.process;
      if (process != null) process.destroy();
    }
    sessions.clear();
    active = null;
    if (terminal != null) terminal.setText("");
    buildTabs();
  }

  private static String tail(String text, int count) {
    return text.length() <= count ? text : text.substring(text.length() - count);
  }

  @Override public void task(File packageFile, File data, String request, Progress progress) throws Exception {
    StudioTasks.run(packageFile, data, request, progress);
  }

  @Override public void close() {
    closed = true;
    handler.removeCallbacks(poll);
    closeSessions();
    io.shutdownNow();
    inputQueue.shutdownNow();
    activity = null;
    host = null;
  }

  private final class Session {
    private final int number;
    private final StringBuilder transcript = new StringBuilder();
    private final StringBuilder line = new StringBuilder();
    private final List<String> history = new ArrayList<>();
    private Process process;
    private OutputStream input;
    private boolean running;
    private boolean exited;
    private boolean launching;
    private boolean stickToBottom = true;
    private int savedScroll;

    private Session(int number) {
      this.number = number;
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
