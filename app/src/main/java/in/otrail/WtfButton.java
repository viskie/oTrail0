package in.otrail;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.ImageButton;

/** Regular octagonal base-app toggle; same visual scale and touch target as the other map icons. */
final class WtfButton extends ImageButton {
  WtfButton(Context context,boolean enabled,Runnable toggle){
    super(context);setScaleType(ScaleType.FIT_CENTER);int inset=Math.round((UiConfig.LOCATE_SIZE_DP-28*UiConfig.LOCATE_VISUAL_SCALE)/2*getResources().getDisplayMetrics().density);setPadding(inset,inset,inset,inset);
    setTooltipText(UiConfig.WTF_LABEL);setOnClickListener(v->toggle.run());update(enabled);
  }
  void update(boolean enabled){
    setSelected(enabled);setActivated(enabled);setContentDescription(UiConfig.WTF_LABEL+(enabled?" enabled · tap to disable":" disabled · tap to enable"));setStateDescription(enabled?"Enabled":"Disabled");
    int ink=enabled?UiConfig.WTF_ENABLED_TEXT:UiConfig.WTF_DISABLED_TEXT,fill=enabled?UiConfig.WTF_ENABLED_BACKGROUND:UiConfig.WTF_DISABLED_BACKGROUND;
    setImageDrawable(new Glyph(ink));setBackground(new RippleDrawable(ColorStateList.valueOf(UiConfig.LOCATE_RIPPLE_COLOR),new Octagon(fill,ink,false),new Octagon(Color.WHITE,Color.WHITE,true)));
  }
  private static final class Glyph extends Drawable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    Glyph(int color){paint.setColor(color);paint.setTypeface(Typeface.create(UiConfig.HEADING_FONT,Typeface.BOLD));paint.setTextAlign(Paint.Align.CENTER);}
    @Override public void draw(Canvas canvas){Rect b=getBounds();paint.setTextSize(b.height()*UiConfig.WTF_ICON_SIZE_SP/24f);Paint.FontMetrics metrics=paint.getFontMetrics();canvas.drawText(UiConfig.WTF_ICON_TEXT,b.exactCenterX(),b.exactCenterY()-(metrics.ascent+metrics.descent)/2,paint);}
    @Override public int getIntrinsicWidth(){return 28;}@Override public int getIntrinsicHeight(){return 28;}
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}@Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
  }
  private static final class Octagon extends Drawable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final int fill,edge;private final boolean mask;
    Octagon(int fill,int edge,boolean mask){this.fill=fill;this.edge=edge;this.mask=mask;}
    @Override public void draw(Canvas canvas){
      Rect b=getBounds();float stroke=Math.max(2,b.width()*.018f),radius=(Math.min(b.width(),b.height())/2f-stroke)*UiConfig.LOCATE_VISUAL_SCALE,cx=b.exactCenterX(),cy=b.exactCenterY();Path path=new Path();
      for(int i=0;i<8;i++){double angle=-Math.PI/2+Math.PI/8+i*Math.PI/4;float x=cx+radius*(float)Math.cos(angle),y=cy+radius*(float)Math.sin(angle);if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}path.close();paint.setStyle(Paint.Style.FILL);paint.setShader(null);
      if(mask){paint.setColor(Color.WHITE);canvas.drawPath(path,paint);return;}
      NeonBorderDrawable.pathGlow(canvas,path,paint,edge,stroke);
      canvas.save();canvas.translate(0,Math.max(2,b.height()*.035f));paint.setColor(UiConfig.LOCATE_BEVEL_SHADOW);canvas.drawPath(path,paint);canvas.restore();
      paint.setShader(new LinearGradient(cx-radius,cy-radius,cx+radius,cy+radius,new int[]{UiConfig.LOCATE_BEVEL_LIGHT,fill,fill},null,Shader.TileMode.CLAMP));canvas.drawPath(path,paint);paint.setShader(null);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setColor(edge);canvas.drawPath(path,paint);
    }
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}@Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
  }
}
