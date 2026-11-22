package in.otrail.extensions.btkeyboard;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import in.otrail.modules.ModuleV1;
import java.util.ArrayList;
import java.util.List;

public final class BtKeyboardModule implements ModuleV1 {
  private static final int PERMISSIONS = 940;
  private Activity activity;
  private LinearLayout root;
  private TextView status;

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

  private String[] permissions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      return new String[] {
        android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.BLUETOOTH_ADVERTISE,
        android.Manifest.permission.BLUETOOTH_SCAN
      };
    }
    return new String[] {
      android.Manifest.permission.BLUETOOTH,
      android.Manifest.permission.BLUETOOTH_ADMIN
    };
  }

  private List<String> missingPermissions() {
    List<String> missing = new ArrayList<>();
    for (String permission : permissions()) {
      if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
        missing.add(permission);
      }
    }
    return missing;
  }

  private void requestPermissionsIfNeeded() {
    List<String> missing = missingPermissions();
    if (!missing.isEmpty()) {
      activity.requestPermissions(missing.toArray(new String[0]), PERMISSIONS);
      status.setText("Bluetooth permissions are required before opening the keyboard.");
    } else {
      status.setText("Bluetooth permissions granted. Open the keyboard app.");
    }
  }

  private void openKeyboard() {
    if (!missingPermissions().isEmpty()) {
      requestPermissionsIfNeeded();
      return;
    }
    Intent launch = activity.getPackageManager().getLaunchIntentForPackage("com.example.btcombokeyboard");
    if (launch != null) {
      launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      activity.startActivity(launch);
      status.setText("Launched Bluetooth keyboard.");
    } else {
      status.setText("Bluetooth keyboard app is not installed. Install it from GitHub, then reopen this module.");
      try {
        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/viskie/btkbDroid/releases")));
      } catch (Exception ignored) {
      }
    }
  }

  @Override
  public View create(Activity activity, Host host) throws Exception {
    this.activity = activity;
    root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(12), dp(12), dp(12), dp(12));
    root.addView(label("Bluetooth keyboard module", 20));
    root.addView(label("Requests Bluetooth permissions and launches the BT combo keyboard app. Install the keyboard app first if it is missing.", 13));
    status = label("Preparing Bluetooth permissions...", 13);
    root.addView(status);
    Button open = button("Open Bluetooth keyboard", this::openKeyboard);
    root.addView(open, new LinearLayout.LayoutParams(-1, -2));
    Button install = button("Install keyboard app from GitHub", () -> {
      try {
        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/viskie/btkbDroid/releases")));
      } catch (Exception ignored) {
      }
    });
    root.addView(install, new LinearLayout.LayoutParams(-1, -2));
    requestPermissionsIfNeeded();
    return root;
  }

  @Override
  public void permissionResult(int request, String[] permissions, int[] grants) {
    if (request != PERMISSIONS) return;
    if (missingPermissions().isEmpty()) {
      status.setText("Bluetooth permissions granted. Open the keyboard app.");
    } else {
      status.setText("Some Bluetooth permissions were denied. The keyboard cannot run without them.");
    }
  }

  @Override
  public void resume() {}

  @Override
  public void pause() {}

  @Override
  public void close() {}
}
