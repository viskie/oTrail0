package in.otrail;
import android.graphics.*;
import java.util.*;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Overlay;

/** Screen-space ribbons with a single shared path for exact round casing and coloured fill. */
final class TrailRibbonOverlay extends Overlay {
 record Ribbon(List<GeoPoint> points,TrailPalette.Stroke source,int color,boolean ongoing){}
 private final List<Ribbon> ribbons;
 private final TrailLanes lanes;
 private final List<int[]> laneIndices=new ArrayList<>();
 private final List<Path> cachedPaths=new ArrayList<>();
 private double zoom=Double.NaN,latitude,longitude;private int width,height;private float rotation;
 private final Paint casing=new Paint(Paint.ANTI_ALIAS_FLAG),fill=new Paint(Paint.ANTI_ALIAS_FLAG);
 TrailRibbonOverlay(List<Ribbon> ribbons,List<TrailPalette.Stroke> strokes){
  this.ribbons=new ArrayList<>(ribbons);this.ribbons.sort(Comparator.comparingLong(r->r.source()==null?Long.MIN_VALUE:r.source().points().get(r.source().points().size()-1).time()));
  lanes=new TrailLanes(strokes);
  for(Ribbon r:this.ribbons){int[] indices=new int[r.points().size()];var source=r.source();
   if(source!=null&&!r.ongoing()&&source.pass()>0&&source.points().size()==indices.length)for(int i=0;i<indices.length;i++)indices[i]=lanes.lane(source.points().get(i),source.points().get(Math.max(0,i-1)),source.points().get(Math.min(indices.length-1,i+1)),source.pass());
   laneIndices.add(indices);
  }
  for(Paint paint:new Paint[]{casing,fill}){paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
 }
 @Override public void draw(Canvas canvas,MapView map,boolean shadow){
  if(shadow)return;float density=map.getResources().getDisplayMetrics().density;
  fill.setStrokeWidth(UiConfig.TRAIL_WIDTH_DP*density);casing.setStrokeWidth((UiConfig.TRAIL_WIDTH_DP+2*UiConfig.TRAIL_BORDER_DP)*density);
  var center=map.getMapCenter();
  boolean rebuild=zoom!=map.getZoomLevelDouble()||latitude!=center.getLatitude()||longitude!=center.getLongitude()||width!=map.getWidth()||height!=map.getHeight()||rotation!=map.getMapOrientation();
  if(rebuild){zoom=map.getZoomLevelDouble();latitude=center.getLatitude();longitude=center.getLongitude();width=map.getWidth();height=map.getHeight();rotation=map.getMapOrientation();cachedPaths.clear();}
  Point current=new Point(),before=new Point(),after=new Point();
  for(int r=0;r<ribbons.size();r++){Ribbon ribbon=ribbons.get(r);var points=ribbon.points();if(points.size()<2)continue;Path path=rebuild?new Path():cachedPaths.get(r);
   if(rebuild){
   for(int i=0;i<points.size();i++) {
    GeoPoint p=points.get(i),a=points.get(Math.max(0,i-1)),b=points.get(Math.min(points.size()-1,i+1));
    map.getProjection().toPixels(p,current);map.getProjection().toPixels(a,before);map.getProjection().toPixels(b,after);
    float x=current.x,y=current.y,dx=after.x-before.x,dy=after.y-before.y;double length=Math.hypot(dx,dy);
    TrailPalette.Stroke source=ribbon.source();
    if(source!=null&&source.pass()>0&&source.points().size()==points.size()&&length>0){
     int lane=laneIndices.get(r)[i];
     // Canonical geographic direction keeps opposing traversals on the same physical side.
     boolean reverse=b.getLongitude()<a.getLongitude()||b.getLongitude()==a.getLongitude()&&b.getLatitude()<a.getLatitude();
     float offset=lane*UiConfig.TRAIL_LANE_SPACING_DP*density*(reverse?-1:1);
     x+=-dy/length*offset;y+=dx/length*offset;
    }
    if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
   }
   cachedPaths.add(path);}
   casing.setColor(ribbon.ongoing()?UiConfig.LIVE_TRAIL_OUTLINE:UiConfig.TRAIL_OUTLINE);fill.setColor(ribbon.color());
   canvas.drawPath(path,casing);canvas.drawPath(path,fill);
  }
 }
}
