package in.otrail;

import static org.junit.Assert.*;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class RouteTollsTest {
  private RoutePlan route() {
    return new RoutePlan(
        List.of(new RoutePlan.Point(20,77),new RoutePlan.Point(20,77.02)),
        List.of(),3000,120);
  }

  @Test public void parserKeepsOnlyMappedTollsCloseToRouteAndOrdersThem() throws Exception {
    JSONObject node=new JSONObject().put("type","node").put("id",1)
        .put("lat",20.0004).put("lon",77.005)
        .put("tags",new JSONObject().put("barrier","toll_booth").put("name","East Gate"));
    JSONObject gantry=new JSONObject().put("type","way").put("id",2)
        .put("center",new JSONObject().put("lat",20).put("lon",77.019))
        .put("tags",new JSONObject().put("highway","toll_gantry"));
    JSONObject distant=new JSONObject().put("type","node").put("id",3)
        .put("lat",20.01).put("lon",77.01)
        .put("tags",new JSONObject().put("barrier","toll_booth"));
    List<RouteTolls.Toll> tolls=RouteTolls.parse(
        new JSONObject().put("elements",new JSONArray().put(gantry).put(distant).put(node)),route());
    assertEquals(2,tolls.size());
    assertEquals("East Gate",tolls.get(0).name());
    assertEquals("way/2",tolls.get(1).key());
  }

  @Test public void queryUsesNarrowRouteCorridorAndSearchesBothTollTags() {
    String query=RouteTolls.query(route());
    assertTrue(query.contains("[barrier~\"^(toll_booth|toll_gantry)$\"]"));
    assertTrue(query.contains("[highway=toll_gantry]"));
    assertTrue(query.contains("around:350,poly:\""));
    assertTrue(query.contains("poly:\"20.000000 77.000000"));
    assertTrue(query.contains("[out:json][timeout:25]"));
  }
}
