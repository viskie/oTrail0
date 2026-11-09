package in.otrail;

import android.graphics.*;
import java.util.*;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Overlay;

/** Display-only long-stop annotations; collision emphasis uses projected city-label bounds. */
final class StopLabelsOverlay extends Overlay {
  private List<TrailDb.Stop> stops=List.of();
  private List<CityPlaces.Place> cities=List.of();
  private DriveMetrics.Result metrics;
  private boolean liveBubble;
  void liveBubble(boolean visible){liveBubble=visible;}
  void update(List<TrailDb.Stop> stops,DriveMetrics.Result metrics,List<CityPlaces.Place> cities){this.stops=stops;this.metrics=metrics;this.cities=cities;}
  static boolean longStop(TrailDb.Stop stop){return stop.observed()-stop.arrival()>=UiConfig.LONG_STOP_MINUTES*60_000L;}
  @Override public void draw(Canvas canvas,MapView map,boolean shadow){
    if(shadow||map.getZoomLevelDouble()<UiConfig.LONG_STOP_LABEL_MIN_ZOOM
        ||!org.osmdroid.views.overlay.infowindow.InfoWindow.getOpenedInfoWindowsOn(map).isEmpty())return;
    float density=map.getResources().getDisplayMetrics().density,sp=map.getResources().getDisplayMetrics().scaledDensity;
    Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);text.setTextSize(UiConfig.LONG_STOP_LABEL_SIZE_SP*sp);
    text.setTypeface(Typeface.create(UiConfig.BODY_FONT,Typeface.ITALIC));
    List<RectF> cityBounds=new ArrayList<>();Point point=new Point();
    Paint city=new Paint(text);city.setTextSize(OfflineMapTheme.citySize(25,(int)map.getZoomLevelDouble())*density);
    for(CityPlaces.Place place:cities){
      map.getProjection().toPixels(new GeoPoint(place.lat(),place.lon()),point);
      float half=city.measureText(place.name())/2+5*density;
      cityBounds.add(new RectF(point.x-half,point.y-city.getTextSize(),point.x+half,point.y+city.getTextSize()));
    }
    Paint background=new Paint(Paint.ANTI_ALIAS_FLAG);background.setColor(UiConfig.LONG_STOP_LABEL_BACKGROUND);
    Paint border=new Paint(Paint.ANTI_ALIAS_FLAG);border.setColor(UiConfig.LONG_STOP_COLOR);border.setStyle(Paint.Style.STROKE);border.setStrokeWidth(density);
    List<RectF> occupied=new ArrayList<>();
    int shown=0;
    for(int i=stops.size()-1;i>=0&&shown<UiConfig.LONG_STOP_LABEL_LIMIT;i--){
      TrailDb.Stop stop=stops.get(i);if(liveBubble&&i==stops.size()-1)continue;if(!longStop(stop))continue;
      map.getProjection().toPixels(new GeoPoint(stop.lat(),stop.lon()),point);
      if(point.x<0||point.y<0||point.x>map.getWidth()||point.y>map.getHeight())continue;
      String name=stop.locality()!=null?stop.locality():stop.placeName()!=null?stop.placeName():"Stop #"+stop.id();
      if(name.length()>26)name=name.substring(0,25)+"…";
      long minutes=(stop.observed()-stop.arrival())/60000;
      DriveMetrics.Leg leg=metrics==null?null:metrics.stops().get(stop.id());
      String detail=String.format(Locale.US,"%dh %02dm%s",minutes/60,minutes%60,leg==null?"":String.format(Locale.US," · %.2f km",leg.meters()/1000));
      text.setTypeface(Typeface.create(UiConfig.BODY_FONT,Typeface.ITALIC));
      float width=Math.max(text.measureText(name),text.measureText(detail))+14*density,height=text.getTextSize()*2.5f+8*density;
      float left=Math.max(2*density,Math.min(map.getWidth()-width-2*density,point.x-width/2));
      float bottom=point.y-15*density;
      RectF bounds=new RectF(left,bottom-height,left+width,bottom);
      boolean overlap=false;
      for(RectF cityBox:cityBounds)if(RectF.intersects(bounds,cityBox)){
        overlap=true;bounds.offset(0,Math.min(0,cityBox.top-5*density-bounds.bottom));break;
      }
      if(bounds.top<0)bounds.offset(0,-bounds.top+2*density);
      if(overlap)text.setTypeface(Typeface.create(UiConfig.BODY_FONT,Typeface.BOLD_ITALIC));
      // Automatic annotations must never pile up like open popup windows.
      boolean crowded=false;
      RectF padded=new RectF(bounds);padded.inset(-6*density,-6*density);
      for(RectF used:occupied)if(RectF.intersects(padded,used)){crowded=true;break;}
      if(crowded)continue;
      occupied.add(padded);
      canvas.drawRoundRect(bounds,6*density,6*density,background);canvas.drawRoundRect(bounds,6*density,6*density,border);
      text.setColor(UiConfig.LONG_STOP_COLOR);
      canvas.drawText(name,bounds.left+7*density,bounds.top+text.getTextSize()+3*density,text);
      canvas.drawText(detail,bounds.left+7*density,bounds.top+text.getTextSize()*2.25f+3*density,text);shown++;
    }
  }
}
