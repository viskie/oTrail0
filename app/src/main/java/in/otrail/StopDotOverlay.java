package in.otrail;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import java.util.*;
import java.util.function.*;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.*;

/** Runs before markers so zoom/pan adjusts both drawing and hit testing immediately. */
final class StopDotOverlay extends Overlay {
  private record Entry(long id,Marker marker,boolean longStop){}
  private final List<Entry> entries=new ArrayList<>();private final Supplier<Marker> live;
  private final BiFunction<Boolean,Integer,Drawable> icon;
  private boolean dirty=true;private double zoom=Double.NaN,lat,lon,rotation;private int width,height,size=-1;
  StopDotOverlay(Supplier<Marker> live,BiFunction<Boolean,Integer,Drawable> icon){this.live=live;this.icon=icon;}
  void reset(){entries.clear();dirty=true;size=-1;}
  void add(long id,Marker marker,boolean longStop){entries.add(new Entry(id,marker,longStop));dirty=true;}
  @Override public void draw(Canvas canvas,MapView map,boolean shadow){
    if(shadow)return;double z=map.getZoomLevelDouble(),a=map.getMapCenter().getLatitude(),b=map.getMapCenter().getLongitude(),r=map.getMapOrientation();
    if(!dirty&&z==zoom&&a==lat&&b==lon&&r==rotation&&width==map.getWidth()&&height==map.getHeight())return;
    dirty=false;zoom=z;lat=a;lon=b;rotation=r;width=map.getWidth();height=map.getHeight();
    float density=map.getResources().getDisplayMetrics().density;double gap=StopDotLayout.spacing(z)*density;
    Point p=new Point();List<StopDotLayout.Point> candidates=new ArrayList<>();
    for(Entry e:entries){map.getProjection().toPixels(e.marker().getPosition(),p);map.getProjection().rotateAndScalePoint(p.x,p.y,p);if(p.x>=0&&p.y>=0&&p.x<width&&p.y<height)candidates.add(new StopDotLayout.Point(e.id(),p.x,p.y,e.longStop()));}
    double liveX=Double.NaN,liveY=Double.NaN;Marker current=live.get();if(current!=null){map.getProjection().toPixels(current.getPosition(),p);map.getProjection().rotateAndScalePoint(p.x,p.y,p);liveX=p.x;liveY=p.y;}
    Set<Long> shown=StopDotLayout.select(candidates,gap,liveX,liveY);int newSize=StopDotLayout.diameter(z,false);
    for(Entry e:entries){e.marker().setEnabled(shown.contains(e.id()));if(size!=newSize)e.marker().setIcon(icon.apply(e.longStop(),StopDotLayout.diameter(z,e.longStop())));}
    size=newSize;
  }
}
