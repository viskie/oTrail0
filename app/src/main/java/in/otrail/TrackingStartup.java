package in.otrail;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;

/** Shared startup path for UI, boot and application replacement. */
public final class TrackingStartup {
    private TrackingStartup() {}
    public static boolean hasLocation(Context c) { return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED; }
    public static boolean rebootReady(Context c) { return hasLocation(c)&&c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)==PackageManager.PERMISSION_GRANTED; }
    public static boolean start(Context c, boolean fromBackground) {
        if (!hasLocation(c) || fromBackground&&!rebootReady(c)) return false;
        try { c.startForegroundService(new Intent(c,TrackingService.class)); return true; }
        catch (IllegalStateException | SecurityException e) { try(TrailDb db=new TrailDb(c)){db.log("Always-on tracker","START BLOCKED",e.toString());}return false; }
    }
}
