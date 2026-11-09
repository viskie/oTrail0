package in.otrail;

import java.util.*;
import org.json.*;

/** Persisted document references only; selected audio is never copied into the journal. */
public final class ResonationsPlaylist {
  public record Track(String uri,String name) {}
  public static List<Track> decode(String json){
    List<Track> tracks=new ArrayList<>();Set<String> seen=new HashSet<>();
    try{JSONArray list=new JSONArray(json);for(int i=0;i<list.length();i++){
      JSONObject item=list.optJSONObject(i);if(item==null)continue;
      String uri=item.optString("uri"),name=item.optString("name","Audio");
      if(uri.startsWith("content://")&&seen.add(uri))tracks.add(new Track(uri,name));
    }}catch(JSONException ignored){}return List.copyOf(tracks);
  }
  public static String encode(List<Track> tracks){JSONArray list=new JSONArray();for(Track track:tracks)try{list.put(new JSONObject().put("uri",track.uri()).put("name",track.name()));}catch(JSONException ignored){}return list.toString();}
  public static List<String> visibleNames(List<Track> tracks){List<String> lines=new ArrayList<>();for(int i=0;i<Math.min(3,tracks.size());i++)lines.add(tracks.get(i).name());if(tracks.size()>3)lines.add("…");return List.copyOf(lines);}
  public static int next(int index,int size){return size==0?0:Math.floorMod(index+1,size);}
}
