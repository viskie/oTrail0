package in.otrail;

import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;

public class OfflineRouteCodecTest {
  @Test public void brouterInstructionsAndGeometrySurviveCacheNormalization() throws Exception {
    String raw="{\"features\":[{\"geometry\":{\"coordinates\":[[77,20],[77.001,20],[77.001,20.001]]},\"properties\":{\"track-length\":222,\"total-time\":30,\"voicehints\":[[1,2,0,100,90]]}}]}";
    RoutePlan p=OsrmRouteParser.parse(OfflineRouteCodec.brouter(raw));
    assertEquals(3,p.points.size());assertEquals("Turn left",p.steps.get(1).instruction());
    assertEquals(222,p.distance,0);assertEquals(30,p.duration,0);
    assertEquals("Arrive at destination",p.steps.get(2).instruction());
  }
  @Test public void valhallaPolyline6AndManeuversSurviveNormalization() throws Exception {
    String shape=encode(20000000)+encode(77000000)+encode(1000)+encode(1000);
    JSONObject leg=new JSONObject().put("shape",shape).put("maneuvers",new JSONArray()
      .put(new JSONObject().put("begin_shape_index",0).put("instruction","Head south on Example Road"))
      .put(new JSONObject().put("begin_shape_index",1).put("instruction","Your destination is on the left")));
    String raw=new JSONObject().put("trip",new JSONObject().put("legs",new JSONArray().put(leg)).put("summary",new JSONObject().put("length",.15).put("time",20))).toString();
    RoutePlan p=OsrmRouteParser.parse(OfflineRouteCodec.valhalla(raw));
    assertEquals(2,p.points.size());assertEquals(20.001,p.points.get(1).lat(),.0000001);
    assertEquals(150,p.distance,0);assertEquals("Head south on Example Road",p.steps.get(0).instruction());
  }
  @Test public void valhallaErrorsAreActionable() throws Exception {
    try{OfflineRouteCodec.valhalla("{\"error_code\":171,\"error\":\"No suitable edges\"}");fail();}
    catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("No suitable edges"));}
  }
  @Test public void incompletePolylineRejected() throws Exception {
    try{OfflineRouteCodec.decode("_");fail();}catch(IllegalArgumentException expected){}
  }
  private static String encode(long n){long v=n<0?~(n<<1):n<<1;StringBuilder s=new StringBuilder();while(v>=32){s.append((char)((32|(v&31))+63));v>>=5;}return s.append((char)(v+63)).toString();}
}
