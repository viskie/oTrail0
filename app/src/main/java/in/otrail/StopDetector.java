package in.otrail;

/** Anchored dwell detection; uncertain fixes and long gaps never imply a stop. */
public final class StopDetector {
    public static final long MIN_STOP_MS = 120_000, MAX_GAP_MS = 60_000;
    public static final double RADIUS_METERS = 35;
    private long start, last;
    private double latitude, longitude;
    private boolean stopped;
    private int departureEvidence;
    private long departureAt;
    public long persistedStopId = -1;
    public record Event(boolean opened, boolean closed, long arrival, long departure, double lat, double lon) {}
    public Event accept(long time, double lat, double lon, float accuracy, float speed) {
        if (time <= last) return null;
        if (accuracy > 50) {
            Event e = stopped ? new Event(false, true, start, last, latitude, longitude) : null;
            reset();
            if (accuracy > 50) return e;
            seed(time, lat, lon);
            return e;
        }
        if (start == 0) { seed(time, lat, lon); return null; }
        double displacement = distance(latitude, longitude, lat, lon);
        double tolerance = RADIUS_METERS + Math.min(50, accuracy * 2);
        boolean moving = stopped
            ? displacement > tolerance && (speed > 1.5f || displacement > tolerance + 35)
            : displacement > RADIUS_METERS || speed > 1.5f;
        if (stopped && moving && !(displacement > 500 && speed > 3)) {
            if (departureEvidence++ == 0) departureAt = last;
            if (departureEvidence < 3) return null;
        } else if (!moving) departureEvidence = 0;
        if (moving) {
            Event e = stopped ? new Event(false, true, start,
                departureEvidence >= 3 ? departureAt : last, latitude, longitude) : null;
            departureEvidence = 0;
            seed(time, lat, lon); stopped = false;
            return e;
        }
        if (!stopped && time - last > MAX_GAP_MS) {
            seed(time, lat, lon);
            return null;
        }
        last = time;
        if (!stopped && time - start > MIN_STOP_MS) {
            stopped = true;
            return new Event(true, false, start, time, latitude, longitude);
        }
        return null;
    }
    /** Restore only persisted observations; the next fix confirms continuation or departure. */
    public void restore(long arrival, long observed, double lat, double lon, boolean open) {
        start = arrival; last = observed; latitude = lat; longitude = lon; stopped = open;
    }
    public boolean isStopped() { return stopped; }
    public long arrival() { return start; }
    public void reset() { start = last = 0; stopped = false; persistedStopId = -1; departureEvidence = 0; }
    private void seed(long t, double lat, double lon) { start = last = t; latitude = lat; longitude = lon; }
    public static double distance(double a, double b, double c, double d) {
        double p = Math.toRadians(c-a), q = Math.toRadians(d-b);
        double h = Math.sin(p/2)*Math.sin(p/2)+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(c))*Math.sin(q/2)*Math.sin(q/2);
        return 6371000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0, 1-h)));
    }
}
