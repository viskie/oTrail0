package in.otrail;

import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.widget.*;

/** Shared UI tokens; geographic pass colors remain in TrailPalette. */
public final class AppStyle extends UiConfig {
  private static int dp(android.view.View v,float n){return (int)(v.getResources().getDisplayMetrics().density*n+.5f);}
  public static void button(Button b){button(b,0);}
  public static void button(Button b,int emphasis) {
    b.setAllCaps(false);b.setTypeface(Typeface.create(BUTTON_FONT,Typeface.NORMAL));b.setTextSize(BUTTON_SIZE_SP);b.setSingleLine(true);b.setEllipsize(android.text.TextUtils.TruncateAt.END);b.setLetterSpacing(.01f);
    b.setMinHeight(dp(b,44));b.setMinimumHeight(dp(b,44));b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(b,9),0,dp(b,9),0);b.setStateListAnimator(null);
    int fill=BUTTON;
    GradientDrawable shape=new GradientDrawable();shape.setColor(fill);shape.setCornerRadius(dp(b,9));shape.setStroke(dp(b,1),BUTTON_BORDER);
    b.setBackgroundTintList(null);b.setBackground(new RippleDrawable(ColorStateList.valueOf(BUTTON_RIPPLE),shape,null));
    b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{BUTTON_DISABLED_TEXT,BUTTON_TEXT}));
  }
  public static void text(TextView t){t.setTypeface(Typeface.create(BODY_FONT,Typeface.NORMAL));t.setTextColor(ACCENT_TEXT);t.setIncludeFontPadding(false);}
  public static int textColor(int size,boolean bold){return bold||size>=18?HEADING:size<=11?SECONDARY:ACCENT_TEXT;}
}
