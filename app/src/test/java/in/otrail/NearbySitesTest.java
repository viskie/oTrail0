package in.otrail;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class NearbySitesTest {
 private JSONObject site(String amenity,String religion,String name,double lat)throws Exception{return new JSONObject().put("lat",lat).put("lon",78).put("tags",new JSONObject().put("amenity",amenity).put("religion",religion).put("name",name));}
 @Test public void nearestPoliceIsNamedAndNavigable()throws Exception{var data=new JSONObject().put("elements",new JSONArray().put(site("police","","Station A",29.001)).put(site("police","","Station B",29.01)));var sites=NearbySites.parse(data,29,78);assertEquals(1,sites.size());assertEquals("Station A",sites.get(0).name());assertEquals(29.001,sites.get(0).lat(),.00001);assertTrue(sites.get(0).line().contains("111 m"));}
 @Test public void worshipNamesAreRetained()throws Exception{var data=new JSONObject().put("elements",new JSONArray().put(site("place_of_worship","hindu","Shiv Mandir",29.001)));assertTrue(NearbySites.summary(data,29,78,true).contains("Shiv Mandir"));}
 @Test public void oldCacheDoesNotClaimPoliceAbsent()throws Exception{assertTrue(NearbySites.summary(new JSONObject(),29,78,false).contains("Police station: checking updated dataset"));}
 @Test public void missingNamesAreNotInvented()throws Exception{var data=new JSONObject().put("elements",new JSONArray().put(site("police","","",29.001)));assertEquals("Name not mapped",NearbySites.parse(data,29,78).get(0).name());}
 @Test public void distantPoliceCannotReplaceSearchCoverage()throws Exception{var data=new JSONObject().put("elements",new JSONArray().put(site("police","","Far away",30)));assertTrue(NearbySites.parse(data,29,78).isEmpty());}
}
