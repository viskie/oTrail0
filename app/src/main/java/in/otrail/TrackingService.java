package in.otrail;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;

public final class TrackingService extends Service implements LocationListener {
    private LocationManager manager; private TrailDb db; private long trip=-1;private final StopDetector detector=new StopDetector();private long lastElapsed;
    @Override public void onCreate(){super.onCreate();db=new TrailDb(this);manager=(LocationManager)getSystemService(LOCATION_SERVICE);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel("tracking","GPS trail recording",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&"STOP".equals(intent.getAction())){if(trip==-1)trip=db.activeTrip();if(trip!=-1)db.finish(trip);stopSelf();return START_NOT_STICKY;}
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){stopSelf();return START_NOT_STICKY;}
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,TrackingService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,"tracking").setContentTitle("oTraiL · recording your trail").setContentText("GPS stays on with the screen off. Tap Stop to finish.").setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
        startForeground(10,n);
        if(trip==-1){trip=db.begin();db.recoverStops(trip);try{manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,5000,0,this,Looper.getMainLooper());}catch(SecurityException|IllegalArgumentException e){db.log("GPS","ERROR",e.toString());db.finish(trip);stopSelf();}}
        return START_STICKY;
    }
    @Override public void onLocationChanged(Location l){if(trip==-1||!l.hasAccuracy()||l.getAccuracy()>100||l.getElapsedRealtimeNanos()<=lastElapsed||SystemClock.elapsedRealtimeNanos()-l.getElapsedRealtimeNanos()>30_000_000_000L)return;lastElapsed=l.getElapsedRealtimeNanos();db.record(trip,l,detector);}
    @Override public void onProviderDisabled(String p){db.log("GPS","UNAVAILABLE","GPS disabled; no stops inferred without fixes.");}
    @Override public void onDestroy(){manager.removeUpdates(this);db.close();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
