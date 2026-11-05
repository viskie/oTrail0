package in.otrail;

/** Pure GPS guidance state; no UI, network, speech or writes to trail history. */
public final class NavigationProgress {
  public record Guidance(
      String instruction,
      double untilTurn,
      double remaining,
      double seconds,
      boolean offRoute,
      boolean arrived,
      boolean accepted) {}

  private final RoutePlan route;
  private double progress = Double.NaN;
  private long lastTime;
  private int offRouteCount, arrivalCount;

  public NavigationProgress(RoutePlan route) {
    this.route = route;
  }

  public Guidance update(RoutePlan.Point point, float accuracy, long time) {
    if (time <= lastTime || !Float.isFinite(accuracy) || accuracy < 0 || accuracy > 50)
      return guidance(false, false, false);
    double elapsed = lastTime == 0 ? 0 : (time - lastTime) / 1000.0;
    lastTime = time;
    RoutePlan.Match match =
        route.match(
            point,
            Double.isNaN(progress) ? 0 : Math.max(0, progress - 35),
            Double.isNaN(progress)
                ? Double.POSITIVE_INFINITY
                : progress + Math.max(800, elapsed * 60 + 200));
    boolean outside = match.distance() > Math.max(60, accuracy * 2);
    if (accuracy <= 35) offRouteCount = outside ? offRouteCount + 1 : 0;
    else offRouteCount = 0;
    if (!outside && Double.isFinite(match.distance()))
      progress = Double.isNaN(progress) ? match.along() : Math.max(progress, match.along());
    boolean atEnd =
        !Double.isNaN(progress)
            && route.length() - progress <= 40
            && RoutePlan.metres(point, route.points.get(route.points.size() - 1)) <= 50
            && accuracy <= 25;
    arrivalCount = atEnd ? arrivalCount + 1 : 0;
    return guidance(offRouteCount >= 3, arrivalCount >= 2, true);
  }

  private Guidance guidance(boolean off, boolean arrived, boolean accepted) {
    double along = Double.isNaN(progress) ? 0 : progress,
        left = Math.max(0, route.length() - along);
    RoutePlan.Step next = null;
    if (along < 25 && !route.steps.isEmpty() && route.steps.get(0).along() < 1)
      next = route.steps.get(0);
    else
      for (RoutePlan.Step step : route.steps)
        if (step.along() > along + 10) {
          next = step;
          break;
        }
    if (next == null && !route.steps.isEmpty()) next = route.steps.get(route.steps.size() - 1);
    return new Guidance(
        arrived
            ? "You have arrived"
            : next == null ? "Continue to destination" : next.instruction(),
        next == null ? left : Math.max(0, next.along() - along),
        left,
        route.length() == 0 ? 0 : route.duration * left / route.length(),
        off,
        arrived,
        accepted);
  }
}
