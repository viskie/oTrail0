package in.otrail;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/** Keeps the module process alive so background Debian shell PTYs are not reclaimed. */
public final class ModuleSessionService extends Service {
  private static final String CHANNEL = "module-sessions";
  private static final int NOTIFICATION_ID = 731;

  static void start(Context context) {
    context.startForegroundService(new Intent(context, ModuleSessionService.class));
  }

  static void stop(Context context) {
    context.stopService(new Intent(context, ModuleSessionService.class));
  }

  @Override public void onCreate() {
    super.onCreate();
    NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    if (manager.getNotificationChannel(CHANNEL) == null) {
      manager.createNotificationChannel(new NotificationChannel(
          CHANNEL, "Debian shell sessions", NotificationManager.IMPORTANCE_LOW));
    }
    PendingIntent open = PendingIntent.getActivity(this, 0,
        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification notification = new Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("Debian shell sessions are running")
        .setContentText("Open oTraiL to return to your shell tabs")
        .setContentIntent(open)
        .setOngoing(true)
        .build();
    startForeground(NOTIFICATION_ID, notification);
  }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    return START_NOT_STICKY;
  }

  @Override public IBinder onBind(Intent intent) {
    return null;
  }
}
