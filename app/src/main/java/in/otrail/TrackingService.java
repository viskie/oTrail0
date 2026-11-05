package in.otrail;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;

public final class TrackingService extends Service implements LocationListener {
  public static final long FIX_INTERVAL_MS = 5000;
  private LocationManager manager;
  private TrailDb db;
  private long trip = -1;
  private final StopDetector detector = new StopDetector();
  private long lastElapsed;
  private final java.util.concurrent.ExecutorService gpsWriter =
      java.util.concurrent.Executors.newSingleThreadExecutor();
  private final java.util.concurrent.ScheduledExecutorService backupWorker =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

  @Override
  public void onCreate() {
    super.onCreate();
    db = new TrailDb(this);
    manager = (LocationManager) getSystemService(LOCATION_SERVICE);
    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
        .createNotificationChannel(
            new NotificationChannel(
                "tracking", "GPS trail recording", NotificationManager.IMPORTANCE_LOW));
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) {
      stopSelf();
      return START_NOT_STICKY;
    }
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification n =
        new Notification.Builder(this, "tracking")
            .setContentTitle("oTraiL · always recording")
            .setContentText("GPS every 5 seconds · recording continues when the app is closed")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setOngoing(true)
            .build();
    startForeground(10, n);
    if (trip == -1) {
      trip = db.begin();
      StopGeocoder.backfill(this);
      backupWorker.scheduleWithFixedDelay(
          () -> StopGeocoder.backfill(this), 1, 1, java.util.concurrent.TimeUnit.HOURS);
      backupWorker.scheduleWithFixedDelay(
          () -> {
            try {
              new TrailBackup(this).save(db, false);
            } catch (Exception e) {
              db.log("Trail backup", "FAILED", e.toString());
            }
          },
          30,
          60,
          java.util.concurrent.TimeUnit.SECONDS);
      db.restoreDetector(trip, detector);
      try {
        manager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, FIX_INTERVAL_MS, 0, this, Looper.getMainLooper());
      } catch (SecurityException | IllegalArgumentException e) {
        db.log("GPS", "ERROR", e.toString());
        db.finish(trip);
        stopSelf();
      }
    }
    return START_STICKY;
  }

  @Override
  public void onLocationChanged(Location l) {
    Location fix = new Location(l);
    try {
      gpsWriter.execute(() -> storeFix(fix));
    } catch (java.util.concurrent.RejectedExecutionException ignored) {
    }
  }

  private void storeFix(Location l) {
    if (trip == -1
        || !l.hasAccuracy()
        || l.getAccuracy() > 100
        || l.getElapsedRealtimeNanos() <= lastElapsed
        || SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos() > 30_000_000_000L)
      return;
    lastElapsed = l.getElapsedRealtimeNanos();
    long active = db.activeTrip();
    if (active != trip) {
      trip = active == -1 ? db.begin() : active;
      detector.reset();
      db.restoreDetector(trip, detector);
    }
    long newStopId = db.record(trip, l, detector);
    try {
      NavigationManager.get(this).update(l);
    } catch (RuntimeException e) {
      db.log("Navigation", "ERROR", e.toString());
    }
    if (newStopId > 0) StopGeocoder.enqueue(this, newStopId);
  }

  @Override
  public void onProviderDisabled(String p) {
    db.log("GPS", "UNAVAILABLE", "GPS disabled; no stops inferred without fixes.");
  }

  @Override
  public void onDestroy() {
    manager.removeUpdates(this);
    gpsWriter.execute(
        () -> {
          backupWorker.execute(() -> db.close());
          backupWorker.shutdown();
        });
    gpsWriter.shutdown();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }
}
