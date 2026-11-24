package in.otrail.extensions.btkeyboard;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import in.otrail.UiConfig;
import in.otrail.modules.ModuleV1;
import java.util.List;
import java.util.Locale;

/**
 * In-app Bluetooth HID remote. It replaces the old companion APK and runs the
 * scan, pairing, connection and keyboard/mouse reports directly in the module.
 */
public final class BtKeyboardModule implements ModuleV1 {
  private static final int REQUEST_ACCESS = 401;
  private static final int REQUEST_ENABLE = 402;

  private Activity activity;
  private Host host;
  private BluetoothHidController controller;
  private LinearLayout scanRoot;
  private TextView keyboardConnection, keyboardStatus;
  private ScrollView keyboardScreen;
  private boolean keyboardVisible;
  private boolean keyboardDismissed;
  private boolean wasConnected;
  private TextView status;
  private TextView connection;
  private TextView hidState;
  private LinearLayout deviceList;
  private Button scan;
  private Button grant;
  private Button enable;
  private Button register;
  private Button ctrl;
  private Button alt;
  private Button shift;
  private EditText textInput;
  private boolean pendingScan;
  private boolean ctrlOn;
  private boolean altOn;
  private boolean shiftOn;
  private boolean capsOn;

  @Override
  public String id() {
    return "mod4";
  }

  @Override
  public View create(Activity activity, Host host) {
    this.activity = activity;
    this.host = host;
    activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    controller = new BluetoothHidController(activity, new BluetoothHidController.Listener() {
      @Override
      public void onStatus(String message, boolean error) {
        showStatus(message, error);
      }

      @Override
      public void onDevicesChanged() {
        refreshState();
      }

      @Override
      public void onConnectionChanged() {
        refreshState();
      }
    });

    android.widget.FrameLayout container = new android.widget.FrameLayout(activity);
    container.setBackgroundColor(UiConfig.MODULE_BACKGROUND);
    scanRoot = new LinearLayout(activity);
    scanRoot.setOrientation(LinearLayout.VERTICAL);
    scanRoot.setPadding(dp(10), dp(8), dp(10), dp(10));
    container.addView(scanRoot, new android.widget.FrameLayout.LayoutParams(-1, -1));

    scanRoot.addView(header());
    status = label("Starting Bluetooth remote…", 11, UiConfig.MODULE_MUTED);
    status.setMaxLines(4);
    status.setEllipsize(TextUtils.TruncateAt.END);
    status.setTextIsSelectable(true);
    scanRoot.addView(status);

    scanRoot.addView(accessRow());
    scanRoot.addView(deviceSection());
    scanRoot.addView(remoteSection());
    scanRoot.addView(mediaSection());

    keyboardScreen = new ScrollView(activity);
    keyboardScreen.setFillViewport(true);
    keyboardScreen.setVisibility(View.GONE);
    keyboardScreen.setBackgroundColor(UiConfig.MODULE_BACKGROUND);
    LinearLayout keyboardRoot = new LinearLayout(activity);
    keyboardRoot.setOrientation(LinearLayout.VERTICAL);
    keyboardRoot.setPadding(dp(10), dp(8), dp(10), dp(10));
    keyboardScreen.addView(keyboardRoot, new ScrollView.LayoutParams(-1, -2));
    addCyberKeyboard(keyboardRoot);

    container.addView(keyboardScreen, new android.widget.FrameLayout.LayoutParams(-1, -1));

    if (!permissionsGranted()) {
      showStatus("Bluetooth access is not granted. Tap GRANT ACCESS to scan and connect.", true);
    } else {
      controller.start();
    }
    refreshState();
    return container;
  }

  private View header() {
    LinearLayout row = new LinearLayout(activity);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, 0, 0, dp(4));
    TextView title = label("BLUETOOTH HID REMOTE", 18, UiConfig.MODULE_TEXT);
    title.setTypeface(android.graphics.Typeface.create(UiConfig.STATUS_FONT, android.graphics.Typeface.BOLD));
    row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    hidState = label("HID OFF", 10, UiConfig.MODULE_MUTED);
    hidState.setGravity(Gravity.CENTER);
    row.addView(hidState, new LinearLayout.LayoutParams(dp(82), dp(28)));
    return row;
  }

  private View accessRow() {
    LinearLayout row = new LinearLayout(activity);
    row.setGravity(Gravity.CENTER_VERTICAL);
    grant = chip("GRANT ACCESS", this::requestAccess);
    enable = chip("ENABLE BT", this::requestEnable);
    register = chip("REGISTER HID", this::registerHid);
    Button openKeyboard = chip("OPEN KEYBOARD", this::showKeyboardScreen);
    row.addView(grant, new LinearLayout.LayoutParams(0, dp(30), 1));
    row.addView(enable, new LinearLayout.LayoutParams(0, dp(30), 1));
    row.addView(register, new LinearLayout.LayoutParams(0, dp(30), 1));
    row.addView(openKeyboard, new LinearLayout.LayoutParams(0, dp(30), 1));
    row.setPadding(0, dp(3), 0, dp(3));
    return row;
  }

  private View deviceSection() {
    LinearLayout panel = new LinearLayout(activity);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(7), dp(6), dp(7), dp(7));
    panel.setBackground(panelBackground(UiConfig.MODULE_ACCENT_ALT));

    LinearLayout bar = new LinearLayout(activity);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = label("NEARBY DEVICES", 10, UiConfig.MODULE_ACCENT);
    title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    scan = chip("SCAN", this::scan);
    bar.addView(scan, new LinearLayout.LayoutParams(dp(92), dp(30)));
    panel.addView(bar);

    connection = label("No host connected", 10, UiConfig.MODULE_MUTED);
    connection.setPadding(dp(4), dp(2), dp(4), dp(3));
    panel.addView(connection);

    ScrollView listScroll = new ScrollView(activity);
    listScroll.setFillViewport(false);
    listScroll.setVerticalScrollBarEnabled(true);
    deviceList = new LinearLayout(activity);
    deviceList.setOrientation(LinearLayout.VERTICAL);
    listScroll.addView(deviceList, new ScrollView.LayoutParams(-1, -2));
    panel.addView(listScroll, new LinearLayout.LayoutParams(-1, dp(176)));

    return panel;
  }

  private View remoteSection() {
    LinearLayout panel = new LinearLayout(activity);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(7), dp(6), dp(7), dp(7));
    panel.setBackground(panelBackground(UiConfig.MODULE_ACCENT));
    LinearLayout bar = new LinearLayout(activity);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = label("KEYBOARD / TRACKPAD", 10, UiConfig.MODULE_ACCENT);
    title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    panel.addView(bar);

    LinearLayout modifiers = new LinearLayout(activity);
    ctrl = chip("CTRL", () -> toggleModifier(0));
    alt = chip("ALT", () -> toggleModifier(1));
    shift = chip("SHIFT", () -> toggleModifier(2));
    modifiers.addView(ctrl, new LinearLayout.LayoutParams(0, dp(28), 1));
    modifiers.addView(alt, new LinearLayout.LayoutParams(0, dp(28), 1));
    modifiers.addView(shift, new LinearLayout.LayoutParams(0, dp(28), 1));
    panel.addView(modifiers);
    refreshModifiers();

    LinearLayout textRow = new LinearLayout(activity);
    textRow.setGravity(Gravity.CENTER_VERTICAL);
    textInput = new EditText(activity);
    textInput.setHint("Type text for the connected host");
    textInput.setHintTextColor(UiConfig.MODULE_MUTED);
    textInput.setTextColor(UiConfig.MODULE_TEXT);
    textInput.setTextSize(11);
    textInput.setSingleLine(true);
    textInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    textInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
    textRow.addView(textInput, new LinearLayout.LayoutParams(0, dp(30), 1));
    Button send = chip("SEND", this::sendText);
    textRow.addView(send, new LinearLayout.LayoutParams(dp(64), dp(30)));
    panel.addView(textRow, new LinearLayout.LayoutParams(-1, -2));
    textInput.setOnEditorActionListener((view, action, event) -> {
      if (action == EditorInfo.IME_ACTION_SEND) {
        sendText();
        return true;
      }
      return false;
    });

    addKeyRow(panel,
        new String[] {"ESC", "TAB", "BKSP", "ENTER", "SPACE", "DEL"},
        new int[] {41, 43, 42, 40, 44, 76});
    addKeyRow(panel,
        new String[] {"←", "↑", "↓", "→", "HOME", "END"},
        new int[] {80, 82, 81, 79, 74, 77});
    addKeyRow(panel,
        new String[] {"PGUP", "PGDN", "INS", "F1", "F2", "F3"},
        new int[] {75, 78, 73, 58, 59, 60});

    TrackpadView trackpad = new TrackpadView();
    trackpad.setMinimumHeight(dp(145));
    panel.addView(trackpad, new LinearLayout.LayoutParams(-1, dp(145)));

    LinearLayout mouse = new LinearLayout(activity);
    mouse.setGravity(Gravity.CENTER_VERTICAL);
    Button left = chip("LEFT", () -> controller.sendMouse(HidReportDescriptor.MOUSE_LEFT, 0, 0, 0));
    Button release = chip("RELEASE", () -> controller.sendMouse(0, 0, 0, 0));
    Button right = chip("RIGHT", () -> controller.sendMouse(HidReportDescriptor.MOUSE_RIGHT, 0, 0, 0));
    Button up = chip("SCROLL ↑", () -> controller.sendMouse(0, 0, 0, 20));
    Button down = chip("SCROLL ↓", () -> controller.sendMouse(0, 0, 0, -20));
    mouse.addView(left, new LinearLayout.LayoutParams(0, dp(29), 1));
    mouse.addView(release, new LinearLayout.LayoutParams(0, dp(29), 1));
    mouse.addView(right, new LinearLayout.LayoutParams(0, dp(29), 1));
    mouse.addView(up, new LinearLayout.LayoutParams(0, dp(29), 1));
    mouse.addView(down, new LinearLayout.LayoutParams(0, dp(29), 1));
    panel.addView(mouse);
    return panel;
  }

  private void addCyberKeyboard(LinearLayout parent) {

    parent.setPadding(dp(6), dp(4), dp(6), dp(4));

    LinearLayout topBar = new LinearLayout(activity);
    topBar.setGravity(Gravity.CENTER_VERTICAL);
    topBar.setPadding(0, 0, 0, dp(6));
    TextView title = label("CYBER COMBO REMOTE", 16, UiConfig.MODULE_TEXT);
    title.setTypeface(android.graphics.Typeface.create(UiConfig.STATUS_FONT, android.graphics.Typeface.BOLD));
    topBar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    keyboardConnection = label("", 10, UiConfig.MODULE_TEXT);
    topBar.addView(keyboardConnection, new LinearLayout.LayoutParams(-2, -2));
    Button devices = cyberKey("Devices", 0, false);
    devices.setTextColor(UiConfig.MODULE_ACCENT);
    devices.setOnClickListener(v -> showScanScreen());
    topBar.addView(devices, new LinearLayout.LayoutParams(dp(86), dp(30)));
    parent.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

    LinearLayout trackpadRow = new LinearLayout(activity);
    trackpadRow.setOrientation(LinearLayout.HORIZONTAL);
    trackpadRow.setGravity(Gravity.CENTER_VERTICAL);
    trackpadRow.setPadding(0, dp(4), 0, dp(4));

    TrackpadView trackpad = new TrackpadView();
    trackpad.setBackground(panelBackground(0xff00d4ff));
    trackpadRow.setMinimumHeight(dp(64));

    LinearLayout mouseRow = new LinearLayout(activity);
    mouseRow.setGravity(Gravity.CENTER_HORIZONTAL);
    mouseRow.setPadding(0, 0, dp(4), 0);
    Button left = cyberKey("L", 0, false);
    left.setOnClickListener(v -> controller.sendMouse(HidReportDescriptor.MOUSE_LEFT, 0, 0, 0));
    styleCyberKey(left, false);
    android.graphics.drawable.GradientDrawable leftFrame = (android.graphics.drawable.GradientDrawable) left.getBackground();
    leftFrame.setStroke(dp(1), 0xff9b59ff);
    Button right = cyberKey("R", 0, false);
    right.setOnClickListener(v -> controller.sendMouse(HidReportDescriptor.MOUSE_RIGHT, 0, 0, 0));
    styleCyberKey(right, false);
    android.graphics.drawable.GradientDrawable rightFrame = (android.graphics.drawable.GradientDrawable) right.getBackground();
    rightFrame.setStroke(dp(1), 0xff9b59ff);
    mouseRow.addView(left, new LinearLayout.LayoutParams(0, -1, 1));
    mouseRow.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
    trackpadRow.addView(mouseRow, new LinearLayout.LayoutParams(0, -1, 2));
    trackpadRow.addView(trackpad, new LinearLayout.LayoutParams(0, -1, 3));
    parent.addView(trackpadRow, new LinearLayout.LayoutParams(-1, dp(64), 1));

    addKeyboardRows(parent);

    keyboardStatus = label("", 10, 0xffffff00);
    parent.addView(keyboardStatus);
    LinearLayout mediaRow = new LinearLayout(activity);
    mediaRow.setGravity(Gravity.CENTER_HORIZONTAL);
    mediaRow.setPadding(0, dp(4), 0, dp(2));
    mediaRow.addView(cyberMedia("⏮", HidReportDescriptor.MEDIA_PREVIOUS), new LinearLayout.LayoutParams(0, dp(30), 1));
    mediaRow.addView(cyberMedia("⏯", HidReportDescriptor.MEDIA_PLAY_PAUSE), new LinearLayout.LayoutParams(0, dp(30), 1));
    mediaRow.addView(cyberMedia("⏭", HidReportDescriptor.MEDIA_NEXT), new LinearLayout.LayoutParams(0, dp(30), 1));
    mediaRow.addView(cyberMedia("🔉", HidReportDescriptor.MEDIA_VOLUME_DOWN), new LinearLayout.LayoutParams(0, dp(30), 1));
    mediaRow.addView(cyberMedia("🔊", HidReportDescriptor.MEDIA_VOLUME_UP), new LinearLayout.LayoutParams(0, dp(30), 1));
    mediaRow.addView(cyberMedia("🔇", HidReportDescriptor.MEDIA_MUTE), new LinearLayout.LayoutParams(0, dp(30), 1));
    parent.addView(mediaRow, new LinearLayout.LayoutParams(-1, -2));
  }

  private void addKeyboardRows(LinearLayout parent) {
    String[][] rows = new String[][] {
        {"Esc", "`", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "="},
        {"Tab", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "[", "]"},
        {"Caps", "A", "S", "D", "F", "G", "H", "J", "K", "L", ";", "'"},
        {"Shift", "Z", "X", "C", "V", "B", "N", "M", ",", ".", "/", "Enter"},
        {"Ctrl", "Win", "Alt", "Space", "←", "↑", "↓", "→", "Alt", "Ctrl"}
    };
    int[][] usages = new int[][] {
        {41,53,30,31,32,33,34,35,36,37,38,39,45,46},
        {43,20,26,8,21,23,28,24,12,18,19,47,48},
        {57,4,22,7,9,10,11,13,14,15,51,52},
        {0,29,27,6,25,5,17,16,54,55,56,40},
        {0,0,0,44,79,82,81,80,0,0}
    };
    for (int row = 0; row < rows.length; row++) {
      LinearLayout line = new LinearLayout(activity);
      line.setGravity(Gravity.CENTER_VERTICAL);
      line.setPadding(0, dp(2), 0, dp(2));
      for (int col = 0; col < rows[row].length; col++) {
        String label = rows[row][col];
        int usage = usages[row][col];
        float weight = label.equals("Space") ? 3.2f : 1f;
        if (label.equals("Tab") || label.equals("Caps") || label.equals("Shift")
            || label.equals("Enter") || label.equals("Ctrl") || label.equals("Alt")) {
          weight = 1.35f;
        }
        Button key = cyberKey(label, usage, true);
        if (label.equals("Caps")) {
          key.setOnClickListener(v -> {
            capsOn = !capsOn;
            styleCyberKey(key, capsOn);
            controller.sendKeystroke(0, 57);
          });
        } else if (label.equals("Shift")) {
          key.setOnClickListener(v -> {
            shiftOn = !shiftOn;
            refreshModifiers();
            styleCyberKey(key, shiftOn);
          });
        } else if (label.equals("Ctrl")) {
          key.setOnClickListener(v -> {
            ctrlOn = !ctrlOn;
            refreshModifiers();
            styleCyberKey(key, ctrlOn);
          });
        } else if (label.equals("Alt")) {
          key.setOnClickListener(v -> {
            altOn = !altOn;
            refreshModifiers();
            styleCyberKey(key, altOn);
          });
        } else if (label.equals("Win")) {
          key.setContentDescription("Windows Start menu");
          key.setOnClickListener(v -> controller.sendKeystroke(HidReportDescriptor.MOD_GUI, 0));
        } else if (label.equals("Space")) {
          key.setOnClickListener(v -> controller.sendKeystroke(modifiersForUsage(usage), 44));
        } else if (usage == 0) {
          // Modifier-only key already handled above.
        } else {
          final int finalUsage = usage;
          key.setOnClickListener(v -> {
            int mods = modifiersForUsage(finalUsage);
            controller.sendKeystroke(mods, finalUsage);
          });
        }
        styleCyberKey(key, false);
        LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(30), weight);
        cell.setMargins(dp(1), 0, dp(1), 0);
        line.addView(key, cell);
      }
      parent.addView(line, new LinearLayout.LayoutParams(-1, -2));
    }
  }

  private int modifiersForUsage(int usage) {
    int mods = modifiers();
    if (capsOn && usage >= 4 && usage <= 29) mods ^= HidReportDescriptor.MOD_SHIFT;
    return mods;
  }

  private Button cyberKey(String text, int usage, boolean keyboard) {
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
    button.setPadding(dp(2), 0, dp(2), 0);
    android.graphics.drawable.GradientDrawable frame = new android.graphics.drawable.GradientDrawable();
    frame.setColor(0xff000000);
    frame.setCornerRadius(dp(6));
    frame.setStroke(dp(1), 0xff2a3555);
    button.setBackground(frame);
    button.setTextColor(0xfff8fafc);
    return button;
  }

  private Button cyberMedia(String text, int usage) {
    Button button = cyberKey(text, usage, false);
    button.setOnClickListener(v -> controller.sendConsumer(usage));
    android.graphics.drawable.GradientDrawable frame =
        (android.graphics.drawable.GradientDrawable) button.getBackground();
    frame.setStroke(dp(1), 0xff00d4ff);
    return button;
  }

  private Button cyberSideButton(String text, Runnable action) {
    Button button = new Button(activity);
    button.setText(text);
    button.setTextSize(12);
    button.setTextColor(0xff333333);
    button.setAllCaps(false);
    button.setMinHeight(0);
    button.setMinimumHeight(0);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(0, 0, 0, 0);
    android.graphics.drawable.GradientDrawable frame = new android.graphics.drawable.GradientDrawable();
    frame.setColor(0xfff2e8cf);
    frame.setCornerRadius(dp(21));
    button.setBackground(frame);
    button.setOnClickListener(v -> {
      try { action.run(); } catch (Throwable error) { showStatus("Action failed: " + error.getMessage(), true); }
    });
    return button;
  }

  private void styleCyberKey(Button button, boolean active) {
    if (button == null) return;
    android.graphics.drawable.GradientDrawable frame = new android.graphics.drawable.GradientDrawable();
    frame.setColor(0xff000000);
    frame.setCornerRadius(dp(6));
    frame.setStroke(dp(1), active ? 0xff00d4ff : 0xff2a3555);
    button.setTextColor(active ? 0xff00d4ff : 0xfff8fafc);
    button.setBackground(frame);
  }

  private View mediaSection() {
    LinearLayout row = new LinearLayout(activity);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(5), 0, 0);
    row.setMinimumHeight(dp(32));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(30), 1);
    row.addView(mediaButton("PREV", HidReportDescriptor.MEDIA_PREVIOUS), params);
    row.addView(mediaButton("PLAY", HidReportDescriptor.MEDIA_PLAY_PAUSE),
        new LinearLayout.LayoutParams(0, dp(30), 1));
    row.addView(mediaButton("NEXT", HidReportDescriptor.MEDIA_NEXT),
        new LinearLayout.LayoutParams(0, dp(30), 1));
    row.addView(mediaButton("VOL-", HidReportDescriptor.MEDIA_VOLUME_DOWN),
        new LinearLayout.LayoutParams(0, dp(30), 1));
    row.addView(mediaButton("MUTE", HidReportDescriptor.MEDIA_MUTE),
        new LinearLayout.LayoutParams(0, dp(30), 1));
    row.addView(mediaButton("VOL+", HidReportDescriptor.MEDIA_VOLUME_UP),
        new LinearLayout.LayoutParams(0, dp(30), 1));
    return row;
  }

  private Button mediaButton(String text, int usage) {
    return chip(text, () -> controller.sendConsumer(usage));
  }

  private void addKeyRow(LinearLayout parent, String[] labels, int[] usages) {
    LinearLayout row = new LinearLayout(activity);
    row.setGravity(Gravity.CENTER_VERTICAL);
    for (int index = 0; index < labels.length; index++) {
      final int usage = usages[index];
      row.addView(chip(labels[index], () -> sendKey(usage)), weight());
    }
    parent.addView(row, new LinearLayout.LayoutParams(-1, dp(29)));
  }

  private void sendKey(int usage) {
    controller.sendKeystroke(modifiers(), usage);
  }

  private int modifiers() {
    return (ctrlOn ? HidReportDescriptor.MOD_CTRL : 0)
        | (altOn ? HidReportDescriptor.MOD_ALT : 0)
        | (shiftOn ? HidReportDescriptor.MOD_SHIFT : 0);
  }

  private void sendText() {
    String value = textInput.getText().toString();
    if (value.isEmpty()) {
      showStatus("Type some text first.", true);
      return;
    }
    controller.sendText(value);
    textInput.setText("");
    showStatus("Sending " + value.length() + " character(s)…", false);
  }

  private void toggleModifier(int which) {
    if (which == 0) ctrlOn = !ctrlOn;
    if (which == 1) altOn = !altOn;
    if (which == 2) shiftOn = !shiftOn;
    refreshModifiers();
  }

  private void refreshModifiers() {
    styleModifier(ctrl, ctrlOn);
    styleModifier(alt, altOn);
    styleModifier(shift, shiftOn);
  }

  private void styleModifier(Button button, boolean active) {
    if (button == null) return;
    GradientDrawable frame = new GradientDrawable();
    frame.setColor(active ? 0xff00d4ff : 0xff1a2238);
    frame.setCornerRadius(dp(6));
    frame.setStroke(dp(1), active ? 0xff00d4ff : 0xff2a3555);
    button.setTextColor(active ? 0xff000000 : 0xfff8fafc);
    button.setBackground(frame);
  }

  private void scan() {
    if (controller.isScanning()) {
      controller.stopDiscovery("Scan stopped.");
      refreshState();
      return;
    }
    if (!ensureAccess()) {
      pendingScan = true;
      return;
    }
    controller.startDiscovery();
    refreshState();
  }

  private void requestAccess() {
    if (permissionsGranted()) {
      controller.start();
      refreshState();
      return;
    }
    if (Build.VERSION.SDK_INT >= 31) {
      activity.requestPermissions(new String[] {
          Manifest.permission.BLUETOOTH_CONNECT,
          Manifest.permission.BLUETOOTH_SCAN,
          Manifest.permission.BLUETOOTH_ADVERTISE
      }, REQUEST_ACCESS);
    }
    showStatus("Allow Nearby devices access for oTraiL.", false);
  }

  private boolean ensureAccess() {
    if (!permissionsGranted()) {
      requestAccess();
      return false;
    }
    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    if (adapter == null) {
      showStatus("This phone has no Bluetooth adapter.", true);
      return false;
    }
    if (!adapter.isEnabled()) {
      requestEnable();
      return false;
    }
    return true;
  }

  private void requestEnable() {
    try {
      activity.startActivityForResult(
          new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE);
      showStatus("Confirm turning on Bluetooth, then return to scan.", false);
    } catch (Exception error) {
      showStatus("Could not open Bluetooth settings: " + error.getMessage(), true);
    }
  }

  private void registerHid() {
    if (!ensureAccess()) return;
    controller.init();
    controller.registerHid();
  }

  private boolean permissionsGranted() {
    if (Build.VERSION.SDK_INT < 31) return true;
    return activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED
        && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
            == PackageManager.PERMISSION_GRANTED;
  }

  @Override
  public void permissionResult(int request, String[] permissions, int[] grants) {
    if (request != REQUEST_ACCESS) return;
    if (permissionsGranted()) {
      showStatus("Bluetooth access granted. Starting HID remote…", false);
      controller.start();
      refreshState();
      if (pendingScan) {
        pendingScan = false;
        controller.startDiscovery();
      }
    } else {
      pendingScan = false;
      showStatus("Bluetooth access denied. Tap GRANT ACCESS or open app settings.", true);
      offerSettings();
    }
  }

  private void offerSettings() {
    try {
      activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
          android.net.Uri.parse("package:" + activity.getPackageName())));
    } catch (Exception ignored) {
      // The device may not expose the app-details page.
    }
  }

  @Override
  public void activityResult(int request, int result, Intent data) {
    if (request != REQUEST_ENABLE) return;
    if (result == Activity.RESULT_OK) {
      showStatus("Bluetooth enabled. Refreshing devices…", false);
      controller.refresh();
      controller.init();
      if (pendingScan) {
        pendingScan = false;
        controller.startDiscovery();
      }
    } else if (pendingScan) {
      pendingScan = false;
      showStatus("Bluetooth is still off; scanning needs Bluetooth enabled.", true);
    }
  }

  @Override
  public void resume() {
    if (activity == null || controller == null) return;
    if (permissionsGranted()) {
      controller.start();
      controller.refresh();
    }
    refreshState();
  }

  @Override
  public void pause() {
    if (controller != null) controller.stopDiscovery(null);
  }

  @Override
  public boolean backPressed() {
    if (keyboardVisible) {
      showScanScreen();
      return true;
    }
    return false;
  }

  @Override
  public void close() {
    if (controller != null) controller.close();
    controller = null;
    keyboardScreen = null;
    scanRoot = null;
    activity = null;
    host = null;
  }

  private void refreshDevices() {
    if (deviceList == null || activity == null) return;
    activity.runOnUiThread(() -> {
      if (deviceList == null || controller == null) return;
      deviceList.removeAllViews();
      List<BluetoothHidController.DeviceEntry> devices = controller.devices();
      if (devices.isEmpty()) {
        TextView empty = label(controller.isScanning()
            ? "Scanning… no devices found yet."
            : "No nearby devices yet. Tap SCAN; make the host discoverable first.", 10,
            UiConfig.MODULE_MUTED);
        empty.setPadding(dp(5), dp(8), dp(5), dp(8));
        deviceList.addView(empty);
        return;
      }
      for (BluetoothHidController.DeviceEntry entry : devices) {
        deviceList.addView(deviceRow(entry), new LinearLayout.LayoutParams(-1, dp(48)));
      }
    });
  }

  private View deviceRow(BluetoothHidController.DeviceEntry entry) {
    LinearLayout row = new LinearLayout(activity);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(5), dp(3), dp(4), dp(3));
    row.setBackground(panelBackground(
        entry.isConnected() ? UiConfig.MODULE_ACCENT : UiConfig.MODULE_DIVIDER));

    LinearLayout text = new LinearLayout(activity);
    text.setOrientation(LinearLayout.VERTICAL);
    TextView name = label(entry.label(), 11, UiConfig.MODULE_TEXT);
    name.setSingleLine(true);
    name.setEllipsize(TextUtils.TruncateAt.END);
    String signal = entry.rssi > -127 ? " · " + entry.rssi + " dBm" : "";
    TextView meta = label(entry.address() + " · " + entry.stateLabel() + signal, 9,
        entry.bonded ? UiConfig.MODULE_ACCENT_ALT : UiConfig.MODULE_MUTED);
    meta.setSingleLine(true);
    text.addView(name);
    text.addView(meta);
    row.addView(text, new LinearLayout.LayoutParams(0, -1, 1));

    boolean connected = entry.isConnected();
    String action = connected ? "CONNECTED" : entry.bonded ? "CONNECT" : "PAIR";
    Button button = chip(action, () -> chooseDevice(entry));
    button.setTextSize(9);
    button.setEnabled(!connected);
    row.addView(button, new LinearLayout.LayoutParams(dp(86), dp(30)));
    row.setOnClickListener(view -> chooseDevice(entry));
    return row;
  }

  private void chooseDevice(BluetoothHidController.DeviceEntry entry) {
    if (!ensureAccess()) return;
    try {
      if (entry.device.getBondState() == BluetoothDevice.BOND_BONDED) {
        controller.connect(entry.device);
      } else {
        controller.pair(entry.device);
      }
    } catch (SecurityException error) {
      showStatus("Bluetooth permission denied: " + error.getMessage(), true);
    }
    refreshDevices();
  }

  private void showKeyboardScreen() {
    if (keyboardScreen == null || scanRoot == null) return;
    activity.runOnUiThread(() -> {
      keyboardDismissed = false;
      scanRoot.setVisibility(View.GONE);
      keyboardScreen.setVisibility(View.VISIBLE);
      keyboardVisible = true;
    });
  }

  private void showScanScreen() {
    if (keyboardScreen == null || scanRoot == null) return;
    activity.runOnUiThread(() -> {
      keyboardDismissed = true;
      keyboardScreen.setVisibility(View.GONE);
      scanRoot.setVisibility(View.VISIBLE);
      keyboardVisible = false;
    });
  }

  private void refreshState() {
    activity.runOnUiThread(() -> {
      if (controller == null) return;
      boolean connected = controller.isConnected();
      keyboardConnection.setText(connected ? "● CONNECTED" : "○ DISCONNECTED");
      keyboardConnection.setTextColor(connected ? 0xff55ff55 : 0xffff3838);
      if (connection != null) {
        connection.setText(connected
            ? "Live host · " + controller.connectedName()
            : "No host connected · pair a device, then connect");
        connection.setTextColor(connected ? UiConfig.MODULE_ACCENT : UiConfig.MODULE_MUTED);
      }
      if (hidState != null) {
        hidState.setText(controller.isRegistered() ? "HID READY" : "HID OFF");
        hidState.setTextColor(controller.isRegistered()
            ? UiConfig.MODULE_DOT_ONLINE : UiConfig.MODULE_MUTED);
      }
      if (scan != null) scan.setText(controller.isScanning() ? "STOP SCAN" : "SCAN");
      if (grant != null) grant.setVisibility(permissionsGranted() ? View.GONE : View.VISIBLE);
      if (connected && !wasConnected && !keyboardDismissed) showKeyboardScreen();
      if (!connected) {
        keyboardDismissed = false;
        showScanScreen();
      }
      wasConnected = connected;
      refreshDevices();
    });
  }

  private void showStatus(String message, boolean error) {
    if (keyboardStatus != null) keyboardStatus.post(() -> keyboardStatus.setText(message));
    if (activity == null) return;
    activity.runOnUiThread(() -> {
      if (status == null) return;
      status.setText(message);
      status.setTextColor(error ? UiConfig.TERMINAL_ERROR : UiConfig.MODULE_MUTED);
    });
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
    button.setEllipsize(TextUtils.TruncateAt.END);
    button.setMinHeight(0);
    button.setMinimumHeight(0);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(dp(2), 0, dp(2), 0);
    button.setBackgroundTintList(null);
    GradientDrawable frame = new GradientDrawable();
    frame.setColor(UiConfig.MODULE_SURFACE_ALT);
    frame.setCornerRadius(dp(6));
    frame.setStroke(dp(1), UiConfig.MODULE_DIVIDER);
    button.setBackground(new android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(0x44ffffff), frame, null));
    button.setOnClickListener(view -> {
      try {
        action.run();
      } catch (Throwable error) {
        showStatus("Action failed: " + error.getClass().getSimpleName()
            + ": " + error.getMessage(), true);
      }
    });
    return button;
  }

  private GradientDrawable panelBackground(int accent) {
    GradientDrawable background = new GradientDrawable();
    background.setColor(UiConfig.MODULE_SURFACE);
    background.setCornerRadius(dp(10));
    background.setStroke(dp(1), accent);
    return background;
  }

  private LinearLayout.LayoutParams weight() {
    return new LinearLayout.LayoutParams(0, -1, 1);
  }

  private int dp(int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }

  private final class TrackpadView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float lastX;
    private float lastY;
    private float remainderX;
    private float remainderY;
    private long downAt;
    private boolean moved;
    private boolean twoFingers;

    TrackpadView() {
      super(activity);
      setBackground(panelBackground(UiConfig.MODULE_DIVIDER));
      setContentDescription("Trackpad. Drag to move the pointer, tap for left click.");
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      paint.setColor(UiConfig.MODULE_MUTED);
      paint.setTextSize(dp(10));
      paint.setTextAlign(Paint.Align.CENTER);
      canvas.drawText("TRACKPAD · drag to move · tap to click", getWidth() / 2f,
          getHeight() / 2f, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          getParent().requestDisallowInterceptTouchEvent(true);
          lastX = event.getX();
          lastY = event.getY();
          remainderX = 0;
          remainderY = 0;
          downAt = System.currentTimeMillis();
          moved = false;
          twoFingers = false;
          return true;
        case MotionEvent.ACTION_POINTER_DOWN:
          twoFingers = true;
          lastY = averageY(event);
          return true;
        case MotionEvent.ACTION_MOVE:
          if (event.getPointerCount() >= 2) {
            float y = averageY(event);
            int wheel = Math.round((lastY - y) / 7f);
            lastY = y;
            if (wheel != 0) {
              moved = true;
              controller.sendMouse(0, 0, 0, wheel);
            }
            return true;
          }
          float dx = event.getX() - lastX;
          float dy = event.getY() - lastY;
          lastX = event.getX();
          lastY = event.getY();
          remainderX += dx * 0.633333f;
          remainderY += dy * 0.633333f;
          int moveX = (int) remainderX;
          int moveY = (int) remainderY;
          remainderX -= moveX;
          remainderY -= moveY;
          if (moveX != 0 || moveY != 0) {
            moved = true;
            controller.sendMouse(0, moveX, moveY, 0);
          }
          return true;
        case MotionEvent.ACTION_UP:
          getParent().requestDisallowInterceptTouchEvent(false);
          if (!twoFingers && !moved && System.currentTimeMillis() - downAt < 350) {
            controller.sendMouse(HidReportDescriptor.MOUSE_LEFT, 0, 0, 0);
            controller.sendMouse(0, 0, 0, 0);
            performClick();
          }
          return true;
        case MotionEvent.ACTION_CANCEL:
          getParent().requestDisallowInterceptTouchEvent(false);
          return true;
        default:
          return super.onTouchEvent(event);
      }
    }

    @Override
    public boolean performClick() {
      super.performClick();
      return true;
    }

    private float averageY(MotionEvent event) {
      float total = 0;
      for (int index = 0; index < event.getPointerCount(); index++) {
        total += event.getY(index);
      }
      return total / event.getPointerCount();
    }
  }
}
