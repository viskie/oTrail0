package in.otrail;

import java.util.*;

/** Display-only geometry. Inferred edges never enter the GPS fixes table or GeoJSON export. */
public final class TrailSegments {
  public record Sample(long id, long trip, long time, double lat, double lon) {}

  public record Segment(boolean inferred, List<Sample> points) {}


  public static List<Segment> build(List<Sample> samples, int maxPoints) {
    List<Segment> result = new ArrayList<>();
    if (samples.isEmpty()) return result;
    List<Sample> observed = new ArrayList<>();
    observed.add(samples.get(0));
    for (int i = 1; i < samples.size(); i++) {
      Sample a = samples.get(i - 1), b = samples.get(i);
      long elapsed = b.time - a.time;
      boolean contiguous = a.trip == b.trip && elapsed > 0 && elapsed <= StopDetector.MAX_GAP_MS;
      if (contiguous) observed.add(b);
      else {
        result.add(new Segment(false, thin(observed, maxPoints)));
        observed = new ArrayList<>();
        observed.add(b);
        double distance = StopDetector.distance(a.lat, a.lon, b.lat, b.lon);
        if (elapsed > 0
            && distance / (elapsed / 1000.0) <= 60) result.add(new Segment(true, List.of(a, b)));
      }
    }
    result.add(new Segment(false, thin(observed, maxPoints)));
    return result;
  }

  private static List<Sample> thin(List<Sample> points, int max) {
    if (points.size() <= Math.max(2, max)) return List.copyOf(points);
    List<Sample> out = new ArrayList<>();
    int step = Math.max(1, (points.size() + max - 1) / max);
    for (int i = 0; i < points.size() - 1; i += step) out.add(points.get(i));
    out.add(points.get(points.size() - 1));
    return out;
  }
}
