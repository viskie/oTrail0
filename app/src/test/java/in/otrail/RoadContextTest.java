package in.otrail;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
public class RoadContextTest {
  private JSONObject road(long id,double lat,String ref)throws Exception{return new JSONObject().put("type","way").put("id",id).put("tags",new JSONObject().put("highway","primary").put("ref",ref)).put("geometry",new JSONArray().put(new JSONObject().put("lat",lat).put("lon",77)).put(new JSONObject().put("lat",lat).put("lon",77.02)));}
  private JSONObject poi(long id,double lat)throws Exception{return new JSONObject().put("type","node").put("id",id).put("lat",lat).put("lon",77.012).put("tags",new JSONObject().put("name","Test Temple").put("amenity","place_of_worship").put("religion","hindu"));}
  @Test public void templeOnCurrentRoadIncludedAndParallelRoadExcluded()throws Exception{JSONObject data=new JSONObject().put("elements",new JSONArray().put(road(1,20,"NH-09")).put(road(2,20.003,"SH273")).put(poi(1,20.0002)).put(poi(2,20.003)));var result=RoadContext.parse(data,20,77.01,90,1000,true);assertEquals("NH-09",result.road());assertEquals(1,result.landmarks().size());assertEquals("Temple",result.landmarks().get(0).kind());}
  @Test public void noRoadIsNotInvented()throws Exception{var r=RoadContext.parse(new JSONObject().put("elements",new JSONArray().put(road(1,21,"NH-09"))),20,77.01,Double.NaN,1000,true);assertTrue(r.road().isEmpty());}
  @Test public void roadIdentityNormalizesReferencePunctuation()throws Exception{assertEquals(RoadGeometry.identity(road(1,20,"NH-09")),RoadGeometry.identity(road(2,20,"NH 09")));}
}
