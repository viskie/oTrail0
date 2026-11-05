package in.otrail;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.os.Build;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** A separate, throttled queue: address services never run on the GPS writer or UI thread. */
public final class StopGeocoder implements AutoCloseable {
  interface Lookup {
    Address find(double latitude, double longitude) throws Exception;
  }

  private static StopGeocoder instance;
  private final Context app;
  private final Lookup lookup;
  private final long delayMs;
  private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
  private boolean running, requested, closed;
  // A stalled legacy binder call cannot create an unbounded number of replacement threads.
  private static final ExecutorService LEGACY =
      new ThreadPoolExecutor(
          0,
          1,
          30,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          r -> {
            Thread t = new Thread(r, "legacy-geocoder");
            t.setDaemon(true);
            return t;
          });

  StopGeocoder(Context context, Lookup lookup) {
    this(context, lookup, 1000);
  }

  StopGeocoder(Context context, Lookup lookup, long delayMs) {
    this.app = context.getApplicationContext();
    this.lookup = lookup;
    this.delayMs = delayMs;
  }

  private static synchronized StopGeocoder get(Context c) {
    if (instance == null) {
      Context app = c.getApplicationContext();
      instance = new StopGeocoder(app, (lat, lon) -> lookupAddress(app, lat, lon));
    }
    return instance;
  }

  public static void enqueue(Context c, long stopId) {
    if (stopId > 0) get(c).request();
  }

  public static void backfill(Context c) {
    get(c).request();
  }

  synchronized void request() {
    if (closed) return;
    requested = true;
    if (!running) {
      running = true;
      worker.execute(this::next);
    }
  }

  private void next() {
    synchronized (this) {
      if (closed) return;
      requested = false;
    }
    boolean found = false;
    try (TrailDb db = new TrailDb(app)) {
      List<Long> ids = db.pendingStopIds(1);
      if (!ids.isEmpty()) {
        found = true;
        resolve(db, ids.get(0));
      }
    } catch (Exception ignored) {
      /* Database/lookup failures must not stop GPS recording. */
    }
    synchronized (this) {
      if (closed) return;
      if (found || requested) worker.schedule(this::next, delayMs, TimeUnit.MILLISECONDS);
      else running = false;
    }
  }

  private void resolve(TrailDb db, long id) {
    TrailDb.Stop stop = db.stopById(id);
    if (stop == null || "RESOLVED".equals(stop.geocodeStatus())) return;
    if ("FAILED".equals(stop.geocodeStatus())
        && System.currentTimeMillis() - stop.geocodeAttempt() < TrailDb.GEOCODE_RETRY_MS) return;
    try {
      Address a = lookup.find(stop.lat(), stop.lon());
      if (a == null) {
        db.markGeocodeFailed(id);
        return;
      }
      String locality = first(a.getLocality(), a.getSubLocality()),
          district = first(a.getSubAdminArea()),
          state = first(a.getAdminArea()),
          country = first(a.getCountryName());
      String
          place = first(a.getSubLocality(), locality, a.getFeatureName(), district, state, country),
          road = first(a.getThoroughfare()),
          pin = first(a.getPostalCode());
      if (first(place, locality, district, state, country, road, pin) == null) {
        db.markGeocodeFailed(id);
        return;
      }
      db.saveStopAddress(id, place, locality, district, state, country, pin, road);
    } catch (Exception e) {
      db.markGeocodeFailed(id);
    }
  }

  private static Address lookupAddress(Context c, double lat, double lon) throws Exception {
    if (!Geocoder.isPresent()) return null;
    Geocoder g = new Geocoder(c, Locale.getDefault());
    List<Address> addresses;
    if (Build.VERSION.SDK_INT >= 33) {
      CountDownLatch done = new CountDownLatch(1);
      AtomicReference<List<Address>> result = new AtomicReference<>();
      g.getFromLocation(
          lat,
          lon,
          1,
          new Geocoder.GeocodeListener() {
            @Override
            public void onGeocode(List<Address> a) {
              result.set(a);
              done.countDown();
            }

            @Override
            public void onError(String message) {
              done.countDown();
            }
          });
      if (!done.await(15, TimeUnit.SECONDS)) return null;
      addresses = result.get();
    } else {
      Future<List<Address>> request = LEGACY.submit(() -> legacyLookup(g, lat, lon));
      try {
        addresses = request.get(15, TimeUnit.SECONDS);
      } finally {
        request.cancel(true);
      }
    }
    return addresses == null || addresses.isEmpty() ? null : addresses.get(0);
  }

  @SuppressWarnings("deprecation")
  private static List<Address> legacyLookup(Geocoder g, double lat, double lon) throws Exception {
    return g.getFromLocation(lat, lon, 1);
  }

  private static String first(String... values) {
    for (String s : values) if (s != null && !s.isBlank()) return s;
    return null;
  }

  @Override
  public synchronized void close() {
    closed = true;
    worker.shutdownNow();
  }
}
