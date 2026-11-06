package in.otrail;

import org.json.*;

public final class RoadGeometry {
  public record Match(double distance,double along){}
  public static Match match(JSONArray shape,double lat,double lon) throws Exception {
    double best=Double.POSITIVE_INFINITY,along=0,total=0,scale=Math.cos(Math.toRadians(lat));
    for(int i=1;i<shape.length();i++) {
      JSONObject a=shape.getJSONObject(i-1),b=shape.getJSONObject(i);
      double ax=(a.getDouble("lon")-lon)*111320*scale,ay=(a.getDouble("lat")-lat)*111320;
      double bx=(b.getDouble("lon")-lon)*111320*scale,by=(b.getDouble("lat")-lat)*111320;
      double dx=bx-ax,dy=by-ay,len=Math.hypot(dx,dy),t=len==0?0:Math.max(0,Math.min(1,-(ax*dx+ay*dy)/(len*len)));
      double d=Math.hypot(ax+t*dx,ay+t*dy);if(d<best){best=d;along=total+t*len;}total+=len;
    }
    return new Match(best,along);
  }
  public static String identity(JSONObject road) {
    JSONObject tags=road.optJSONObject("tags");if(tags==null)return "way/"+road.optLong("id");
    String ref=tags.optString("ref").trim(),name=tags.optString("name").trim();
    return !ref.isEmpty()?"ref:"+ref.replaceAll("[^A-Za-z0-9;]","").toUpperCase(java.util.Locale.ROOT):!name.isEmpty()?"name:"+name.toLowerCase(java.util.Locale.ROOT):"way/"+road.optLong("id");
  }
  public static String label(JSONObject road) {
    JSONObject t=road.optJSONObject("tags");if(t==null)return "Unnamed road";
    String ref=t.optString("ref"),name=t.optString("name");
    return !ref.isBlank()?ref+(name.isBlank()?"":" · "+name):!name.isBlank()?name:"Unnamed "+t.optString("highway","road").replace('_',' ')+" road";
  }
}
