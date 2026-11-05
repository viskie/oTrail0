package in.otrail;

import java.util.*;

/** Immutable geometry shared by online and future offline routing providers. */
public final class RoutePlan {
  public record Point(double lat, double lon) {
    public Point {
      if (!Double.isFinite(lat)
          || !Double.isFinite(lon)
          || Math.abs(lat) > 90
          || Math.abs(lon) > 180) throw new IllegalArgumentException("Invalid route coordinate");
    }
  }

  public record Step(double along, String instruction) {}

  public record Match(double along, double distance) {}

  public final List<Point> points;
  public final List<Step> steps;
  public final double distance, duration;
  public final double[] cumulative;

  public RoutePlan(List<Point> points, List<Step> steps, double distance, double duration) {
    if (points.size() < 2
        || !Double.isFinite(distance)
        || !Double.isFinite(duration)
        || distance < 0
        || duration < 0) throw new IllegalArgumentException("Invalid route");
    this.points = List.copyOf(points);
    this.steps = List.copyOf(steps);
    this.distance = distance;
    this.duration = duration;
    cumulative = new double[points.size()];
    for (int i = 1; i < points.size(); i++)
      cumulative[i] = cumulative[i - 1] + metres(points.get(i - 1), points.get(i));
    double previous = -1;
    for (Step s : steps) {
      if (!Double.isFinite(s.along) || s.along < previous || s.along < 0 || s.instruction == null)
        throw new IllegalArgumentException("Invalid route step");
      previous = s.along;
    }
  }

  public double length() {
    return cumulative[cumulative.length - 1];
  }

  public static double metres(Point a, Point b) {
    return StopDetector.distance(a.lat, a.lon, b.lat, b.lon);
  }

  public Match match(Point p, double minAlong, double maxAlong) {
    double best = Double.POSITIVE_INFINITY, along = 0;
    double scale = Math.cos(Math.toRadians(p.lat));
    for (int i = 1; i < points.size(); i++) {
      if (cumulative[i] < minAlong || cumulative[i - 1] > maxAlong) continue;
      Point a = points.get(i - 1), b = points.get(i);
      double ax = (a.lon - p.lon) * 111320 * scale,
          ay = (a.lat - p.lat) * 111320,
          bx = (b.lon - p.lon) * 111320 * scale,
          by = (b.lat - p.lat) * 111320;
      double dx = bx - ax,
          dy = by - ay,
          denom = dx * dx + dy * dy,
          t = denom == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / denom));
      double at = cumulative[i - 1] + t * (cumulative[i] - cumulative[i - 1]);
      if (at < minAlong || at > maxAlong) continue;
      double distance = Math.hypot(ax + t * dx, ay + t * dy);
      if (distance < best) {
        best = distance;
        along = at;
      }
    }
    return new Match(along, best);
  }
}
