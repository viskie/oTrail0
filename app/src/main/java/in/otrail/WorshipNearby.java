package in.otrail;
import org.json.*;
import java.util.*;

/** Nearest mapped sites in any direction, measured as straight-line distances. */
public final class WorshipNearby {
  public static final String[] RELIGIONS={"hindu","muslim","christian","sikh","buddhist"};
  public static final String[] LABELS={"Temple","Mosque","Church","Gurdwara","Buddhist"};
  public static String summary(JSONObject payload,double lat,double lon)throws Exception {
    double[] nearest=new double[5];Arrays.fill(nearest,Double.POSITIVE_INFINITY);
    JSONArray elements=payload.optJSONArray("elements");if(elements!=null)for(int i=0;i<elements.length();i++){
      JSONObject e=elements.getJSONObject(i),tags=e.optJSONObject("tags"),point=e.has("lat")?e:e.optJSONObject("center");if(tags==null||point==null)continue;
      if(!"place_of_worship".equals(tags.optString("amenity")))continue;
      double distance=PhotoIndex.distance(lat,lon,point.getDouble("lat"),point.getDouble("lon"));
      for(int r=0;r<5;r++)if(RELIGIONS[r].equals(tags.optString("religion"))&&distance<=UiConfig.LIVE_WORSHIP_RADIUS_M)nearest[r]=Math.min(nearest[r],distance);
    }
    StringBuilder result=new StringBuilder();for(int i=0;i<5;i++){if(i>0)result.append(i==3?"\n":" · ");result.append(LABELS[i]).append(": ");if(Double.isFinite(nearest[i]))result.append(nearest[i]<1000?String.format(Locale.US,"%.0f m",nearest[i]):String.format(Locale.US,"%.1f km",nearest[i]/1000));else result.append("not mapped ≤25 km");}return result.toString();
  }
}
