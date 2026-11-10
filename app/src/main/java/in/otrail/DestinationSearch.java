package in.otrail;

import android.content.Context;
import android.location.*;
import android.os.Build;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Explicit user searches only; coordinate entry remains usable when the Android geocoder is absent.
 */
public final class DestinationSearch {
  public record Result(String name, RoutePlan.Point point) {}

  private static final ExecutorService LEGACY =
      new ThreadPoolExecutor(
          0,
          1,
          30,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          r -> {
            Thread t = new Thread(r, "destination-search");
            t.setDaemon(true);
            return t;
          });

  public static List<Result> search(Context context, String query) throws Exception {
    String[] xy = query.trim().split("\\s*,\\s*");
    if (xy.length == 2) {
      try {
        RoutePlan.Point p =
            new RoutePlan.Point(Double.parseDouble(xy[0]), Double.parseDouble(xy[1]));
        if (!india(p)) throw new IllegalArgumentException("Choose a destination in India");
        return List.of(new Result(query, p));
      } catch (NumberFormatException ignored) {
      }
    }
    if (query.isBlank()) throw new IllegalArgumentException("Enter a place or latitude, longitude");
    if (!Geocoder.isPresent())
      throw new IllegalStateException(
          "Place search is unavailable on this phone; enter latitude, longitude or hold the map");
    Geocoder geocoder = new Geocoder(context, Locale.getDefault());
    List<Address> found;
    if (Build.VERSION.SDK_INT >= 33) {
      CountDownLatch done = new CountDownLatch(1);
      AtomicReference<List<Address>> result = new AtomicReference<>();
      geocoder.getFromLocationName(
          query,
          5,
          6,
          68,
          38,
          98,
          new Geocoder.GeocodeListener() {
            @Override
            public void onGeocode(List<Address> a) {
              result.set(a);
              done.countDown();
            }

            @Override
            public void onError(String s) {
              done.countDown();
            }
          });
      if (!done.await(15, TimeUnit.SECONDS)) throw new TimeoutException("Place search timed out");
      found = result.get();
    } else {
      Future<List<Address>> task = LEGACY.submit(() -> legacy(geocoder, query));
      try {
        found = task.get(15, TimeUnit.SECONDS);
      } finally {
        task.cancel(true);
      }
    }
    List<Result> out = new ArrayList<>();
    if (found != null) {
      for (Address a : found) {
          if (a.hasLatitude() && a.hasLongitude()) {

              RoutePlan.Point p = new RoutePlan.Point(
                  a.getLatitude(),
                  a.getLongitude()
              );

              if (india(p)) {

                  String name = a.getMaxAddressLineIndex() >= 0
                      ? a.getAddressLine(0)
                      : Objects.toString(a.getFeatureName(), query);

                  String pin = a.getPostalCode();

                  if (pin != null
                          && pin.matches("[1-9][0-9]{5}")
                          && !name.matches(
                              ".*(?<![0-9])"
                              + java.util.regex.Pattern.quote(pin)
                              + "(?![0-9]).*"
                          )) {
                      name = pin+"-"+name + ", " + pin;
                  }

                  out.add(new Result(name, p));
              }
          }
      }
   }

  return out;
}
  @SuppressWarnings("deprecation")
  private static List<Address> legacy(Geocoder g, String q) throws Exception {
    return g.getFromLocationName(q, 5, 6, 68, 38, 98);
  }

  static boolean india(RoutePlan.Point p) {
    return p.lat() >= 6 && p.lat() <= 38 && p.lon() >= 68 && p.lon() <= 98;
  }
}
