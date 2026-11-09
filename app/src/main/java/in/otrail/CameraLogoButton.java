package in.otrail;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.ImageButton;

/** Regular upright pentagon, sized like Locate while retaining the full accessible touch target. */
final class CameraLogoButton extends ImageButton {
  CameraLogoButton(Context context,Runnable capture){
    super(context);setImageResource(android.R.drawable.ic_menu_camera);setColorFilter(UiConfig.CAM_LOGO_COLOR);setScaleType(ScaleType.FIT_CENTER);
    int inset=Math.round((UiConfig.LOCATE_SIZE_DP-28*UiConfig.LOCATE_VISUAL_SCALE)/2*getResources().getDisplayMetrics().density);setPadding(inset,inset,inset,inset);
    setBackground(new RippleDrawable(ColorStateList.valueOf(UiConfig.LOCATE_RIPPLE_COLOR),new Pentagon(false),new Pentagon(true)));
    setContentDescription("CAM · Take a geotagged photo");setTooltipText("CAM");setOnClickListener(v->capture.run());
  }
  private static final class Pentagon extends Drawable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final boolean mask;
    Pentagon(boolean mask){this.mask=mask;}
    @Override public void draw(Canvas canvas){
      Rect b=getBounds();float stroke=Math.max(2,b.width()*.018f),radius=(Math.min(b.width(),b.height())/2f-stroke)*UiConfig.LOCATE_VISUAL_SCALE;
      float cx=b.exactCenterX(),cy=b.exactCenterY(),depth=Math.max(2,b.height()*.035f);Path path=new Path();
      for(int i=0;i<5;i++){double angle=-Math.PI/2+i*2*Math.PI/5;float x=cx+radius*(float)Math.cos(angle),y=cy+radius*(float)Math.sin(angle);if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}path.close();
      paint.setStyle(Paint.Style.FILL);paint.setShader(null);
      if(mask){paint.setColor(Color.WHITE);canvas.drawPath(path,paint);return;}
      NeonBorderDrawable.pathGlow(canvas,path,paint,UiConfig.CAM_BORDER_COLOR,stroke);
      canvas.save();canvas.translate(0,depth);paint.setColor(UiConfig.LOCATE_BEVEL_SHADOW);canvas.drawPath(path,paint);canvas.restore();
      paint.setShader(new LinearGradient(cx-radius,cy-radius,cx+radius,cy+radius,new int[]{UiConfig.LOCATE_BEVEL_LIGHT,UiConfig.CAM_BACKGROUND,UiConfig.CAM_BACKGROUND},null,Shader.TileMode.CLAMP));canvas.drawPath(path,paint);paint.setShader(null);
      paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setColor(UiConfig.CAM_BORDER_COLOR);canvas.drawPath(path,paint);
    }
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
  }
}
