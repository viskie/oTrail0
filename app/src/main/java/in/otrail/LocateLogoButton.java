package in.otrail;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.ImageButton;

/** App logo in a hexagonal touch target; accessibility still identifies the Locate action. */
final class LocateLogoButton extends ImageButton {
  LocateLogoButton(Context context,Runnable locate){
    super(context);
    setImageDrawable(context.getApplicationInfo().loadIcon(context.getPackageManager()));
    setColorFilter(UiConfig.LOCATE_LOGO_COLOR);
    setScaleType(ScaleType.FIT_CENTER);
    int inset=Math.round((UiConfig.LOCATE_SIZE_DP-28*UiConfig.LOCATE_VISUAL_SCALE)/2*getResources().getDisplayMetrics().density);
    setPadding(inset,inset,inset,inset);
    setBackground(new RippleDrawable(ColorStateList.valueOf(UiConfig.LOCATE_RIPPLE_COLOR),
        new Hex(UiConfig.LOCATE_BACKGROUND,UiConfig.LOCATE_BORDER_COLOR),new Hex(Color.WHITE,Color.WHITE)));
    setContentDescription(UiConfig.text("locate_me_and_follow_gps"));
    setTooltipText(UiConfig.text("locate"));
    setOnClickListener(view->locate.run());
  }
  private static final class Hex extends Drawable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int fill,edge;
    Hex(int fill,int edge){this.fill=fill;this.edge=edge;}
    @Override public void draw(Canvas canvas){
      Rect b=getBounds();float stroke=Math.max(2,b.width()*.018f);
      float radius=(Math.min(b.width(),b.height())/2f-stroke)*UiConfig.LOCATE_VISUAL_SCALE;
      float cx=b.exactCenterX(),cy=b.exactCenterY(),depth=Math.max(2,b.height()*.035f);
      Path path=new Path();
      for(int i=0;i<6;i++){
        double angle=Math.PI*i/3;
        float x=cx+radius*(float)Math.cos(angle),y=cy+radius*(float)Math.sin(angle);
        if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
      }
      path.close();if(fill!=Color.WHITE)NeonBorderDrawable.pathGlow(canvas,path,paint,edge,stroke);paint.setStyle(Paint.Style.FILL);paint.setShader(null);
      if(fill!=Color.WHITE){
        canvas.save();canvas.translate(0,depth);paint.setColor(UiConfig.LOCATE_BEVEL_SHADOW);canvas.drawPath(path,paint);canvas.restore();
        paint.setShader(new LinearGradient(cx-radius,cy-radius,cx+radius,cy+radius,
            new int[]{UiConfig.LOCATE_BEVEL_LIGHT,fill,fill},null,Shader.TileMode.CLAMP));
      }
      paint.setColor(fill);canvas.drawPath(path,paint);paint.setShader(null);
      paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setColor(edge);canvas.drawPath(path,paint);
      if(fill!=Color.WHITE){
        paint.setColor(UiConfig.LOCATE_BEVEL_LIGHT);paint.setStrokeWidth(stroke*.55f);
        canvas.drawLine(cx-radius,cy,cx-radius*.5f,cy-radius*.8660254f,paint);
        canvas.drawLine(cx-radius*.5f,cy-radius*.8660254f,cx+radius*.5f,cy-radius*.8660254f,paint);
      }
    }
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
  }
}
