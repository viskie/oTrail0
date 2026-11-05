package in.otrail;

import android.content.*;

/** Credential-protected trail data becomes available after the first unlock. */
public final class BootReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    String action = intent.getAction();
    if (Intent.ACTION_BOOT_COMPLETED.equals(action)
        || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
      AppUpdater.schedule(context);
      if (!TrackingStartup.start(context, true)) {
        try (TrailDb db = new TrailDb(context)) {
          db.log(
              "Always-on tracker",
              "SETUP REQUIRED",
              "Grant precise location and Allow all the time for automatic startup after"
                  + " reboot/update.");
        }
      }
    }
  }
}
