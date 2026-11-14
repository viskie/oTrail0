package in.otrail;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Screen-space true-north reference; GPS course rotates the map, never a magnetic compass. */
final class WtfNorthView extends View {
  private float degrees;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  WtfNorthView(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);setClickable(false);}
  void rotation(float value){degrees=value;invalidate();}
  @Override protected void onDraw(Canvas canvas){float cx=getWidth()/2f,cy=getHeight()/2f,r=getWidth()*.25f;double angle=Math.toRadians(degrees);float x=cx+r*(float)Math.sin(angle),y=cy-r*(float)Math.cos(angle);
    paint.setStyle(Paint.Style.FILL);paint.setColor(UiConfig.WTF_NORTH_BACKGROUND);canvas.drawCircle(cx,cy,getWidth()*.46f,paint);paint.setColor(UiConfig.WTF_NORTH_COLOR);paint.setStrokeWidth(getWidth()*.035f);canvas.drawLine(cx,cy,x,y,paint);
    canvas.save();canvas.translate(x,y);canvas.rotate(degrees);Path p=new Path();p.moveTo(0,-r*.24f);p.lineTo(-r*.2f,r*.12f);p.lineTo(r*.2f,r*.12f);p.close();canvas.drawPath(p,paint);canvas.restore();paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(getWidth()*.19f);paint.setTypeface(Typeface.create(UiConfig.BODY_FONT,Typeface.BOLD));canvas.drawText("N",cx,cy+getWidth()*.36f,paint);
  }
}
