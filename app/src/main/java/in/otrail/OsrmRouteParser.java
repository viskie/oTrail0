package in.otrail;

import java.util.*;
import org.json.*;

public final class OsrmRouteParser {
  private OsrmRouteParser() {}

  public static RoutePlan parse(String json) throws Exception {
    JSONObject root = new JSONObject(json);
    if (!"Ok".equals(root.optString("code")))
      throw new IllegalArgumentException(
          "No driving route: " + root.optString("message", root.optString("code")));
    JSONObject r = root.getJSONArray("routes").getJSONObject(0);
    JSONArray coordinates = r.getJSONObject("geometry").getJSONArray("coordinates");
    if (coordinates.length() > 200000) throw new IllegalArgumentException("Route is too large");
    List<RoutePlan.Point> points = new ArrayList<>();
    for (int i = 0; i < coordinates.length(); i++) {
      JSONArray p = coordinates.getJSONArray(i);
      points.add(new RoutePlan.Point(p.getDouble(1), p.getDouble(0)));
    }
    RoutePlan geometry =
        new RoutePlan(points, List.of(), r.getDouble("distance"), r.getDouble("duration"));
    List<RoutePlan.Step> steps = new ArrayList<>();
    int cursor = 0;
    JSONArray legs = r.getJSONArray("legs");
    for (int l = 0; l < legs.length(); l++) {
      JSONArray raw = legs.getJSONObject(l).getJSONArray("steps");
      for (int i = 0; i < raw.length(); i++) {
        JSONObject s = raw.getJSONObject(i), m = s.getJSONObject("maneuver");
        JSONArray xy = m.getJSONArray("location");
        RoutePlan.Point at = new RoutePlan.Point(xy.getDouble(1), xy.getDouble(0));
        double best = Double.POSITIVE_INFINITY;
        int index = cursor;
        for (int p = cursor; p < points.size(); p++) {
          double d = RoutePlan.metres(at, points.get(p));
          if (d < best) {
            best = d;
            index = p;
          }
          if (d < .5) break;
        }
        cursor = index;
        steps.add(
            new RoutePlan.Step(
                geometry.cumulative[index],
                s.optString("_instruction", instruction(m, s.optString("name"), s.optString("ref")))));
      }
    }
    if (steps.isEmpty()) throw new IllegalArgumentException("Route contains no turn instructions");
    return new RoutePlan(points, steps, geometry.distance, geometry.duration);
  }

  static String instruction(JSONObject m, String name, String ref) {
    String type = m.optString("type"),
        modifier = m.optString("modifier", "straight").replace('_', ' '),
        road = name.isBlank() ? ref : name;
    String text =
        switch (type) {
          case "depart" -> "Head " + heading(m.optInt("bearing_after", 0));
          case "arrive" -> "Arrive at destination";
          case "roundabout", "rotary" ->
              "Enter roundabout"
                  + (m.optInt("exit", 0) > 0 ? " · take exit " + m.optInt("exit") : "");
          case "exit roundabout", "exit rotary" -> "Exit roundabout";
          case "merge" -> "Merge " + modifier;
          case "on ramp" -> "Take the ramp " + modifier;
          case "off ramp" -> "Take the exit " + modifier;
          case "fork" -> "Keep " + modifier + " at the fork";
          case "end of road" -> "At the end of the road, turn " + modifier;
          case "turn" -> modifier.equals("uturn") ? "Make a U-turn" : "Turn " + modifier;
          case "new name", "continue", "notification" -> "Continue " + modifier;
          default -> "Continue " + modifier;
        };
    return !road.isBlank() && !type.equals("arrive") ? text + " onto " + road : text;
  }

  private static String heading(int b) {
    return new String[] {
          "north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"
        }
        [(int) Math.floorMod(Math.round(b / 45.0), 8)];
  }
}
