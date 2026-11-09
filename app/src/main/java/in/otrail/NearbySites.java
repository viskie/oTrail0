package in.otrail;
import org.json.*;
import java.util.*;

/** Six closest mapped destinations, in any direction; no road-distance claims. */
public final class NearbySites {
  public record Site(String kind,String name,double lat,double lon,double meters){
    public String line(){return kind+": "+name+" — "+(meters<1000?String.format(Locale.US,"%.0f m",meters):String.format(Locale.US,"%.1f km",meters/1000));}
  }
  public static final String[] KINDS={"Temple","Mosque","Church","Gurdwara","Buddhist","Police station"};
  public static List<Site> parse(JSONObject payload,double lat,double lon)throws Exception{
    Site[] nearest=new Site[6];JSONArray elements=payload.optJSONArray("elements");if(elements!=null)for(int i=0;i<elements.length();i++){
      JSONObject e=elements.getJSONObject(i),tags=e.optJSONObject("tags"),point=e.has("lat")?e:e.optJSONObject("center");if(tags==null||point==null)continue;int kind=-1;
      if(tags.optString("amenity").equals("police"))kind=5;else if(tags.optString("amenity").equals("place_of_worship"))for(int r=0;r<5;r++)if(WorshipNearby.RELIGIONS[r].equals(tags.optString("religion")))kind=r;
      if(kind<0)continue;double pLat=point.getDouble("lat"),pLon=point.getDouble("lon"),distance=PhotoIndex.distance(lat,lon,pLat,pLon);if(!Double.isFinite(pLat)||!Double.isFinite(pLon)||Math.abs(pLat)>90||Math.abs(pLon)>180||distance>UiConfig.LIVE_WORSHIP_RADIUS_M)continue;
      String name=tags.optString("name",tags.optString("name:en","")).trim();if(name.isEmpty())name="Name not mapped";
      if(nearest[kind]==null||distance<nearest[kind].meters())nearest[kind]=new Site(KINDS[kind],name,pLat,pLon,distance);
    }
    List<Site> out=new ArrayList<>();for(Site site:nearest)if(site!=null)out.add(site);return List.copyOf(out);
  }
  public static String summary(JSONObject payload,double lat,double lon,boolean policeSearched)throws Exception{
    List<Site> sites=parse(payload,lat,lon);List<String> lines=new ArrayList<>();for(String kind:KINDS){Site found=null;for(Site site:sites)if(site.kind().equals(kind))found=site;lines.add(found==null?kind+": "+(kind.equals("Police station")&&!policeSearched?"checking updated dataset":"not mapped within 25 km"):found.line());}return String.join("\n",lines);
  }
}
