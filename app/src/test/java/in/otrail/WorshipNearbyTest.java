package in.otrail;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class WorshipNearbyTest {
 private JSONObject site(String religion,double lat,double lon)throws Exception{return new JSONObject().put("lat",lat).put("lon",lon).put("tags",new JSONObject().put("amenity","place_of_worship").put("religion",religion));}
 @Test public void closestFromAnyDirection()throws Exception{JSONObject data=new JSONObject().put("elements",new JSONArray().put(site("hindu",29.001,78)).put(site("hindu",28.9995,78)).put(site("muslim",29,78.001)));String result=WorshipNearby.summary(data,29,78);assertTrue(result.contains("Temple: 56 m"));assertTrue(result.contains("Mosque: 97 m"));}
 @Test public void incompleteCoverageIsNotZeroDistance()throws Exception{String result=WorshipNearby.summary(new JSONObject().put("elements",new JSONArray()),29,78);assertTrue(result.contains("Church: not mapped ≤25 km"));assertFalse(result.contains(": 0 m"));}
 @Test public void farOutsideSearchCannotBePresentedAsNearest()throws Exception{String result=WorshipNearby.summary(new JSONObject().put("elements",new JSONArray().put(site("hindu",30,78))),29,78);assertTrue(result.contains("Temple: not mapped"));}
 @Test public void waysUseTheirCenter()throws Exception{JSONObject way=site("sikh",29.001,78);way.remove("lat");way.remove("lon");way.put("center",new JSONObject().put("lat",29.001).put("lon",78));assertTrue(WorshipNearby.summary(new JSONObject().put("elements",new JSONArray().put(way)),29,78).contains("Gurdwara: 111 m"));}
}
