package in.otrail.extensions.opay;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures the current/last GPS fix for a payment snapshot. The fix is stored with the
 * pending transaction; it never leaves the phone and is not required for payment.
 */
final class LocationFix {
  interface Listener {
    void onFix(Double latitude, Double longitude, Float accuracy, String provider, long time);
  }

  private LocationFix() {}

  static boolean permitted(Activity activity) {
    return activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        == PackageManager.PERMISSION_GRANTED
        || activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        == PackageManager.PERMISSION_GRANTED;
  }

  static void capture(Activity activity, Listener listener) {
    if (activity == null || listener == null || !permitted(activity)) return;
    LocationManager manager;
    try {
      manager = (LocationManager) activity.getSystemService(Activity.LOCATION_SERVICE);
    } catch (RuntimeException error) {
      return;
    }
    if (manager == null) return;

    Location best = null;
    for (String provider : new String[]{LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
      try {
        Location candidate = manager.getLastKnownLocation(provider);
        if (candidate == null) continue;
        if (best == null || candidate.getTime() > best.getTime()) best = candidate;
      } catch (SecurityException | IllegalArgumentException ignored) {
        // Provider unavailable or permission revoked between checks.
      }
    }
    long now = System.currentTimeMillis();
    AtomicBoolean delivered = new AtomicBoolean();
    if (best != null && now - best.getTime() < 10 * 60_000L) {
      deliver(listener, best, delivered);
      return;
    }

    final Location fallback = best;
    Handler main = new Handler(Looper.getMainLooper());
    Runnable timeout = () -> {
      if (fallback != null) deliver(listener, fallback, delivered);
    };
    main.postDelayed(timeout, 8000);
    try {
      manager.getCurrentLocation(LocationManager.GPS_PROVIDER, null, activity.getMainExecutor(),
          location -> {
            if (location != null) {
              main.removeCallbacks(timeout);
              deliver(listener, location, delivered);
            }
          });
      manager.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, activity.getMainExecutor(),
          location -> {
            if (location != null) {
              main.removeCallbacks(timeout);
              deliver(listener, location, delivered);
            }
          });
    } catch (SecurityException | IllegalArgumentException error) {
      if (fallback != null) deliver(listener, fallback, delivered);
    }
  }

  private static void deliver(Listener listener, Location location, AtomicBoolean delivered) {
    if (location == null || !delivered.compareAndSet(false, true)) return;
    String provider = location.getProvider() == null ? "" : location.getProvider();
    listener.onFix(location.getLatitude(), location.getLongitude(),
        location.hasAccuracy() ? location.getAccuracy() : null, provider, location.getTime());
  }
}
