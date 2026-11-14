package in.otrail;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;

public final class TrackingService extends Service implements LocationListener {
  public static final long FIX_INTERVAL_MS = 13000;
  private LocationManager manager;
  private TrailDb db;
  private long trip = -1;
  private final StopDetector detector = new StopDetector();
  private long lastElapsed;
  private long requestedInterval=1000;
  private volatile boolean destroyed;
  private android.hardware.SensorManager sensors;
  private android.hardware.Sensor motion;
  private final android.hardware.TriggerEventListener wake=new android.hardware.TriggerEventListener(){public void onTrigger(android.hardware.TriggerEvent event){requestGps(1000);}};
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
            .setContentText("Adaptive GPS · 1s moving / 13–30s stopped · recording continues when the app is closed")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setOngoing(true)
            .build();
    startForeground(10, n);
    if (trip == -1) {
      trip = db.begin();
      StopGeocoder.backfill(this);
      backupWorker.scheduleWithFixedDelay(() -> {
        try { db.driveMetrics(); } catch (Exception e) { db.log("Drive metrics", "ERROR", e.toString()); }
      }, 10, 60, java.util.concurrent.TimeUnit.SECONDS);
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
      sensors=(android.hardware.SensorManager)getSystemService(SENSOR_SERVICE);motion=sensors.getDefaultSensor(android.hardware.Sensor.TYPE_SIGNIFICANT_MOTION);
      requestGps(1000);
    }
    return START_STICKY;
  }

  private void requestGps(long interval){
    if(destroyed||manager==null||checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return;
    try{manager.removeUpdates(this);requestedInterval=interval;
      if(Build.VERSION.SDK_INT>=31)manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,new LocationRequest.Builder(interval).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).setMinUpdateIntervalMillis(interval).setMaxUpdateDelayMillis(0).build(),getMainExecutor(),this);
      else manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,interval,0,this,Looper.getMainLooper());
      if(motion!=null){sensors.cancelTriggerSensor(wake,motion);if(interval>1000)sensors.requestTriggerSensor(wake,motion);}
    }catch(SecurityException|IllegalArgumentException e){db.log("GPS","ERROR",e.toString());}
  }
  @Override
  public void onLocationChanged(Location l) {
    if(l.hasAccuracy()&&l.getAccuracy()>25&&requestedInterval!=1000)requestGps(1000);
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
      if(!detector.isUncertain())NavigationManager.get(this).update(l);
    } catch (RuntimeException e) {
      db.log("Navigation", "ERROR", e.toString());
    }
    long interval=GpsPolling.interval(l.getAccuracy(),l.hasSpeed()?l.getSpeed():0,detector.isStopped(),l.getTime()-detector.arrival(),motion!=null);
    new Handler(Looper.getMainLooper()).post(()->{if(interval!=requestedInterval)requestGps(interval);});
    if (newStopId > 0) StopGeocoder.enqueue(this, newStopId);
  }

  @Override
  public void onProviderDisabled(String p) {
    db.log("GPS", "UNAVAILABLE", "GPS disabled; no stops inferred without fixes.");
  }

  @Override
  public void onDestroy() {
    destroyed=true;
    if(sensors!=null&&motion!=null)sensors.cancelTriggerSensor(wake,motion);
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
