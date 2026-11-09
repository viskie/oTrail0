package in.otrail;
import java.util.*;

/** Keeps actionable values in the map bubble; lookup diagnostics belong in the side panel. */
public final class LivePopupText {
  public record Parts(String values,String notes){}
  public static Parts worship(String raw){
    List<String> values=new ArrayList<>(),missing=new ArrayList<>(),notes=new ArrayList<>();
    for(String line:raw.split("\\n")){
      if(line.startsWith("Straight-line")){notes.add(line);continue;}
      for(String item:line.split(" · ")){boolean site=false;for(String label:WorshipNearby.LABELS)if(item.startsWith(label+":")){site=true;if(item.contains("not mapped"))missing.add(label);else values.add(item);break;}if(!site&&!item.isBlank())notes.add(item);}
    }
    if(!missing.isEmpty())notes.add("Not mapped within 25 km: "+String.join(", ",missing));
    return new Parts(String.join(" · ",values),String.join("\n",notes));
  }
  public static boolean addressPending(String address){return address.startsWith("Locality /")||address.startsWith("Road data not cached");}
}
