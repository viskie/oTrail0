package in.otrail;

import java.util.*;
import org.json.*;

/** Both offline engines produce the same cached route format; never writes GPS observations. */
public final class OfflineRouteCodec {
  static String brouter(String json) throws Exception {
    JSONObject feature = new JSONObject(json).getJSONArray("features").getJSONObject(0);
    JSONArray points = feature.getJSONObject("geometry").getJSONArray("coordinates");
    JSONObject properties = feature.getJSONObject("properties");
    JSONArray steps = new JSONArray();
    add(steps, points.getJSONArray(0), "Head along the route");
    JSONArray hints = properties.optJSONArray("voicehints");
    if (hints != null) for (int i = 0; i < hints.length(); i++) {
      JSONArray h = hints.getJSONArray(i);
      int index = h.getInt(0), code = h.getInt(1);
      if (index < 0 || index >= points.length()) continue;
      String text = switch (code) {
        case 2 -> "Turn left"; case 3 -> "Turn slightly left"; case 4 -> "Turn sharply left";
        case 5 -> "Turn right"; case 6 -> "Turn slightly right"; case 7 -> "Turn sharply right";
        case 8 -> "Keep left"; case 9 -> "Keep right";
        case 10,11,15 -> "Make a U-turn";
        case 13,14 -> "At the roundabout take exit " + Math.abs(h.optInt(2, 1));
        case 17 -> "Take the exit left"; case 18 -> "Take the exit right";
        default -> "Continue along the route";
      };
      add(steps, points.getJSONArray(index), text);
    }
    add(steps, points.getJSONArray(points.length()-1), "Arrive at destination");
    return encode(points, steps, properties.getDouble("track-length"), properties.getDouble("total-time"));
  }

  static String valhalla(String json) throws Exception {
    JSONObject root = new JSONObject(json);
    if (root.has("error") || root.has("error_code") || root.has("code"))
      throw new IllegalArgumentException(root.optString("error", root.optString("message", "Valhalla could not find a route")));
    JSONObject trip = root.getJSONObject("trip");
    JSONArray legs = trip.getJSONArray("legs"), points = new JSONArray(), steps = new JSONArray();
    for (int i = 0; i < legs.length(); i++) {
      JSONObject leg = legs.getJSONObject(i);
      JSONArray shape = decode(leg.getString("shape"));
      for (int j=0;j<shape.length();j++) points.put(shape.getJSONArray(j));
      JSONArray maneuvers = leg.getJSONArray("maneuvers");
      for (int j=0;j<maneuvers.length();j++) {
        JSONObject m = maneuvers.getJSONObject(j);
        add(steps, shape.getJSONArray(m.getInt("begin_shape_index")), m.getString("instruction"));
      }
    }
    JSONObject summary = trip.getJSONObject("summary");
    return encode(points, steps, summary.getDouble("length")*1000, summary.getDouble("time"));
  }

  static JSONArray decode(String encoded) throws Exception {
    JSONArray out = new JSONArray(); long lat=0, lon=0; int[] cursor={0};
    while (cursor[0]<encoded.length()) {
      lat+=number(encoded,cursor); lon+=number(encoded,cursor);
      out.put(new JSONArray().put(lon/1e6).put(lat/1e6));
      if(out.length()>200000) throw new IllegalArgumentException("Route too large");
    }
    return out;
  }
  private static long number(String s,int[] cursor) {
    long value=0; int shift=0,b;
    do {
      if(cursor[0]>=s.length() || shift>30) throw new IllegalArgumentException("Invalid route geometry");
      b=s.charAt(cursor[0]++)-63;
      if(b<0 || b>63) throw new IllegalArgumentException("Invalid route geometry");
      value|=(long)(b&31)<<shift; shift+=5;
    } while(b>=32);
    return (value&1)!=0 ? ~(value>>1) : value>>1;
  }
  private static void add(JSONArray steps,JSONArray xy,String text) throws Exception {
    steps.put(new JSONObject().put("_instruction",text).put("maneuver",new JSONObject().put("location",xy).put("type","continue")));
  }
  private static String encode(JSONArray points,JSONArray steps,double distance,double duration) throws Exception {
    return new JSONObject().put("code","Ok").put("routes",new JSONArray().put(new JSONObject()
      .put("geometry",new JSONObject().put("type","LineString").put("coordinates",points))
      .put("distance",distance).put("duration",duration)
      .put("legs",new JSONArray().put(new JSONObject().put("steps",steps))))) .toString();
  }
}
