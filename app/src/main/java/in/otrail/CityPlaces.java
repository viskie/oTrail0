package in.otrail;

import java.io.File;
import java.util.*;
import org.mapsforge.core.model.*;
import org.mapsforge.map.datastore.*;
import org.mapsforge.map.reader.MapFile;
import org.osmdroid.util.BoundingBox;

/** Read existing on-phone map POIs only; no network requests or extra map downloads. */
final class CityPlaces {
  record Place(String name,double lat,double lon){}
  private String key="";
  private List<Place> cached=List.of();
  synchronized List<Place> read(File file,BoundingBox bounds,int zoom){
    if(!file.isFile()||zoom<6||bounds==null)return List.of();
    int z=Math.min(14,zoom),n=1<<z;
    int left=x(bounds.getLonWest(),n),right=x(bounds.getLonEast(),n),top=y(bounds.getLatNorth(),n),bottom=y(bounds.getLatSouth(),n);
    if(right<left||bottom<top||(long)(right-left+1)*(bottom-top+1)>48)return List.of();
    String next=file.lastModified()+":"+z+":"+left+":"+right+":"+top+":"+bottom;
    if(next.equals(key))return cached;
    MapFile map=null;
    try{
      map=new MapFile(file,"en");
      MapReadResult data=map.readPoiData(new Tile(left,top,(byte)z,256),new Tile(right,bottom,(byte)z,256));
      List<Place> result=places(data);key=next;cached=result;return result;
    }catch(RuntimeException error){return List.of();}finally{if(map!=null)map.close();}
  }
  static List<Place> places(MapReadResult data){
    if(data==null)return List.of();
    Map<String,Place> unique=new LinkedHashMap<>();
    for(PointOfInterest poi:data.pointOfInterests){
      String kind="",name="",english="";
      for(Tag tag:poi.tags){if(tag.key.equals("place"))kind=tag.value;if(tag.key.equals("name"))name=tag.value;if(tag.key.equals("name:en"))english=tag.value;}
      if(!kind.equals("city")&&!kind.equals("town"))continue;
      if(!english.isBlank())name=english;if(name.isBlank())continue;
      Place place=new Place(name,poi.position.latitude,poi.position.longitude);unique.put(name+":"+place.lat()+":"+place.lon(),place);
    }
    return List.copyOf(unique.values());
  }
  private static int x(double lon,int n){return Math.max(0,Math.min(n-1,(int)Math.floor((lon+180)/360*n)));}
  private static int y(double lat,int n){double r=Math.toRadians(Math.max(-85.05,Math.min(85.05,lat)));return Math.max(0,Math.min(n-1,(int)Math.floor((1-Math.log(Math.tan(r)+1/Math.cos(r))/Math.PI)/2*n)));}
}
