package in.otrail;

import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.widget.*;

/** Shared UI tokens; geographic pass colors remain in TrailPalette. */
public final class AppStyle {
  public static final int BLUE=0xff0078ff,PURPLE=0xffbd00ff,ORANGE=0xffff9a00,LIME=0xff01ff1f,YELLOW=0xffe3ff00;
  public static final int ACCENT_TEXT=0xff004fa8,HEADING=0xff7600a0,SECONDARY=0xff895200;
  public static final int BUTTON=0xff8b21b8,BUTTON_TEXT=0xff29335c;
  public static final int BACKGROUND=0xffd4d4d8,SURFACE=0xffe7e7eb,INK=0xff1b1b24,MUTED=0xff51515e;
  public static final int CYAN=0xff17deee,CORAL=0xffff7f50,PINK=0xffff4162,LEMON=0xffecf284,TEAL=0xff10aeb2;
  private static int dp(android.view.View v,float n){return (int)(v.getResources().getDisplayMetrics().density*n+.5f);}
  public static void button(Button b){button(b,0);}
  public static void button(Button b,int emphasis) {
    b.setAllCaps(false);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setTextSize(12);b.setSingleLine(true);b.setEllipsize(android.text.TextUtils.TruncateAt.END);b.setLetterSpacing(.01f);
    b.setMinHeight(dp(b,44));b.setMinimumHeight(dp(b,44));b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(b,9),0,dp(b,9),0);b.setStateListAnimator(null);
    int fill=BUTTON;
    GradientDrawable shape=new GradientDrawable();shape.setColor(fill);shape.setCornerRadius(dp(b,9));shape.setStroke(dp(b,1),BUTTON_TEXT);
    b.setBackgroundTintList(null);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x66e3ff00),shape,null));
    b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0x9929335c,BUTTON_TEXT}));
  }
  public static void text(TextView t){t.setTypeface(Typeface.create("sans-serif-condensed",Typeface.NORMAL));t.setTextColor(ACCENT_TEXT);t.setIncludeFontPadding(false);}
  public static int textColor(int size,boolean bold){return bold||size>=18?HEADING:size<=11?SECONDARY:ACCENT_TEXT;}
}
