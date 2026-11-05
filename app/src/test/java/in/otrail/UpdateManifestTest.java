package in.otrail;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class UpdateManifestTest {
  private JSONObject valid() throws Exception {
    return new JSONObject()
        .put("versionCode", 7)
        .put("versionName", "0.2.5")
        .put("apkUrl", UpdateManifest.PREFIX + "oTraiL-0.2.5-debug.apk")
        .put("sha256", "a".repeat(64))
        .put("sizeBytes", 4_500_000);
  }

  @Test
  public void comparesVersionCodesNotStrings() throws Exception {
    UpdateManifest m = UpdateManifest.parse(valid().toString());
    assertTrue(m.newerThan(6));
    assertFalse(m.newerThan(7));
    assertFalse(m.newerThan(8));
  }

  @Test
  public void rejectsForeignArtifacts() throws Exception {
    for (String url :
        new String[] {
          "http://raw.githubusercontent.com/viskie/oTrail0/main/releases/a.apk",
          "https://evil.example/a.apk",
          UpdateManifest.PREFIX + "../a.apk",
          UpdateManifest.PREFIX + "a.apk?token=x"
        })
      try {
        UpdateManifest.parse(valid().put("apkUrl", url).toString());
        fail(url);
      } catch (IllegalArgumentException expected) {
      }
  }

  @Test
  public void rejectsBadDigestAndOversizedDownload() throws Exception {
    try {
      UpdateManifest.parse(valid().put("sha256", "not-a-checksum").toString());
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      UpdateManifest.parse(valid().put("sizeBytes", 100_000_000).toString());
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }
}
