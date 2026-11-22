package in.otrail;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.ImageButton;

/** Heptagonal map-bar shortcut to the separate remote-desktop Activity. */
final class ScreenShareButton extends ImageButton {
  ScreenShareButton(Context context, Runnable open) {
    super(context);
    setScaleType(ScaleType.FIT_CENTER);
    int inset = Math.round((UiConfig.LOCATE_SIZE_DP - 28) / 2
        * getResources().getDisplayMetrics().density);
    setPadding(inset, inset, inset, inset);
    setImageDrawable(new Glyph());
    setBackground(new RippleDrawable(
        ColorStateList.valueOf(UiConfig.LOCATE_RIPPLE_COLOR),
        new Heptagon(UiConfig.SCREEN_SHARE_BACKGROUND, UiConfig.SCREEN_SHARE_ICON_COLOR),
        new Heptagon(Color.WHITE, Color.WHITE)));
    setContentDescription(UiConfig.text("remote_desktop"));
    setTooltipText(UiConfig.text("remote_desktop"));
    setOnClickListener(view -> open.run());
  }

  private static final class Glyph extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    @Override public void draw(Canvas canvas) {
      Rect bounds = getBounds();
      float left = bounds.left + bounds.width() * .22f;
      float right = bounds.right - bounds.width() * .22f;
      float top = bounds.top + bounds.height() * .27f;
      float bottom = bounds.top + bounds.height() * .67f;
      paint.setColor(UiConfig.SCREEN_SHARE_ICON_COLOR);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(Math.max(2, bounds.width() * .055f));
      paint.setStrokeJoin(Paint.Join.ROUND);
      canvas.drawRoundRect(left, top, right, bottom, bounds.width() * .035f,
          bounds.width() * .035f, paint);
      canvas.drawLine(bounds.exactCenterX(), bottom, bounds.exactCenterX(),
          bounds.bottom - bounds.height() * .22f, paint);
      canvas.drawLine(left + bounds.width() * .14f, bounds.bottom - bounds.height() * .22f,
          right - bounds.width() * .14f, bounds.bottom - bounds.height() * .22f, paint);
      paint.setStyle(Paint.Style.FILL);
      Path arrow = new Path();
      arrow.moveTo(bounds.left + bounds.width() * .36f, bounds.top + bounds.height() * .45f);
      arrow.lineTo(bounds.left + bounds.width() * .51f, bounds.top + bounds.height() * .57f);
      arrow.lineTo(bounds.left + bounds.width() * .64f, bounds.top + bounds.height() * .41f);
      arrow.lineTo(bounds.left + bounds.width() * .64f, bounds.top + bounds.height() * .49f);
      arrow.lineTo(bounds.left + bounds.width() * .51f, bounds.top + bounds.height() * .64f);
      arrow.lineTo(bounds.left + bounds.width() * .36f, bounds.top + bounds.height() * .53f);
      arrow.close();
      canvas.drawPath(arrow, paint);
    }
    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(android.graphics.ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
  }

  private static final class Heptagon extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int fill, edge;
    Heptagon(int fill, int edge) { this.fill = fill; this.edge = edge; }
    @Override public void draw(Canvas canvas) {
      Rect bounds = getBounds();
      float stroke = Math.max(2, bounds.width() * .035f);
      float radius = (Math.min(bounds.width(), bounds.height()) / 2f - stroke)
          * UiConfig.LOCATE_VISUAL_SCALE;
      Path polygon = new Path();
      for (int i = 0; i < 7; i++) {
        double angle = -Math.PI / 2 + i * Math.PI * 2 / 7;
        float x = bounds.exactCenterX() + radius * (float) Math.cos(angle);
        float y = bounds.exactCenterY() + radius * (float) Math.sin(angle);
        if (i == 0) polygon.moveTo(x, y); else polygon.lineTo(x, y);
      }
      polygon.close();
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(fill);
      canvas.drawPath(polygon, paint);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(stroke);
      paint.setColor(edge);
      canvas.drawPath(polygon, paint);
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(android.graphics.ColorFilter filter) {
      paint.setColorFilter(filter); invalidateSelf();
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
  }
}
