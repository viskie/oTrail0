package in.otrail;

import static org.junit.Assert.*;

import java.util.*;
import org.json.*;
import org.junit.Test;

public class NavigationTest {
  private RoutePlan straight() {
    return new RoutePlan(
        List.of(new RoutePlan.Point(20, 77), new RoutePlan.Point(20.01, 77)),
        List.of(
            new RoutePlan.Step(0, "Head north"), new RoutePlan.Step(1112, "Arrive at destination")),
        1112,
        120);
  }

  private String fixture() throws Exception {
    return new JSONObject()
        .put("code", "Ok")
        .put(
            "routes",
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("distance", 1112)
                        .put("duration", 120)
                        .put(
                            "geometry",
                            new JSONObject()
                                .put(
                                    "coordinates",
                                    new JSONArray("[[77,20],[77,20.005],[77,20.01]]")))
                        .put(
                            "legs",
                            new JSONArray()
                                .put(
                                    new JSONObject()
                                        .put(
                                            "steps",
                                            new JSONArray()
                                                .put(
                                                    new JSONObject()
                                                        .put("name", "Road A")
                                                        .put(
                                                            "maneuver",
                                                            new JSONObject()
                                                                .put("type", "depart")
                                                                .put("bearing_after", 0)
                                                                .put(
                                                                    "location",
                                                                    new JSONArray("[77,20]"))))
                                                .put(
                                                    new JSONObject()
                                                        .put("name", "Road B")
                                                        .put(
                                                            "maneuver",
                                                            new JSONObject()
                                                                .put("type", "turn")
                                                                .put("modifier", "right")
                                                                .put(
                                                                    "location",
                                                                    new JSONArray("[77,20.005]"))))
                                                .put(
                                                    new JSONObject()
                                                        .put("name", "")
                                                        .put(
                                                            "maneuver",
                                                            new JSONObject()
                                                                .put("type", "arrive")
                                                                .put(
                                                                    "location",
                                                                    new JSONArray(
                                                                        "[77,20.01]")))))))))
        .toString();
  }

  @Test
  public void parsesGeoJsonAndTurns() throws Exception {
    RoutePlan p = OsrmRouteParser.parse(fixture());
    assertEquals(20, p.points.get(0).lat(), 0);
    assertEquals(77, p.points.get(0).lon(), 0);
    assertEquals(3, p.steps.size());
    assertEquals("Turn right onto Road B", p.steps.get(1).instruction());
    assertTrue(p.steps.get(1).along() > 500);
  }

  @Test
  public void rejectsNoRoute() throws Exception {
    try {
      OsrmRouteParser.parse("{\"code\":\"NoRoute\",\"message\":\"No road\"}");
      fail();
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("No driving route"));
    }
  }

  @Test
  public void rejectsInvalidCoordinates() {
    try {
      new RoutePlan.Point(91, 77);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      new RoutePlan.Point(Double.NaN, 77);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void projectsBetweenVertices() {
    RoutePlan p = straight();
    RoutePlan.Match m = p.match(new RoutePlan.Point(20.005, 77), 0, Double.POSITIVE_INFINITY);
    assertEquals(0, m.distance(), 1);
    assertEquals(p.length() / 2, m.along(), 2);
  }

  @Test
  public void requiresThreeAccurateOffRouteFixes() {
    NavigationProgress p = new NavigationProgress(straight());
    RoutePlan.Point outside = new RoutePlan.Point(20.005, 77.002);
    assertFalse(p.update(outside, 8, 1000).offRoute());
    assertFalse(p.update(outside, 8, 6000).offRoute());
    assertTrue(p.update(outside, 8, 11000).offRoute());
    assertFalse(p.update(new RoutePlan.Point(20.005, 77), 8, 16000).offRoute());
  }

  @Test
  public void ignoresInaccurateAndDuplicateFixes() {
    NavigationProgress p = new NavigationProgress(straight());
    assertFalse(p.update(new RoutePlan.Point(20, 77), 80, 1000).accepted());
    assertTrue(p.update(new RoutePlan.Point(20, 77), 8, 2000).accepted());
    assertFalse(p.update(new RoutePlan.Point(20.01, 77), 8, 2000).accepted());
  }

  @Test
  public void arrivalNeedsTwoGoodFixes() {
    NavigationProgress p = new NavigationProgress(straight());
    assertFalse(p.update(new RoutePlan.Point(20.0099, 77), 8, 1000).arrived());
    assertTrue(p.update(new RoutePlan.Point(20.01, 77), 8, 6000).arrived());
  }

  @Test
  public void doesNotArriveAtStartOfLoop() {
    RoutePlan.Point origin = new RoutePlan.Point(20, 77);
    RoutePlan route =
        new RoutePlan(
            List.of(
                origin,
                new RoutePlan.Point(20.005, 77),
                new RoutePlan.Point(20.005, 77.005),
                origin),
            List.of(),
            1800,
            180);
    NavigationProgress p = new NavigationProgress(route);
    assertFalse(p.update(origin, 8, 1000).arrived());
    assertFalse(p.update(origin, 8, 6000).arrived());
  }

  @Test
  public void advancesInstructionAfterDeparture() throws Exception {
    NavigationProgress p = new NavigationProgress(OsrmRouteParser.parse(fixture()));
    var g = p.update(new RoutePlan.Point(20.001, 77), 8, 1000);
    assertEquals("Turn right onto Road B", g.instruction());
    assertTrue(g.untilTurn() > 400 && g.untilTurn() < 500);
  }

  @Test
  public void roundaboutAndUTurn() throws Exception {
    assertEquals(
        "Enter roundabout · take exit 3",
        OsrmRouteParser.instruction(
            new JSONObject().put("type", "roundabout").put("exit", 3), "", ""));
    assertEquals(
        "Make a U-turn",
        OsrmRouteParser.instruction(
            new JSONObject().put("type", "turn").put("modifier", "uturn"), "", ""));
  }

  @Test
  public void rejectsInsecureRoutingEndpoint() throws Exception {
    try {
      OnlineRouting.server("http://example.com/route/");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertEquals("https://example.com/route/", OnlineRouting.server("https://example.com/route"));
  }

  @Test
  public void currentJournalCoordinatesNotMutated() {
    List<RoutePlan.Point> input =
        new ArrayList<>(List.of(new RoutePlan.Point(20, 77), new RoutePlan.Point(20.01, 77)));
    RoutePlan route = new RoutePlan(input, List.of(), 1000, 100);
    NavigationProgress progress = new NavigationProgress(route);
    progress.update(new RoutePlan.Point(20.005, 77), 8, 1000);
    assertEquals(input, route.points);
    input.clear();
    assertEquals(2, route.points.size());
  }
}
