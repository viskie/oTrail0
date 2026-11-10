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
    private final int markerClearance;private final MapView map;private GeoPoint point;private final TextView state,coordinates,address,worship;
    private final android.view.ViewTreeObserver observer;private final android.view.ViewTreeObserver.OnPreDrawListener positioner;
    GpsBubble(Context c,MapView map){
      super(c);
      this.map=map;
      markerClearance=GpsMarkerStyle.selected(c).sizeDp()/2;
      setOrientation(VERTICAL);
      setPadding(dp(7),dp(7),dp(7),dp(7));setElevation(dp(2));
      setBackground(NeonBorderDrawable.panel(this,NeonBorderDrawable.Palette.BRIGHT_ORANGE,UiConfig.NAV_BOX_CORNER_DP));

      state=text(UiConfig.LIVE_BUBBLE_SIZE_SP);
      ((LayoutParams)state.getLayoutParams()).topMargin=dp(UiConfig.LIVE_BUBBLE_BORDER_DP);
      addView(state);
      View line=new View(c);
      line.setBackgroundColor(UiConfig.LIVE_BUBBLE_TEXT);
      LayoutParams separator=new LayoutParams(-1,dp(1));
      separator.setMargins(1,1,1,1);
      addView(line,separator);
      coordinates=text(UiConfig.LIVE_BUBBLE_SIZE_SP);
      address=text(UiConfig.LIVE_BUBBLE_SIZE_SP);
      worship=text(UiConfig.LIVE_BUBBLE_SIZE_SP);
      addView(coordinates)
      ;addView(address);
      addView(worship);
      setVisibility(GONE);
      observer=map.getViewTreeObserver();
      positioner=()->{position();return true;};
      observer.addOnPreDrawListener(positioner);
    }

    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    
    private TextView text(int sp){
      TextView v=new TextView(getContext());
      v.setTextColor(UiConfig.PURPLE);
      v.setTextSize(sp);
      v.setTypeface(Typeface.create(UiConfig.LIVE_BUBBLE_FONT,Typeface.NORMAL));
      v.setIncludeFontPadding(false);
      v.setPadding(4,4,4,4);
      LayoutParams p=new LayoutParams(-1,-2);
      p.setMargins(dp(UiConfig.LIVE_BUBBLE_BORDER_DP),0,dp(UiConfig.LIVE_BUBBLE_BORDER_DP),0);
      v.setLayoutParams(p);return v;
    }

    void update(GeoPoint point,CharSequence status,String locality,String temples){this.point=point;setChangedText(state,status);setChangedText(coordinates,String.format(java.util.Locale.US,"Lat %.4f  ·  Lon %.4f",point.getLatitude(),point.getLongitude()));setChangedText(address,locality);((LayoutParams)address.getLayoutParams()).bottomMargin=dp(UiConfig.LIVE_BUBBLE_BORDER_DP);address.setVisibility(locality.isBlank()?GONE:VISIBLE);setChangedText(worship,temples);worship.setVisibility(temples.isBlank()?GONE:VISIBLE);setVisibility(VISIBLE);position();}
    private static void setChangedText(TextView view,CharSequence value){if(!android.text.TextUtils.equals(view.getText(),value))view.setText(value);}
    private void position(){if(point==null||getVisibility()!=VISIBLE||map.getWidth()==0)return;int width=Math.min(dp(UiConfig.LIVE_BUBBLE_WIDTH_DP),map.getWidth()-dp(16));if(width<=0)return;android.view.ViewGroup.LayoutParams p=getLayoutParams();if(p!=null&&p.width!=width){p.width=width;setLayoutParams(p);}
    Point screen=map.getProjection().toPixels(point,null);map.getProjection().rotateAndScalePoint(screen.x,screen.y,screen);
    if(screen.x<0||screen.y<0||screen.x>map.getWidth()||screen.y>map.getHeight()){setAlpha(0);return;}setAlpha(1);
    float x=Math.max(dp(8),Math.min(map.getWidth()-width-dp(8),screen.x-width/2f)),y=Math.max(dp(7),screen.y-getHeight()-dp(markerClearance));if(Math.abs(getX()-x)>.5f)setX(x);if(Math.abs(getY()-y)>.5f)setY(y);
  }
  void close(){if(observer.isAlive())observer.removeOnPreDrawListener(positioner);}
}

