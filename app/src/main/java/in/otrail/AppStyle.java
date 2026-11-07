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
    b.setAllCaps(false);b.setTypeface(Typeface.create(BUTTON_FONT,BUTTON_FONT_STYLE));b.setTextSize(BUTTON_SIZE_SP);b.setSingleLine(true);b.setEllipsize(android.text.TextUtils.TruncateAt.END);b.setLetterSpacing(.01f);
    b.setMinHeight(dp(b,44));b.setMinimumHeight(dp(b,44));b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(b,9),0,dp(b,9),0);b.setStateListAnimator(null);
    int fill=BUTTON;
    GradientDrawable shape=VIBRANT_FINISH
        ?new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{fill,mix(fill,BUTTON_GRADIENT_ACCENT,BUTTON_GRADIENT_MIX)})
        :new GradientDrawable();
    if(!VIBRANT_FINISH)shape.setColor(fill);
    shape.setCornerRadius(dp(b,10));shape.setStroke(dp(b,1),BUTTON_BORDER);
    android.graphics.drawable.Drawable face=shape;
    if(VIBRANT_FINISH){
      GradientDrawable edge=new GradientDrawable();edge.setColor(BUTTON_EDGE_ACCENT);edge.setCornerRadius(dp(b,11));
      LayerDrawable layers=new LayerDrawable(new android.graphics.drawable.Drawable[]{edge,shape});
      layers.setLayerInset(1,dp(b,1),dp(b,1),dp(b,1),dp(b,3));face=layers;
    }
    b.setBackgroundTintList(null);b.setBackground(new RippleDrawable(ColorStateList.valueOf(BUTTON_RIPPLE),face,null));
    b.setPadding(dp(b,9),0,dp(b,9),0);
    b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{BUTTON_DISABLED_TEXT,BUTTON_TEXT}));
  }
  public static void text(TextView t){t.setTypeface(Typeface.create(BODY_FONT,Typeface.NORMAL));t.setTextColor(ACCENT_TEXT);t.setIncludeFontPadding(false);}
  public static int textColor(int size,boolean bold){return bold||size>=18?HEADING:size<=11?SECONDARY:ACCENT_TEXT;}
  public static void nativeTextTree(android.view.View view) {
    if(view instanceof Button button && !(view instanceof CompoundButton))button(button);
    else if(view instanceof TextView text){
      text.setTextColor(INK);text.setLinkTextColor(ACCENT_TEXT);text.setTextSize(BODY_SIZE_SP*TEXT_SCALE);text.setTypeface(Typeface.create(BODY_FONT,text.getTypeface()==null?Typeface.NORMAL:text.getTypeface().getStyle()));
      if(text instanceof EditText)text.setHintTextColor(MUTED);
    }
    if(view instanceof android.view.ViewGroup group)
      for(int i=0;i<group.getChildCount();i++)nativeTextTree(group.getChildAt(i));
  }
  public static ArrayAdapter<String> choices(android.content.Context context,java.util.List<String> values) {
    return new ArrayAdapter<String>(context,android.R.layout.simple_spinner_dropdown_item,values){
      @Override public android.view.View getView(int position,android.view.View recycled,android.view.ViewGroup parent){
        android.view.View view=super.getView(position,recycled,parent);nativeTextTree(view);return view;
      }
      @Override public android.view.View getDropDownView(int position,android.view.View recycled,android.view.ViewGroup parent){
        android.view.View view=super.getDropDownView(position,recycled,parent);nativeTextTree(view);view.setBackgroundColor(SURFACE);return view;
      }
    };
  }
  /** Blend only the decorative finish; original manual palette values are never rewritten. */
  private static int mix(int from,int to,float amount){
    float t=Math.max(0,Math.min(1,amount));
    return Color.argb((int)(Color.alpha(from)*(1-t)+Color.alpha(to)*t),
        (int)(Color.red(from)*(1-t)+Color.red(to)*t),
        (int)(Color.green(from)*(1-t)+Color.green(to)*t),
        (int)(Color.blue(from)*(1-t)+Color.blue(to)*t));
  }
  public static android.graphics.drawable.Drawable canvas(){
    if(!VIBRANT_FINISH)return new ColorDrawable(BACKGROUND);
    return new GradientDrawable(GradientDrawable.Orientation.TL_BR,
        new int[]{mix(BACKGROUND,CYAN,.12f),BACKGROUND,mix(BACKGROUND,SECONDARY,.10f)});
  }
  public static android.graphics.drawable.Drawable panel(android.view.View view,int accent){
    GradientDrawable fill=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
        new int[]{VIBRANT_FINISH?mix(SURFACE,accent,PANEL_TINT_MIX):SURFACE,SURFACE});
    fill.setCornerRadius(dp(view,9));
    if(VIBRANT_FINISH)fill.setStroke(dp(view,1),accent);
    if(!VIBRANT_FINISH)return fill;
    GradientDrawable edge=new GradientDrawable();edge.setColor(accent);edge.setCornerRadius(dp(view,9));
    LayerDrawable layers=new LayerDrawable(new android.graphics.drawable.Drawable[]{edge,fill});
    layers.setLayerInset(1,dp(view,3),0,0,0);
    return layers;
  }
  public static void decoratePanel(android.view.View view,int accent){
    int left=view.getPaddingLeft(),top=view.getPaddingTop(),right=view.getPaddingRight(),bottom=view.getPaddingBottom();
    view.setBackground(panel(view,accent));view.setPadding(left,top,right,bottom);
  }
  public static void toggle(Switch toggle){
    toggle.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{PURPLE,CYAN}));
    toggle.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{mix(SURFACE,PURPLE,.55f),mix(SURFACE,CYAN,.55f)}));
  }
}
