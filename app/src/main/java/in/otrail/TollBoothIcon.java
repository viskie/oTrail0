package in.otrail;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Distinct map pin indicating a mapped toll location. */
final class TollBoothIcon extends Drawable {
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  private final float density;

  TollBoothIcon(Context context) {
    density=context.getResources().getDisplayMetrics().density;
  }

  @Override public void draw(Canvas canvas) {
    Rect bounds=getBounds();
    float size=Math.min(bounds.width(),bounds.height());
    float cx=bounds.exactCenterX(),cy=bounds.top+size*.39f,r=size*.30f;
    Path pin=new Path();
    pin.moveTo(cx-r*.74f,cy+r*.35f);
    pin.lineTo(cx,cy+r*1.65f);
    pin.lineTo(cx+r*.74f,cy+r*.35f);
    pin.close();
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(UiConfig.TOLL_MARKER_COLOR);
    canvas.drawPath(pin,paint);
    canvas.drawCircle(cx,cy,r,paint);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(Math.max(1,density*1.5f));
    paint.setColor(Color.BLACK);
    canvas.drawPath(pin,paint);
    canvas.drawCircle(cx,cy,r,paint);
    paint.setStyle(Paint.Style.FILL);
    float gateY=cy-r*.08f;
    paint.setStrokeWidth(density*2f);
    canvas.drawRect(cx-r*.62f,gateY-density,cx+r*.62f,gateY+density,paint);
    canvas.drawRect(cx-r*.46f,gateY-r*.62f,cx-r*.30f,gateY+r*.62f,paint);
    canvas.drawRect(cx+r*.30f,gateY-r*.62f,cx+r*.46f,gateY+r*.62f,paint);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(Math.max(1,density));
    paint.setColor(Color.BLACK);
    canvas.drawRect(cx-r*.62f,gateY-density,cx+r*.62f,gateY+density,paint);
  }

  @Override public int getIntrinsicWidth(){return Math.round(32*density);}
  @Override public int getIntrinsicHeight(){return Math.round(38*density);}
  @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
  @Override public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
  @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
