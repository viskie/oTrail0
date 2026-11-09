package in.otrail;
import android.graphics.*;
import android.graphics.drawable.Drawable;

/** A quiet smile inside the existing GPS circle. No animation, movement or size changes. */
final class HappyGpsFace extends Drawable {
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  @Override public void draw(Canvas canvas){Rect b=getBounds();float w=b.width(),h=b.height(),x=b.left,y=b.top;paint.setColor(UiConfig.GPS_SMILE_COLOR);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(x+w*.36f,y+h*.38f,w*.035f,paint);canvas.drawCircle(x+w*.64f,y+h*.38f,w*.035f,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(w*.055f);paint.setStrokeCap(Paint.Cap.ROUND);canvas.drawArc(new RectF(x+w*.28f,y+h*.40f,x+w*.72f,y+h*.70f),0,180,false,paint);}
  @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}@Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
