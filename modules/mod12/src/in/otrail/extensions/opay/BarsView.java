package in.otrail.extensions.opay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Horizontal bar chart drawn locally; no chart library or web view is involved. */
final class BarsView extends View {
  static final class Bar {
    final String label;
    final long value;
    final int color;

    Bar(String label, long value, int color) {
      this.label = label;
      this.value = value;
      this.color = color;
    }
  }

  private final List<Bar> bars = new ArrayList<>();
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final float density;

  BarsView(Context context) {
    super(context);
    density = context.getResources().getDisplayMetrics().density;
    textPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
    textPaint.setTextSize(10f * density);
  }

  void setBars(List<Bar> values) {
    bars.clear();
    bars.addAll(values);
    requestLayout();
    invalidate();
  }

  @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int rows = Math.max(1, bars.size());
    int height = (int) (rows * 24 * density) + (int) (8 * density);
    int width = MeasureSpec.getSize(widthMeasureSpec);
    setMeasuredDimension(width, Math.min(height, (int) (420 * density)));
  }

  @Override protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (bars.isEmpty()) {
      textPaint.setColor(0xff8494a3);
      canvas.drawText("No data for this range", 8 * density, 20 * density, textPaint);
      return;
    }
    long max = 1;
    for (Bar bar : bars) max = Math.max(max, bar.value);
    float rowHeight = 24 * density;
    float top = 4 * density;
    float labelWidth = Math.min(getWidth() * 0.38f, 120 * density);
    for (Bar bar : bars) {
      textPaint.setColor(0xffd8e4ec);
      String label = bar.label;
      while (textPaint.measureText(label) > labelWidth - 6 * density && label.length() > 3)
        label = label.substring(0, label.length() - 2);
      canvas.drawText(label, 2 * density, top + 12 * density, textPaint);
      float barLeft = labelWidth;
      float available = Math.max(10, getWidth() - labelWidth - 8 * density
          - textPaint.measureText("00000") * 0.4f);
      float width = available * (bar.value / (float) max);
      paint.setColor(bar.color);
      paint.setAlpha(210);
      canvas.drawRoundRect(barLeft, top + 3 * density, barLeft + Math.max(2, width),
          top + 17 * density, 3 * density, 3 * density, paint);
      textPaint.setColor(0xff00d4ff);
      canvas.drawText(in.otrail.extensions.opay.core.Money.format(bar.value),
          barLeft + Math.max(2, width) + 4 * density, top + 13 * density, textPaint);
      top += rowHeight;
    }
    paint.setColor(Color.TRANSPARENT);
  }
}
