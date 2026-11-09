package in.otrail;

import android.app.Activity;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.widget.*;
import java.util.*;
import java.util.function.Supplier;

/** Extend OPTIONS with stable IDs; stored choices never depend on row position. */
public final class GpsMarkerStyle {
  public record Option(String id,String label,int resource,int sizeDp){}
  public static final List<Option> OPTIONS=List.of(
      new Option("robot-snake",UiConfig.text("gps_style_robot"),R.drawable.gps_robot_snake,UiConfig.GPS_ART_MARKER_SIZE_DP),
      new Option("classic-dot",UiConfig.text("gps_style_classic"),0,22));
  private static final Map<Integer,Bitmap> images=new HashMap<>();
  private GpsMarkerStyle(){}
  public static Option selected(Context c){String id=c.getSharedPreferences("gps_marker",0).getString("style",UiConfig.DEFAULT_GPS_MARKER_STYLE);for(Option option:OPTIONS)if(option.id().equals(id))return option;return OPTIONS.get(0);}
  public static Drawable icon(Context c,int stateColor,Supplier<Drawable> classic){Option option=selected(c);return option.resource()==0?classic.get():new ArtMarker(c,option,stateColor);}
  public static void settings(Activity activity,LinearLayout container){
    Button choose=new Button(activity);Runnable label=()->choose.setText(UiConfig.text("gps_marker_style")+" · "+selected(activity).label());label.run();AppStyle.button(choose);
    choose.setOnClickListener(v->{String[] labels=OPTIONS.stream().map(Option::label).toArray(String[]::new);int checked=OPTIONS.indexOf(selected(activity));new AppDialogs(activity).setTitle(UiConfig.text("gps_marker_style")).setSingleChoiceItems(labels,checked,(dialog,index)->{activity.getSharedPreferences("gps_marker",0).edit().putString("style",OPTIONS.get(index).id()).apply();label.run();dialog.dismiss();}).setNegativeButton(UiConfig.text("close"),null).show();});container.addView(choose);
  }
  private static synchronized Bitmap image(Context c,int resource){if(!images.containsKey(resource)){BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;options.inSampleSize=8;images.put(resource,BitmapFactory.decodeResource(c.getResources(),resource,options));}return images.get(resource);}
  private static final class ArtMarker extends Drawable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);private final Bitmap bitmap;private final int size,color;private final float density;
    private int alpha=255;private ColorFilter filter;
    ArtMarker(Context context,Option option,int color){density=context.getResources().getDisplayMetrics().density;size=Math.round(option.sizeDp()*density);this.color=color;bitmap=image(context,option.resource());}
    @Override public void draw(Canvas canvas){Rect b=getBounds();float cx=b.exactCenterX(),cy=b.exactCenterY();
      if(bitmap==null)return;float scale=Math.min(b.width()*.94f/bitmap.getWidth(),b.height()*.94f/bitmap.getHeight()),w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;paint.setStyle(Paint.Style.FILL);paint.setAlpha(alpha);paint.setColorFilter(filter);canvas.drawBitmap(bitmap,null,new RectF(cx-w/2,cy-h/2,cx+w/2,cy+h/2),paint);
    }
    @Override public int getIntrinsicWidth(){return size;}@Override public int getIntrinsicHeight(){return size;}
    @Override public void setAlpha(int value){alpha=value;invalidateSelf();}@Override public void setColorFilter(ColorFilter value){filter=value;invalidateSelf();}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
  }
}
