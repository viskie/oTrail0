package in.otrail;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Compact Debian shell pinned in the main UI instead of opening the full module screen. */
final class InlineShellWidget extends LinearLayout {
  private Activity activity;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newCachedThreadPool();
  private final ExecutorService inputQueue = Executors.newSingleThreadExecutor();
  private final StringBuilder transcript = new StringBuilder();
  private TextView status, output;
  private ScrollView scroll;
  private EditText input;
  private volatile Process process;
  private volatile OutputStream stdin;
  private boolean closed, setupRequested, launchRequested, stickToBottom = true;

  InlineShellWidget(Activity activity) {
    super(activity);
    this.activity = activity;
    setOrientation(VERTICAL);
    setPadding(dp(6), dp(5), dp(6), dp(6));
    GradientDrawable background = new GradientDrawable();
    background.setColor(UiConfig.MODULE_BACKGROUND);
    background.setCornerRadius(dp(9));
    background.setStroke(dp(1), UiConfig.MODULE_DIVIDER);
    setBackground(background);
    addView(header());
    addView(terminal(), new LayoutParams(-1, 0, 1));
    addView(inputRow());
    addView(controlRow());
    append("[In-app Debian shell]\nTap the shell icon to switch panels.\n");
    ensureShell();
  }

  private View header() {
    LinearLayout header = new LinearLayout(activity);
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView dot = label("●", 11, UiConfig.MODULE_DOT_ONLINE);
    header.addView(dot);
    TextView title = label("Debian shell · in-app", 13, UiConfig.MODULE_TEXT);
    title.setTypeface(Typeface.create(UiConfig.STATUS_FONT, Typeface.BOLD));
    header.addView(title, new LayoutParams(0, -2, 1));
    status = label("checking image…", 10, UiConfig.MODULE_MUTED);
    status.setSingleLine(true);
    status.setEllipsize(TextUtils.TruncateAt.START);
    header.addView(status);
    return header;
  }

  private View terminal() {
    scroll = new ScrollView(activity);
    scroll.setFillViewport(true);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
      View child = scroll.getChildAt(0);
      if (child != null) stickToBottom = child.getHeight() - scroll.getHeight() - y <= dp(24);
    });
    GradientDrawable background = new GradientDrawable();
    background.setColor(UiConfig.TERMINAL_BACKGROUND);
    background.setCornerRadius(dp(7));
    scroll.setBackground(background);
    output = new TextView(activity);
    output.setTextColor(UiConfig.TERMINAL_TEXT);
    output.setTextSize(UiConfig.TERMINAL_TEXT_SIZE_SP);
    output.setTypeface(Typeface.MONOSPACE);
    output.setTextIsSelectable(true);
    output.setPadding(dp(7), dp(5), dp(7), dp(5));
    scroll.addView(output, new LayoutParams(-1, -2));
    return scroll;
  }

  private View inputRow() {
    LinearLayout row = new LinearLayout(activity);
    row.setGravity(Gravity.CENTER_VERTICAL);
    input = new EditText(activity);
    input.setSingleLine(true);
    input.setTextColor(UiConfig.TERMINAL_INPUT_TEXT);
    input.setHintTextColor(UiConfig.MODULE_MUTED);
    input.setHint("command");
    input.setTextSize(UiConfig.TERMINAL_TEXT_SIZE_SP);
    input.setTypeface(Typeface.MONOSPACE);
    input.setBackgroundColor(UiConfig.TERMINAL_INPUT_BACKGROUND);
    input.setPadding(dp(7), 0, dp(7), 0);
    input.setImeOptions(EditorInfo.IME_ACTION_SEND);
    input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    input.setOnEditorActionListener((v, action, event) -> { submit(); return true; });
    row.addView(input, new LayoutParams(0, dp(36), 1));
    row.addView(chip("SEND", this::submit), new LayoutParams(dp(58), dp(36)));
    return row;
  }

  private View controlRow() {
    LinearLayout row = new LinearLayout(activity);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.addView(chip("^C", () -> send("\u0003")), new LayoutParams(0, dp(30), 1));
    row.addView(chip("TAB", () -> send("\t")), new LayoutParams(0, dp(30), 1));
    row.addView(chip("↑", () -> send("\u001b[A")), new LayoutParams(0, dp(30), 1));
    row.addView(chip("↓", () -> send("\u001b[B")), new LayoutParams(0, dp(30), 1));
    row.addView(chip("COPY", this::copyOutput), new LayoutParams(0, dp(30), 1));
    row.addView(chip("PASTE", this::pasteInput), new LayoutParams(0, dp(30), 1));
    return row;
  }

  private void copyOutput() {
    String text = output == null ? "" : output.getText().toString();
    if (text.isEmpty()) {
      status.setText("nothing to copy");
      return;
    }
    ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(ClipData.newPlainText("oTraiL shell", text));
    status.setText("copied " + text.length() + " chars");
  }

  private void pasteInput() {
    ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
    if (!clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null) {
      status.setText("clipboard empty");
      return;
    }
    ClipData clip = clipboard.getPrimaryClip();
    StringBuilder text = new StringBuilder();
    for (int index = 0; index < clip.getItemCount(); index++) {
      CharSequence part = clip.getItemAt(index).coerceToText(activity);
      if (part != null) text.append(part);
    }
    input.setText(text);
    input.setSelection(input.length());
    input.requestFocus();
  }

  private TextView label(String text, int size, int color) {
    TextView view = new TextView(activity);
    view.setText(text);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setPadding(dp(4), 0, dp(4), 0);
    return view;
  }

  private Button chip(String text, Runnable action) {
    Button button = new Button(activity);
    button.setText(text);
    button.setAllCaps(false);
    button.setSingleLine(true);
    button.setTextSize(10);
    button.setTypeface(Typeface.create(UiConfig.BODY_FONT, Typeface.BOLD));
    button.setTextColor(UiConfig.MODULE_BUTTON_TEXT);
    button.setMinHeight(0);
    button.setMinimumHeight(0);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(0, 0, 0, 0);
    button.setBackgroundTintList(null);
    GradientDrawable frame = new GradientDrawable();
    frame.setColor(UiConfig.TERMINAL_KEY_BACKGROUND);
    frame.setCornerRadius(dp(6));
    frame.setStroke(dp(1), UiConfig.MODULE_DIVIDER);
    button.setBackground(new android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(0x33ffffff), frame, null));
    button.setOnClickListener(v -> action.run());
    return button;
  }

  private File moduleData() {
    return new File(activity.getFilesDir(), "modules/data/mod3");
  }

  private File godData() {
    return new File(activity.getFilesDir(), "modules/data/mod1");
  }

  private File rootfs() {
    return new File(godData(), "debian/rootfs");
  }

  private File bash() {
    return new File(rootfs(), "usr/bin/bash");
  }

  private void ensureShell() {
    if (closed) return;
    if (bash().isFile()) {
      status.setText("ready");
      launch();
      return;
    }
    ModuleStore store = new ModuleStore(activity);
    String version = store.active("mod1");
    if (!store.enabled("mod1")) {
      status.setText("GOD image module inactive");
      append("\n[Activate GOD in Extension modules; it now carries the Debian image]\n");
      return;
    }
    if (setupRequested) return;
    setupRequested = true;
    try {
      JSONObject state = new JSONObject(ModuleTaskService.readStatus(activity, "mod1"));
      if (!state.optBoolean("busy")) {
        status.setText("installing image…");
        activity.startForegroundService(new Intent(activity, ModuleTaskService.class)
            .putExtra("id", "mod1")
            .putExtra("version", version)
            .putExtra("request", new JSONObject().put("action", "shellSetup").toString()));
      } else status.setText("installing image… " + state.optInt("percent") + "%");
    } catch (Exception error) {
      status.setText("setup failed");
      append("\n[Debian setup failed: " + error + "]\n");
      return;
    }
    handler.postDelayed(poll, 1500);
  }

  private final Runnable poll = new Runnable() {
    @Override public void run() {
      if (closed || !setupRequested) return;
      if (bash().isFile()) {
        status.setText("ready");
        launch();
        return;
      }
      try {
        JSONObject state = new JSONObject(ModuleTaskService.readStatus(activity, "mod1"));
        status.setText(state.optString("phase", "installing") +
            (state.optBoolean("busy") ? " · " + state.optInt("percent") + "%" : ""));
        if (!state.optBoolean("busy")) {
          String log = state.optString("log", "");
          if (!log.isBlank()) append("\n" + tail(log, 400) + "\n");
          append("\n[Debian image is not ready · long-press the icon to close, then try again]\n");
          return;
        }
      } catch (Exception error) {
        status.setText("status unavailable");
      }
      handler.postDelayed(this, 1500);
    }
  };

  private void launch() {
    if (closed || launchRequested || process != null) return;
    launchRequested = true;
    status.setText("starting…");
    io.execute(() -> {
      try {
        File proot = new File(activity.getApplicationInfo().nativeLibraryDir, "libproot.so");
        File loader = new File(activity.getApplicationInfo().nativeLibraryDir, "libprootloader.so");
        File fs = rootfs();
        if (!proot.canExecute()) throw new IOException("PRoot executable is missing");
        if (!loader.canExecute()) throw new IOException("PRoot loader is missing");
        if (!new File(fs, "usr/bin/bash").isFile()) throw new IOException("Debian image is not installed");
        List<String> command = new ArrayList<>(Arrays.asList(
            proot.getPath(), "--kill-on-exit", "--link2symlink", "-0",
            "-r", fs.getCanonicalPath(), "-b", "/dev", "-b", "/proc", "-b", "/sys"));
        bind(command, new File("/system"), "/system");
        bind(command, new File("/vendor"), "/vendor");
        bind(command, new File("/apex"), "/apex");
        bind(command, new File(activity.getApplicationInfo().dataDir), "/app-data");
        bind(command, godData(), "/god-data");
        bind(command, moduleData(), "/shell-data");
        bind(command, activity.getExternalFilesDir(null), "/external-app");
        if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager())
          bind(command, Environment.getExternalStorageDirectory(), "/shared-storage");
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
        Process started = builder.start();
        if (closed) { started.destroy(); return; }
        process = started;
        stdin = started.getOutputStream();
        ui(() -> status.setText("connected"));
        io.execute(() -> read(started));
        io.execute(() -> waitFor(started));
      } catch (Exception error) {
        launchRequested = false;
        ui(() -> status.setText("start failed"));
        append("\n[In-app shell failed: " + error.getMessage() + "]\n");
      }
    });
  }

  private void bind(List<String> command, File file, String guest) throws IOException {
    if (file == null || !file.exists()) return;
    command.add("-b");
    command.add(file.getCanonicalPath() + ":" + guest);
  }

  private void read(Process source) {
    try (InputStream in = source.getInputStream()) {
      byte[] data = new byte[8192];
      int count;
      while ((count = in.read(data)) >= 0)
        append(new String(data, 0, count, StandardCharsets.UTF_8));
    } catch (Exception error) {
      if (!closed && source.isAlive()) append("\n[output error: " + error + "]\n");
    }
  }

  private void waitFor(Process source) {
    int code = -1;
    try {
      code = source.waitFor();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
    synchronized (this) {
      if (process == source) {
        process = null;
        stdin = null;
      }
    }
    int exit = code;
    ui(() -> status.setText(exit == 0 ? "shell exited" : "shell exited · " + exit));
    append("\n[Shell exited " + exit + "]\n");
  }

  private void submit() {
    if (input == null) return;
    String command = input.getText().toString();
    input.setText("");
    send(command + "\n");
  }

  private void send(String text) {
    OutputStream out = stdin;
    if (out == null) {
      status.setText("shell not running");
      return;
    }
    inputQueue.execute(() -> {
      try {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
      } catch (Exception error) {
        ui(() -> status.setText("send failed"));
      }
    });
  }

  private void append(String chunk) {
    if (closed || chunk == null) return;
    String cleaned = clean(chunk);
    if (cleaned.isEmpty()) return;
    final String display;
    boolean trimmed = false;
    synchronized (transcript) {
      transcript.append(cleaned);
      if (transcript.length() > UiConfig.TERMINAL_SCROLLBACK_CHARS) {
        transcript.delete(0, transcript.length() - UiConfig.TERMINAL_SCROLLBACK_CHARS);
        trimmed = true;
      }
      display = transcript.toString();
    }
    boolean replace = trimmed;
    boolean follow = stickToBottom;
    ui(() -> {
      if (output == null) return;
      if (replace) output.setText(display); else output.append(cleaned);
      if (follow && scroll != null) {
        stickToBottom = true;
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
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

  private static String tail(String text, int count) {
    return text.length() <= count ? text : text.substring(text.length() - count);
  }

  private void ui(Runnable action) {
    Activity host = activity;
    if (host != null) host.runOnUiThread(() -> { if (!closed && !host.isDestroyed()) action.run(); });
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  void release() {
    if (closed) return;
    closed = true;
    handler.removeCallbacks(poll);
    OutputStream out = stdin;
    if (out != null) try {
      out.write("exit\n".getBytes(StandardCharsets.UTF_8));
      out.flush();
    } catch (Exception ignored) {
    }
    Process running = process;
    if (running != null) running.destroy();
    io.shutdownNow();
    inputQueue.shutdownNow();
    activity = null;
  }
}
