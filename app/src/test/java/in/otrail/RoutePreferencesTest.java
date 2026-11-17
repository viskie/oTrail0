package in.otrail;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
public class RoutePreferencesTest {
 private JSONObject route(String ref,double distance)throws Exception {
  JSONObject step=new JSONObject().put("ref",ref).put("distance",distance);
  return new JSONObject().put("duration",distance/15).put("legs",new JSONArray().put(new JSONObject().put("steps",new JSONArray().put(step))));
 }
 @Test public void prefersNationalOrStateReferencesWhenAlternativesExist()throws Exception {
  JSONObject nh=route("NH-44",1200),sh=route("SH 273",1000);
  String raw=new JSONObject().put("code","Ok").put("routes",new JSONArray().put(nh).put(sh)).toString();
  assertEquals(1200,new JSONObject(RoutePreferences.select(raw,"nh")).getJSONArray("routes").getJSONObject(0).getDouble("duration")*15,.001);
  assertEquals(1000,new JSONObject(RoutePreferences.select(raw,"sh")).getJSONArray("routes").getJSONObject(0).getDouble("duration")*15,.001);
 }
 @Test public void missingPreferredRoadsDoNotRejectOnlyConnectedRoute()throws Exception {
  String raw=new JSONObject().put("code","Ok").put("routes",new JSONArray().put(route("",1000))).toString();
  for(String key:RoutePreferences.KEYS)assertEquals(1,new JSONObject(RoutePreferences.select(raw,key)).getJSONArray("routes").length());
 }
 @Test public void referencesAndExpresswaysClassifiedWithoutFalseMatches()throws Exception {
  assertEquals(1,RoutePreferences.roadClass(new JSONObject().put("ref","NH 09")));
  assertEquals(2,RoutePreferences.roadClass(new JSONObject().put("ref","SH273")));
  assertEquals(0,RoutePreferences.roadClass(new JSONObject().put("name","Delhi Mumbai Expressway")));
  assertEquals(3,RoutePreferences.roadClass(new JSONObject().put("name","Ash Road")));
 }
 @Test public void offlineSoftCostsRetainAccessRulesAndAutomaticProfile() {
  String profile="access rules\nassign minspeed =\n";
  assertEquals(profile,RoutePreferences.brouterProfile(profile,"auto"));
  String changed=RoutePreferences.brouterProfile(profile,"sh");
  assertTrue(changed.startsWith("access rules"));assertTrue(changed.contains("assign costfactor = add costfactor"));
  for(String key:RoutePreferences.KEYS)for(double w:RoutePreferences.weights(key))assertTrue(w>=1&&Double.isFinite(w));
 }
 @Test public void onlineWaypointsPreserveTravelOrderAndRequestAlternatives()throws Exception {
  String url=OnlineRouting.routeUrl("https://example.com/route/",java.util.List.of(new RoutePlan.Point(20,77),new RoutePlan.Point(17,78),new RoutePlan.Point(21,79)),"nh");
  assertTrue(url.contains("77.000000,20.000000;78.000000,17.000000;79.000000,21.000000"));assertTrue(url.endsWith("alternatives=true"));
 }
 @Test public void unknownPreferencesRemainCompatible(){assertEquals("auto",RoutePreferences.valid("future-profile"));}
}
