package in.otrail;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GpsBubbleTextTest {
  @Test public void showsExpresswayReferenceInsteadOfItsFullName() {
    assertEquals("Exp: NH709A",GpsBubble.roadLabel("NH709A · Delhi-Dehradun Expressway"));
    assertEquals("Exp:",GpsBubble.roadLabel("Delhi-Dehradun Expressway"));
  }

  @Test public void appendsHighwayReferenceAndKeepsLocality() {
    assertEquals("Saharanpur\nExp: NH709A",
        GpsBubble.location("Saharanpur\nDelhi-Dehradun Expressway",
            "NH709A · Delhi-Dehradun Expressway"));
  }

  @Test public void putsDriveDistanceAfterSpeedWithoutDuplicateSeparator() {
    assertEquals("20808 m/hr · 406.2 km",GpsBubble.status(" · 20808 m/hr","406.2 km"));
    assertEquals("20808 m/hr",GpsBubble.status(" · 20808 m/hr",""));
  }

  @Test public void matchesNhaiPlazaNamesWithOptionalSuffix() {
    assertEquals(RouteTolls.normalizeName("Aaini"),
        RouteTolls.normalizeName("Aaini (NH 17) Toll Plaza"));
  }
}
