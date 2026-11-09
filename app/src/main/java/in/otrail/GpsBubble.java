package in.otrail;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;

/** One persistent screen-space bubble anchored above the live dot, including during map rotation. */
final class GpsBubble extends LinearLayout {
  private final MapView map;private GeoPoint point;private final TextView state,coordinates,address,worship;
  private final android.view.ViewTreeObserver observer;private final android.view.ViewTreeObserver.OnPreDrawListener positioner;
  GpsBubble(Context c,MapView map){super(c);this.map=map;setOrientation(VERTICAL);setPadding(dp(9),dp(5),dp(9),dp(5));setElevation(dp(5));
    GradientDrawable background=new GradientDrawable();background.setColor(UiConfig.LIVE_BUBBLE_BACKGROUND);background.setCornerRadius(dp(9));background.setStroke(dp(1),UiConfig.LIVE_BUBBLE_TEXT);setBackground(background);
    state=text(UiConfig.STATUS_SIZE_SP);state.setTypeface(Typeface.create(UiConfig.STATUS_FONT,UiConfig.STATUS_FONT_STYLE));addView(state);
    View line=new View(c);line.setBackgroundColor(UiConfig.LIVE_BUBBLE_TEXT);LayoutParams separator=new LayoutParams(-1,dp(1));separator.setMargins(0,dp(4),0,dp(4));addView(line,separator);
    coordinates=text(12);address=text(12);worship=text(11);addView(coordinates);addView(address);addView(worship);setVisibility(GONE);
    observer=map.getViewTreeObserver();positioner=()->{position();return true;};observer.addOnPreDrawListener(positioner);
  }
  private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
  private TextView text(int sp){TextView v=new TextView(getContext());v.setTextColor(UiConfig.LIVE_BUBBLE_TEXT);v.setTextSize(sp);v.setTypeface(Typeface.create(UiConfig.LIVE_BUBBLE_FONT,Typeface.NORMAL));v.setIncludeFontPadding(false);return v;}
  void update(GeoPoint point,CharSequence status,String locality,String temples){this.point=point;state.setText(status);coordinates.setText(String.format(java.util.Locale.US,"Lat %.4f  ·  Lon %.4f",point.getLatitude(),point.getLongitude()));address.setText(locality);worship.setText(temples);setVisibility(VISIBLE);position();}
  private void position(){if(point==null||getVisibility()!=VISIBLE||map.getWidth()==0)return;int width=Math.min(dp(UiConfig.LIVE_BUBBLE_WIDTH_DP),map.getWidth()-dp(16));if(width<=0)return;android.view.ViewGroup.LayoutParams p=getLayoutParams();if(p!=null&&p.width!=width){p.width=width;setLayoutParams(p);}
    Point screen=map.getProjection().toPixels(point,null);map.getProjection().rotateAndScalePoint(screen.x,screen.y,screen);
    if(screen.x<0||screen.y<0||screen.x>map.getWidth()||screen.y>map.getHeight()){setAlpha(0);return;}setAlpha(1);
    float x=Math.max(dp(8),Math.min(map.getWidth()-width-dp(8),screen.x-width/2f)),y=Math.max(dp(7),screen.y-getHeight()-dp(16));if(Math.abs(getX()-x)>.5f)setX(x);if(Math.abs(getY()-y)>.5f)setY(y);
  }
  void close(){if(observer.isAlive())observer.removeOnPreDrawListener(positioner);}
}
