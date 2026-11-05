package in.otrail;

import java.net.URI;
import org.json.JSONObject;

/**
 * Restrict update artifacts to this repository; Android signing verification is an additional
 * check.
 */
public record UpdateManifest(
    long versionCode, String versionName, String url, String sha256, long size, String notes) {
  public static final String PREFIX =
      "https://raw.githubusercontent.com/viskie/oTrail0/main/releases/";

  public static UpdateManifest parse(String text) throws Exception {
    JSONObject j = new JSONObject(text);
    long code = j.getLong("versionCode"), size = j.getLong("sizeBytes");
    String version = j.getString("versionName"),
        url = j.getString("apkUrl"),
        sha = j.getString("sha256");
    URI uri = new URI(url);
    if (code <= 0
        || version.isBlank()
        || size < 100_000
        || size > 80L * 1024 * 1024
        || !url.startsWith(PREFIX)
        || !url.endsWith(".apk")
        || uri.getQuery() != null
        || uri.getFragment() != null
        || url.contains("..")
        || !sha.matches("[a-fA-F0-9]{64}"))
      throw new IllegalArgumentException("Invalid update manifest");
    return new UpdateManifest(
        code, version, url, sha.toLowerCase(java.util.Locale.ROOT), size, j.optString("notes", ""));
  }

  public boolean newerThan(long installed) {
    return versionCode > installed;
  }
}
