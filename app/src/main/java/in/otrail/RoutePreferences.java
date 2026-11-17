package in.otrail;

import java.util.*;
import org.json.*;

/** Soft road preferences: connected legal roads remain usable when preferred roads are absent. */
public final class RoutePreferences {
  public static final String[] LABELS={"Automatic · fastest", "EX → NH → SH → other roads", "Other roads → SH → NH → EX", "Avoid expressways", "Avoid NH & SH", "Avoid EX, NH & SH", "Prefer NH only", "Prefer SH only"};
  public static final String[] KEYS={"auto","major","minor","no_ex","no_nh_sh","local","nh","sh"};
  private RoutePreferences() {}
  public static String valid(String key){return Arrays.asList(KEYS).contains(key)?key:"auto";}
  // Road classes are ordered EX, NH, SH, other. Penalties are finite to allow necessary connectors.
  static double[] weights(String key){return switch(valid(key)) {
    case "major" -> new double[]{1,1.4,2.2,4};
    case "minor" -> new double[]{5,3,1.5,1};
    case "no_ex" -> new double[]{12,1,1,1};
    case "no_nh_sh" -> new double[]{1,10,10,1};
    case "local" -> new double[]{12,10,10,1};
    case "nh" -> new double[]{5,1,5,5};
    case "sh" -> new double[]{5,5,1,5};
    default -> new double[]{1,1,1,1};
  };}
  static int roadClass(JSONObject step){
    String ref=step.optString("ref","").toUpperCase(Locale.ROOT);
    String name=step.optString("name","").toUpperCase(Locale.ROOT);
    if(ref.matches(".*\\b(?:NE|EX|E)\\s*[- ]?\\d+.*")||name.contains("EXPRESSWAY"))return 0;
    if(ref.matches(".*\\bNH\\s*[- ]?\\d+.*"))return 1;
    if(ref.matches(".*\\bSH\\s*[- ]?\\d+.*"))return 2;
    JSONArray intersections=step.optJSONArray("intersections");
    if(intersections!=null)for(int i=0;i<intersections.length();i++) {
      JSONArray classes=intersections.optJSONObject(i)==null?null:intersections.optJSONObject(i).optJSONArray("classes");
      if(classes!=null)for(int j=0;j<classes.length();j++)if("motorway".equals(classes.optString(j)))return 0;
    }
    return 3;
  }
  static double score(JSONObject route,String preference)throws JSONException {
    if(valid(preference).equals("auto"))return route.optDouble("duration",Double.MAX_VALUE);
    double result=0;double[] weights=weights(preference);JSONArray legs=route.getJSONArray("legs");
    for(int i=0;i<legs.length();i++) {JSONArray steps=legs.getJSONObject(i).getJSONArray("steps");
      for(int j=0;j<steps.length();j++){JSONObject step=steps.getJSONObject(j);result+=Math.max(0,step.optDouble("distance",0))*weights[roadClass(step)];}}
    return result+route.optDouble("duration",0)*.01;
  }
  static String select(String response,String preference)throws JSONException {
    JSONObject root=new JSONObject(response);JSONArray routes=root.optJSONArray("routes");
    if(routes==null||routes.length()==0)return response;
    int best=0;double score=Double.MAX_VALUE;
    for(int i=0;i<routes.length();i++){double s=score(routes.getJSONObject(i),preference);if(s<score){best=i;score=s;}}
    root.put("routes",new JSONArray().put(routes.getJSONObject(best)));return root.toString();
  }
  static String brouterProfile(String original,String preference){
    if(valid(preference).equals("auto"))return original;
    double[] w=weights(preference);
    // BRouter's encoded highway hierarchy is a proxy, not an exact NH/SH designation.
    String penalty=String.format(Locale.ROOT,"assign costfactor = add costfactor switch highway=motorway|motorway_link %.2f switch highway=trunk|trunk_link|primary|primary_link %.2f switch highway=secondary|secondary_link %.2f %.2f\n",(w[0]-1)*2,(w[1]-1)*2,(w[2]-1)*2,(w[3]-1)*2);
    return original.replace("assign minspeed =",penalty+"\nassign minspeed =");
  }
  static double highwayUse(String preference){return switch(valid(preference)) {
    case "minor","no_ex","local","sh" -> 0;
    case "major","nh","no_nh_sh" -> 1;
    default -> .5;
  };}
}
