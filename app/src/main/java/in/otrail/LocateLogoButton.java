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
    int inset=Math.round(14*getResources().getDisplayMetrics().density);
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
      Rect b=getBounds();float stroke=Math.max(2,b.width()*.025f),left=b.left+stroke,top=b.top+stroke;
      float w=b.width()-2*stroke,h=b.height()-2*stroke;
      Path path=new Path();path.moveTo(left+w*.25f,top);path.lineTo(left+w*.75f,top);
      path.lineTo(left+w,top+h*.5f);path.lineTo(left+w*.75f,top+h);
      path.lineTo(left+w*.25f,top+h);path.lineTo(left,top+h*.5f);path.close();
      paint.setStyle(Paint.Style.FILL);paint.setColor(fill);canvas.drawPath(path,paint);
      paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setColor(edge);canvas.drawPath(path,paint);
    }
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
  }
}
