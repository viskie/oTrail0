package in.otrail;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Light personal use; replace the HTTPS OSRM endpoint for a production/self-hosted service. */
public final class OnlineRouting {
  public static final String DEFAULT_SERVER =
      "https://routing.openstreetmap.de/routed-car/route/v1/driving/";

  public static String server(String value) throws Exception {
    URI uri = new URI(value.trim());
    if (!"https".equals(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null)
      throw new IllegalArgumentException(
          "Enter an HTTPS OSRM driving endpoint without credentials or query parameters");
    return value.trim().endsWith("/") ? value.trim() : value.trim() + "/";
  }

  public static String route(String endpoint, RoutePlan.Point from, RoutePlan.Point to)
      throws Exception {
    return route(endpoint,java.util.List.of(from,to),"auto");
  }

  public static String route(String endpoint,java.util.List<RoutePlan.Point> points,String preference) throws Exception {
    return RoutePreferences.select(get(routeUrl(endpoint,points,preference),12_000_000),preference);
  }
  static String routeUrl(String endpoint,java.util.List<RoutePlan.Point> points,String preference)throws Exception {
    if(points.size()<2||points.size()>10)throw new IllegalArgumentException("Choose 2–10 route points");
    StringBuilder coordinates=new StringBuilder();
    for(RoutePlan.Point p:points){if(coordinates.length()>0)coordinates.append(';');coordinates.append(String.format(Locale.US,"%.6f,%.6f",p.lon(),p.lat()));}
    String url=server(endpoint)+coordinates+"?steps=true&overview=full&geometries=geojson&alternatives="+(!RoutePreferences.valid(preference).equals("auto"));
    return url;
  }

  static String get(String url, int maxBytes) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setConnectTimeout(12000);
    c.setReadTimeout(20000);
    c.setRequestProperty("User-Agent", "oTraiL/0.2.4 (in.otrail; personal navigation)");
    try {
      int code = c.getResponseCode();
      if (code != 200)
        throw new IOException(
            code == 429
                ? "Routing server is busy; try again later"
                : "Server returned HTTP " + code);
      try (InputStream in = c.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) {
          if (out.size() + n > maxBytes) throw new IOException("Response exceeds size limit");
          out.write(b, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8.name());
      }
    } finally {
      c.disconnect();
    }
  }
}
