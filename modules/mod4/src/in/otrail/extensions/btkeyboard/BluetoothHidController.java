package in.otrail.extensions.btkeyboard;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the phone's Bluetooth HID device profile, discovery and report writes.
 * UI code only asks this class to scan, pair, connect or send a report.
 */
final class BluetoothHidController {
  interface Listener {
    void onStatus(String message, boolean error);

    void onDevicesChanged();

    void onConnectionChanged();
  }

  private static final int HID_PROFILE = BluetoothProfile.HID_DEVICE;
  private static final int SCAN_MILLIS = 15000;

  private final Activity activity;
  private final Listener listener;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Map<String, DeviceEntry> devices = new LinkedHashMap<>();
  private final ExecutorService callbacks = Executors.newSingleThreadExecutor();
  private final ExecutorService reports = Executors.newSingleThreadExecutor();
  private final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

  private final BroadcastReceiver receiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      String action = intent.getAction();
      if (BluetoothDevice.ACTION_FOUND.equals(action)) {
        onDeviceFound(intent);
      } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
        onDiscoveryFinished();
      } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
        onBondStateChanged(intent);
      } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
        onAdapterStateChanged(intent);
      }
    }
  };

  private final BluetoothProfile.ServiceListener profileListener =
      new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
          if (profile != HID_PROFILE) return;
          hidDevice = (BluetoothHidDevice) proxy;
          requestingProfile = false;
          status("Android HID service connected; registering remote…", false);
          registerHid();
        }

        @Override
        public void onServiceDisconnected(int profile) {
          if (profile != HID_PROFILE) return;
          hidDevice = null;
          registered = false;
          connectedHost = null;
          requestingProfile = false;
          status("Android HID service disconnected. Tap REGISTER HID to retry.", true);
          listener.onConnectionChanged();
        }
      };

  private final BluetoothHidDevice.Callback hidCallback = new BluetoothHidDevice.Callback() {
    @Override
    public void onConnectionStateChanged(BluetoothDevice device, int state) {
      String name = deviceName(device);
      if (state == BluetoothProfile.STATE_CONNECTED) {
        connectedHost = device;
        entry(device).connected = true;
        status("Connected to " + name + ". Remote reports are live.", false);
      } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
        if (connectedHost != null && connectedHost.getAddress().equals(device.getAddress())) {
          connectedHost = null;
        }
        DeviceEntry entry = devices.get(device.getAddress());
        if (entry != null) entry.connected = false;
        status("Disconnected from " + name + ". Tap the device to reconnect.", false);
      } else if (state == BluetoothProfile.STATE_CONNECTING) {
        status("Connecting to " + name + "…", false);
      } else if (state == BluetoothProfile.STATE_DISCONNECTING) {
        status("Disconnecting from " + name + "…", false);
      }
      listener.onConnectionChanged();
      listener.onDevicesChanged();
    }

    @Override
    public void onAppStatusChanged(BluetoothDevice device, boolean ok) {
      registered = ok;
      if (ok) {
        String target = device == null ? "" : " for " + deviceName(device);
        status("HID remote registered" + target + ". Pair the host, then connect it.", false);
      } else {
        status("HID remote is not registered. Tap REGISTER HID.", true);
      }
      listener.onConnectionChanged();
      listener.onDevicesChanged();
    }
  };

  private BluetoothHidDevice hidDevice;
  private BluetoothDevice connectedHost;
  private boolean started;
  private boolean registered;
  private boolean requestingProfile;
  private boolean scanning;
  private String pendingBondAddress;
  private boolean closed;

  private final Runnable scanTimeout = new Runnable() {
    @Override
    public void run() {
      if (scanning) stopDiscovery("Scan time limit reached. Tap SCAN to retry.");
    }
  };

  BluetoothHidController(Activity activity, Listener listener) {
    this.activity = activity;
    this.listener = listener;
  }

  void start() {
    if (started || closed) return;
    started = true;
    IntentFilter filter = new IntentFilter();
    filter.addAction(BluetoothDevice.ACTION_FOUND);
    filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
    filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
    filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
    try {
      if (Build.VERSION.SDK_INT >= 33) {
        activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
      } else {
        activity.registerReceiver(receiver, filter);
      }
    } catch (Exception error) {
      error("Could not listen for Bluetooth events", error);
    }
    refresh();
    init();
  }

  void refresh() {
    if (adapter == null) {
      status("This phone has no Bluetooth adapter.", true);
      return;
    }
    if (!hasConnectPermission()) {
      status("Bluetooth permission is required to list and connect devices.", true);
      return;
    }
    if (!adapter.isEnabled()) {
      status("Bluetooth is off. Turn it on to scan and connect.", true);
      return;
    }
    try {
      for (BluetoothDevice device : adapter.getBondedDevices()) {
        DeviceEntry entry = entry(device);
        entry.bonded = true;
      }
      for (Iterator<DeviceEntry> iterator = devices.values().iterator(); iterator.hasNext(); ) {
        DeviceEntry entry = iterator.next();
        if (!entry.bonded && !entry.isConnected()) iterator.remove();
      }
      listener.onDevicesChanged();
      listener.onConnectionChanged();
    } catch (SecurityException error) {
      error("Bluetooth permission denied while reading paired devices", error);
    } catch (Exception error) {
      error("Could not read paired devices", error);
    }
  }

  void init() {
    if (adapter == null) {
      status("This phone has no Bluetooth adapter.", true);
      return;
    }
    if (!hasConnectPermission()) {
      status("Grant Bluetooth access before registering the HID remote.", true);
      return;
    }
    if (!adapter.isEnabled()) {
      status("Bluetooth is off. Turn it on before registering the HID remote.", true);
      return;
    }
    if (hidDevice != null || requestingProfile) return;
    requestingProfile = true;
    status("Requesting Android HID service…", false);
    try {
      boolean requested = adapter.getProfileProxy(activity, profileListener, HID_PROFILE);
      if (!requested) {
        requestingProfile = false;
        status("Android did not provide the HID device profile.", true);
      }
    } catch (SecurityException error) {
      requestingProfile = false;
      error("Bluetooth permission denied for HID registration", error);
    } catch (Exception error) {
      requestingProfile = false;
      error("Could not start the HID profile", error);
    }
  }

  void registerHid() {
    if (registered) {
      status("HID remote is already registered.", false);
      return;
    }
    if (hidDevice == null) {
      init();
      return;
    }
    try {
      BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
          "oTraiL BT Remote",
          "Keyboard, trackpad and media remote",
          "oTraiL",
          (byte) 0xC0,
          HidReportDescriptor.combo());
      BluetoothHidDeviceAppQosSettings qos = new BluetoothHidDeviceAppQosSettings(
          1, 800, 9, 0, 11250, 11250);
      boolean accepted = hidDevice.registerApp(sdp, null, qos, callbacks, hidCallback);
      status(accepted
          ? "Registering HID remote…"
          : "Android rejected HID registration. Disconnect any other HID app and retry.", !accepted);
    } catch (SecurityException error) {
      error("Bluetooth permission denied while registering HID", error);
    } catch (Exception error) {
      error("HID registration failed", error);
    }
  }

  boolean startDiscovery() {
    if (adapter == null) {
      status("This phone has no Bluetooth adapter.", true);
      return false;
    }
    if (!hasScanPermission()) {
      status("Bluetooth scan permission is required.", true);
      return false;
    }
    if (!adapter.isEnabled()) {
      status("Bluetooth is off. Turn it on before scanning.", true);
      return false;
    }
    try {
      if (adapter.isDiscovering()) adapter.cancelDiscovery();
      for (Iterator<DeviceEntry> iterator = devices.values().iterator(); iterator.hasNext(); ) {
        DeviceEntry entry = iterator.next();
        if (!entry.bonded && !entry.isConnected()) iterator.remove();
      }
      boolean started = adapter.startDiscovery();
      if (!started) {
        status("Android rejected the scan. Check Nearby devices permission and retry.", true);
        return false;
      }
      scanning = true;
      status("Scanning for nearby Bluetooth devices…", false);
      handler.removeCallbacks(scanTimeout);
      handler.postDelayed(scanTimeout, SCAN_MILLIS);
      listener.onDevicesChanged();
      return true;
    } catch (SecurityException error) {
      error("Bluetooth scan permission denied", error);
      return false;
    } catch (Exception error) {
      error("Could not start Bluetooth scanning", error);
      return false;
    }
  }

  void stopDiscovery(String message) {
    handler.removeCallbacks(scanTimeout);
    boolean wasScanning = scanning;
    scanning = false;
    try {
      if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery();
    } catch (Exception ignored) {
      // Discovery may already be stopped by Android.
    }
    if (wasScanning || message != null) status(message == null ? "Scan stopped." : message, false);
    listener.onDevicesChanged();
  }

  boolean isScanning() {
    return scanning;
  }

  boolean pair(BluetoothDevice device) {
    if (device == null) {
      status("Select a device first.", true);
      return false;
    }
    if (!hasConnectPermission()) {
      status("Bluetooth permission is required to pair.", true);
      return false;
    }
    pendingBondAddress = device.getAddress();
    try {
      boolean started = device.createBond();
      status(started
          ? "Pairing with " + deviceName(device) + ". Confirm the code on both devices."
          : "Pairing could not start for " + deviceName(device) + ".", !started);
      if (!started) pendingBondAddress = null;
      return started;
    } catch (SecurityException error) {
      pendingBondAddress = null;
      error("Bluetooth permission denied while pairing", error);
      return false;
    } catch (Exception error) {
      pendingBondAddress = null;
      error("Pairing failed", error);
      return false;
    }
  }

  boolean connect(BluetoothDevice device) {
    if (device == null) {
      status("Select a device first.", true);
      return false;
    }
    if (!registered || hidDevice == null) {
      status("HID remote is not registered yet. Tap REGISTER HID, then connect.", true);
      return false;
    }
    try {
      if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
        status(deviceName(device) + " is not paired yet. Pair it first.", true);
        return false;
      }
      boolean started = hidDevice.connect(device);
      if (started) {
        status("Connect requested for " + deviceName(device) + ". Accept it on the host if prompted.", false);
      } else {
        status("Android refused to connect " + deviceName(device) + ". Check that the host accepts HID input.", true);
      }
      return started;
    } catch (SecurityException error) {
      error("Bluetooth permission denied while connecting", error);
      return false;
    } catch (Exception error) {
      error("Connection failed", error);
      return false;
    }
  }

  List<DeviceEntry> devices() {
    List<DeviceEntry> list = new ArrayList<>(devices.values());
    Collections.sort(list);
    return list;
  }

  boolean isRegistered() {
    return registered;
  }

  boolean isConnected() {
    return connectedHost != null;
  }

  String connectedName() {
    return connectedHost == null ? "" : deviceName(connectedHost);
  }

  void sendKeystroke(int modifier, int usage) {
    reports.execute(() -> sendKeyReport(modifier, usage));
  }

  void sendText(String text) {
    if (text == null || text.isEmpty()) return;
    String value = text;
    reports.execute(() -> {
      int skipped = 0;
      for (int index = 0; index < value.length(); index++) {
        char character = value.charAt(index);
        if (character == '\n') {
          sendKeyReport(0, 40);
        } else if (character == '\t') {
          sendKeyReport(0, 43);
        } else {
          KeyStroke stroke = KeyStroke.forCharacter(character);
          if (stroke == null) {
            skipped++;
          } else {
            sendKeyReport(stroke.modifier, stroke.usage);
          }
        }
        try {
          Thread.sleep(8);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      if (skipped > 0) {
        status("Sent text; skipped " + skipped + " unsupported character(s).", false);
      }
    });
  }

  void sendMouse(int buttons, int dx, int dy, int wheel) {
    reports.execute(() -> {
      BluetoothHidDevice device = hidDevice;
      BluetoothDevice host = connectedHost;
      if (device == null || host == null) {
        status("Connect a host before using the trackpad.", true);
        return;
      }
      byte[] report = {(byte) buttons, clampByte(dx), clampByte(dy), clampByte(wheel)};
      try {
        if (!device.sendReport(host, HidReportDescriptor.REPORT_MOUSE, report)) {
          status("The host rejected a mouse report. Reconnect the device.", true);
        }
      } catch (Exception error) {
        error("Mouse report failed", error);
      }
    });
  }

  void sendConsumer(int usage) {
    reports.execute(() -> {
      BluetoothHidDevice device = hidDevice;
      BluetoothDevice host = connectedHost;
      if (device == null || host == null) {
        status("Connect a host before sending media keys.", true);
        return;
      }
      byte[] pressed = {(byte) (usage & 255), (byte) ((usage >> 8) & 255)};
      byte[] released = {0, 0};
      try {
        device.sendReport(host, HidReportDescriptor.REPORT_CONSUMER, pressed);
        device.sendReport(host, HidReportDescriptor.REPORT_CONSUMER, released);
      } catch (Exception error) {
        error("Media report failed", error);
      }
    });
  }

  void close() {
    closed = true;
    handler.removeCallbacks(scanTimeout);
    try {
      activity.unregisterReceiver(receiver);
    } catch (Exception ignored) {
      // The receiver may not have registered if Bluetooth setup failed.
    }
    scanning = false;
    try {
      if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery();
    } catch (Exception ignored) {
      // Discovery can already be finished.
    }
    try {
      if (hidDevice != null) {
        if (registered) hidDevice.unregisterApp();
        if (adapter != null) adapter.closeProfileProxy(HID_PROFILE, hidDevice);
      }
    } catch (Exception ignored) {
      // The remote process may already have released the profile.
    }
    hidDevice = null;
    connectedHost = null;
    registered = false;
    reportExecutorShutdown();
  }

  private void reportExecutorShutdown() {
    callbacks.shutdownNow();
    reports.shutdownNow();
  }

  private void sendKeyReport(int modifier, int usage) {
    BluetoothHidDevice device = hidDevice;
    BluetoothDevice host = connectedHost;
    if (device == null || host == null) {
      status("Connect a host before typing.", true);
      return;
    }
    if (usage < 0 || usage > 255 || (usage == 0 && modifier == 0)) return;
    byte[] down = {(byte) modifier, 0, (byte) usage, 0, 0, 0, 0, 0};
    byte[] up = new byte[8];
    try {
      if (!device.sendReport(host, HidReportDescriptor.REPORT_KEYBOARD, down)) {
        status("The host rejected a key report. Reconnect the device.", true);
        return;
      }
      device.sendReport(host, HidReportDescriptor.REPORT_KEYBOARD, up);
    } catch (Exception error) {
      error("Key report failed", error);
    }
  }

  private void onDeviceFound(Intent intent) {
    BluetoothDevice device = parcelableDevice(intent);
    if (device == null) return;
    DeviceEntry entry = entry(device);
    entry.rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, (short) -127);
    listener.onDevicesChanged();
  }

  private void onDiscoveryFinished() {
    handler.removeCallbacks(scanTimeout);
    scanning = false;
    int count = devices.size();
    status("Scan finished · " + count + " device" + (count == 1 ? "" : "s")
        + ". Tap a paired device to connect or a new device to pair.", false);
    listener.onDevicesChanged();
  }

  private void onBondStateChanged(Intent intent) {
    BluetoothDevice device = parcelableDevice(intent);
    if (device == null) return;
    int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
    if (state == BluetoothDevice.BOND_BONDED) {
      DeviceEntry entry = entry(device);
      entry.bonded = true;
      if (device.getAddress().equals(pendingBondAddress)) {
        pendingBondAddress = null;
        status("Paired with " + deviceName(device) + ". Connecting…", false);
        handler.postDelayed(() -> connect(device), 500);
      } else {
        status("Paired with " + deviceName(device) + ".", false);
      }
      listener.onDevicesChanged();
    } else if (state == BluetoothDevice.BOND_BONDING) {
      status("Pairing with " + deviceName(device) + "…", false);
    } else if (state == BluetoothDevice.BOND_NONE
        && device.getAddress().equals(pendingBondAddress)) {
      pendingBondAddress = null;
      status("Pairing was cancelled or failed for " + deviceName(device) + ".", true);
      listener.onDevicesChanged();
    }
  }

  private void onAdapterStateChanged(Intent intent) {
    int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
    if (state == BluetoothAdapter.STATE_ON) {
      status("Bluetooth is on. Refreshing devices…", false);
      refresh();
      init();
    } else if (state == BluetoothAdapter.STATE_OFF) {
      scanning = false;
      connectedHost = null;
      status("Bluetooth was turned off.", true);
      listener.onConnectionChanged();
      listener.onDevicesChanged();
    }
  }

  private DeviceEntry entry(BluetoothDevice device) {
    String address = device.getAddress();
    DeviceEntry entry = devices.get(address);
    if (entry == null) {
      entry = new DeviceEntry(device);
      devices.put(address, entry);
    }
    entry.name = deviceName(device);
    entry.bonded = device.getBondState() == BluetoothDevice.BOND_BONDED;
    entry.connected = connectedHost != null && address.equals(connectedHost.getAddress());
    return entry;
  }

  private BluetoothDevice parcelableDevice(Intent intent) {
    if (Build.VERSION.SDK_INT >= 33) {
      return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
    }
    return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
  }

  private String deviceName(BluetoothDevice device) {
    if (device == null) return "unknown device";
    try {
      String name = device.getName();
      return name == null || name.isBlank() ? device.getAddress() : name;
    } catch (SecurityException error) {
      return device.getAddress();
    }
  }

  private boolean hasConnectPermission() {
    return Build.VERSION.SDK_INT < 31
        || activity.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED;
  }

  private boolean hasScanPermission() {
    return Build.VERSION.SDK_INT < 31
        || activity.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
            == PackageManager.PERMISSION_GRANTED;
  }

  private void status(String message, boolean error) {
    if (!closed) listener.onStatus(message, error);
  }

  private void error(String message, Throwable error) {
    String detail = error == null ? "" : " · " + error.getClass().getSimpleName()
        + ": " + String.valueOf(error.getMessage());
    status(message + detail, true);
  }

  private static byte clampByte(int value) {
    return (byte) Math.max(-127, Math.min(127, value));
  }

  static final class DeviceEntry implements Comparable<DeviceEntry> {
    final BluetoothDevice device;
    String name;
    int rssi = -127;
    boolean bonded;
    boolean connected;

    DeviceEntry(BluetoothDevice device) {
      this.device = device;
      this.name = device.getAddress();
    }

    boolean isConnected() {
      return connected;
    }

    String address() {
      try {
        return device.getAddress();
      } catch (SecurityException error) {
        return "permission required";
      }
    }

    String label() {
      return name == null || name.isBlank() ? address() : name;
    }

    String stateLabel() {
      if (connected) return "CONNECTED";
      if (bonded) return "PAIRED";
      return "NEW";
    }

    @Override
    public int compareTo(DeviceEntry other) {
      if (connected != other.connected) return connected ? -1 : 1;
      if (bonded != other.bonded) return bonded ? -1 : 1;
      return label().compareToIgnoreCase(other.label());
    }
  }

  static final class KeyStroke {
    final int modifier;
    final int usage;

    KeyStroke(int modifier, int usage) {
      this.modifier = modifier;
      this.usage = usage;
    }

    static KeyStroke forCharacter(char value) {
      if (value >= 'a' && value <= 'z') {
        return new KeyStroke(0, 4 + (value - 'a'));
      }
      if (value >= 'A' && value <= 'Z') {
        return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 4 + (value - 'A'));
      }
      if (value >= '1' && value <= '9') {
        return new KeyStroke(0, 30 + (value - '1'));
      }
      if (value == '0') return new KeyStroke(0, 39);
      if (value == ' ') return new KeyStroke(0, 44);
      switch (value) {
        case '\b':
          return new KeyStroke(0, 42);
        case '\n':
          return new KeyStroke(0, 40);
        case '\t':
          return new KeyStroke(0, 43);
        case '-':
          return new KeyStroke(0, 45);
        case '_':
          return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 45);
        case '=':
          return new KeyStroke(0, 46);
        case '+':
          return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 46);
        case '[':
          return new KeyStroke(0, 47);
        case '{':
          return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 47);
        case ']':
          return new KeyStroke(0, 48);
        case '}':
          return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 48);
        case '\\':
          return new KeyStroke(0, 49);
        case '|':
          return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 49);
        case ';':
          return new KeyStroke(0, 51);
        case ':':
          return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 51);
        case '\'':
          return new KeyStroke(0, 52);
        case '"':
          return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 52);
        case '`':
          return new KeyStroke(0, 53);
        case '~':
          return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 53);
        case ',':
          return new KeyStroke(0, 54);
        case '<':
          return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 54);
        case '.':
          return new KeyStroke(0, 55);
        case '>':
          return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 55);
        case '/':
          return new KeyStroke(0, 56);
        case '?':
          return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 56);
        case '!':
          return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 30);
        case '@':
          return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 31);
        case '#':
          return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 32);
        case '$':
          return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 33);
        case '%':
          return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 34);
        case '^':
          return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 35);
        case '&':
          return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 36);
        case '*':
          return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 37);
        case '(':
          return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 38);
        case ')':
          return new KeyStroke(HidReportDescriptor.MOD_SHIFT, 39);
        default:
          return null;
      }
    }
  }
}
