package in.otrail.extensions.btkeyboard;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import in.otrail.modules.ModuleV1;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class BtKeyboardModule implements ModuleV1 {
  private static final String KEYBOARD_PACKAGE = "com.example.btcombokeyboard";
  private static final String BUNDLED_APK = "btkeyboard.apk";
  private static final String INSTALL_FILE = "BtComboKeyboard-1.1.apk";
  private Activity activity;
  private Host host;
  private LinearLayout root;
  private TextView status;
  private Button install;
  private boolean pendingInstall;

  @Override
  public String id() { return "mod4"; }

  private int dp(int n) {
    return Math.round(n * activity.getResources().getDisplayMetrics().density);
  }

  private TextView label(String text, int size) {
    TextView v = new TextView(activity);
    v.setText(text);
    v.setTextSize(size);
    v.setPadding(dp(8), dp(5), dp(8), dp(5));
    return v;
  }

  private Button button(String text, Runnable action) {
    Button b = new Button(activity);
    b.setText(text);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private String installedVersion() {
    try {
      PackageInfo info = activity.getPackageManager().getPackageInfo(KEYBOARD_PACKAGE, 0);
      return info.versionName;
    } catch (PackageManager.NameNotFoundException e) {
      return null;
    }
  }

  private void refreshStatus() {
    String version = installedVersion();
    if (version == null) {
      status.setText("Keyboard not installed. The APK is bundled inside this module; no GitHub download is needed.");
      install.setText("Install bundled keyboard");
    } else {
      status.setText("Bluetooth keyboard " + version + " is installed.");
      install.setText("Reinstall / update bundled keyboard");
    }
  }

  private boolean canInstallApks() {
    return Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls();
  }

  private void requestInstallPermission() {
    pendingInstall = true;
    status.setText("Allow oTraiL to install unknown apps, then return here. The install will continue automatically.");
    try {
      activity.startActivity(new Intent(
          Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
          Uri.parse("package:" + activity.getPackageName())));
    } catch (Exception e) {
      pendingInstall = false;
      status.setText("Open Android Settings and allow oTraiL to install unknown apps.");
    }
  }

  private void installKeyboard() {
    if (!canInstallApks()) {
      requestInstallPermission();
      return;
    }
    status.setText("Preparing bundled keyboard…");
    install.setEnabled(false);
    new Thread(() -> {
      try {
        File apk = extractBundledApk();
        activity.runOnUiThread(() -> {
          install.setEnabled(true);
          launchInstaller(apk);
        });
      } catch (Exception e) {
        activity.runOnUiThread(() -> {
          install.setEnabled(true);
          status.setText("Could not prepare the bundled keyboard: " + e.getMessage());
        });
      }
    }, "bt-keyboard-extract").start();
  }

  private File extractBundledApk() throws Exception {
    File folder = new File(activity.getFilesDir(), "transferred-media");
    if (!folder.isDirectory() && !folder.mkdirs()) throw new Exception("storage unavailable");
    File out = new File(folder, INSTALL_FILE);
    try (ZipFile zip = new ZipFile(host.packageFile())) {
      ZipEntry entry = zip.getEntry(BUNDLED_APK);
      if (entry == null) throw new Exception("module package has no keyboard APK");
      if (out.isFile() && out.length() == entry.getSize()) return out;
      File part = new File(folder, INSTALL_FILE + ".part");
      try (InputStream in = zip.getInputStream(entry);
          OutputStream stream = new FileOutputStream(part)) {
        byte[] buffer = new byte[65536];
        int n;
        while ((n = in.read(buffer)) != -1) stream.write(buffer, 0, n);
      }
      if (out.exists() && !out.delete()) throw new Exception("cannot replace old installer file");
      if (!part.renameTo(out)) throw new Exception("cannot finish installer file");
      return out;
    }
  }

  private void launchInstaller(File apk) {
    Uri uri = Uri.parse("content://" + activity.getPackageName()
        + ".transferredmedia/" + Uri.encode(apk.getName()));
    Intent intent = new Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    try {
      activity.startActivity(intent);
      status.setText("Confirm the install in the Android installer. Return here when it finishes.");
    } catch (Exception e) {
      status.setText("No package installer is available on this device.");
    }
  }

  private void openKeyboard() {
    if (installedVersion() == null) {
      installKeyboard();
      return;
    }
    Intent launch = activity.getPackageManager().getLaunchIntentForPackage(KEYBOARD_PACKAGE);
    if (launch == null) {
      status.setText("The keyboard is installed but has no launch screen. Enable it in Android keyboard settings.");
      return;
    }
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    activity.startActivity(launch);
    status.setText("Opened Bluetooth keyboard.");
  }

  @Override
  public View create(Activity activity, Host host) throws Exception {
    this.activity = activity;
    this.host = host;
    root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(12), dp(12), dp(12), dp(12));
    root.addView(label("Bluetooth keyboard", 20));
    root.addView(label(
        "Self-contained module: installs the bundled Bluetooth keyboard APK, then opens it. No GitHub download is used.",
        13));
    status = label("Checking installed keyboard…", 13);
    root.addView(status);
    install = button("Install bundled keyboard", this::installKeyboard);
    root.addView(install, new LinearLayout.LayoutParams(-1, -2));
    Button open = button("Open Bluetooth keyboard", this::openKeyboard);
    root.addView(open, new LinearLayout.LayoutParams(-1, -2));
    refreshStatus();
    return root;
  }

  @Override
  public void permissionResult(int request, String[] permissions, int[] grants) {}

  @Override
  public void resume() {
    if (activity == null) return;
    if (pendingInstall && canInstallApks()) {
      pendingInstall = false;
      installKeyboard();
    } else {
      refreshStatus();
    }
  }

  @Override
  public void pause() {}

  @Override
  public void close() {}
}
