package in.otrail;

import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.widget.*;

/** Shared UI tokens; geographic pass colors remain in TrailPalette. */
public final class AppStyle extends UiConfig {
  private static int dp(android.view.View v,float n){return (int)(v.getResources().getDisplayMetrics().density*n+.5f);}
  public static void button(Button b){button(b,0);}




public static void button(Button b, int emphasis) {
    b.setGravity(android.view.Gravity.CENTER);
    b.setAllCaps(false);
    b.setTypeface(Typeface.create(BUTTON_FONT, BUTTON_FONT_STYLE));
    b.setTextSize(BUTTON_SIZE_SP);
    b.setSingleLine(true);
    b.setEllipsize(android.text.TextUtils.TruncateAt.END);
    b.setLetterSpacing(.01f);
    b.setStateListAnimator(null);

    b.setMinHeight(dp(b, 34));
    b.setMinimumHeight(dp(b, 34));
    b.setMinWidth(0);
    b.setMinimumWidth(0);

    android.graphics.drawable.Drawable neon = NeonBorderDrawable.button(b);

    b.setBackgroundTintList(null);

    b.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(BUTTON_RIPPLE),
            neon,
            null
        )
    );

    b.setPadding(
        dp(b, BUTTON_INNER_PADDING_DP + 6),
        0,
        dp(b, BUTTON_INNER_PADDING_DP + 6),
        0
    );

    b.setTextColor(
        new ColorStateList(
            new int[][]{
                new int[]{-android.R.attr.state_enabled},
                new int[]{}
            },
            new int[]{
                BUTTON_DISABLED_TEXT,
                BUTTON_TEXT
            }
        )
    );
}


  public static void text(TextView t){t.setTypeface(Typeface.create(BODY_FONT,BODY_FONT_STYLE));t.setTextColor(ACCENT_TEXT);t.setIncludeFontPadding(false);}
  public static int textColor(int size,boolean bold){return bold||size>=18?HEADING:size<=11?SECONDARY:ACCENT_TEXT;}
  public static void nativeTextTree(android.view.View view) {
    if(view instanceof Button button && !(view instanceof CompoundButton))button(button);
    else if(view instanceof TextView text){
      text.setTextColor(INK);text.setLinkTextColor(ACCENT_TEXT);text.setTextSize(BODY_SIZE_SP*TEXT_SCALE);text.setTypeface(bodyTypeface(text.getTypeface()));
      if(text instanceof EditText)text.setHintTextColor(MUTED);
    }
    if(view instanceof android.view.ViewGroup group)
      for(int i=0;i<group.getChildCount();i++)nativeTextTree(group.getChildAt(i));
  }
  private static Typeface bodyTypeface(Typeface existing){
    int style=(existing==null?Typeface.NORMAL:existing.getStyle())|BODY_FONT_STYLE;
    return switch(style&3){
      case 3 -> Typeface.create(BODY_FONT,Typeface.BOLD_ITALIC);
      case 2 -> Typeface.create(BODY_FONT,Typeface.ITALIC);
      case 1 -> Typeface.create(BODY_FONT,Typeface.BOLD);
      default -> Typeface.create(BODY_FONT,Typeface.NORMAL);
    };
  }
  public static ArrayAdapter<String> choices(android.content.Context context,java.util.List<String> values) {
    return choices(context,values,BODY_SIZE_SP);
  }
  public static ArrayAdapter<String> choices(android.content.Context context,java.util.List<String> values,int sizeSp) {
    return new ArrayAdapter<String>(context,android.R.layout.simple_spinner_dropdown_item,values){
      @Override public android.view.View getView(int position,android.view.View recycled,android.view.ViewGroup parent){
        android.view.View view=super.getView(position,recycled,parent);nativeTextTree(view);if(view instanceof TextView text)text.setTextSize(sizeSp*TEXT_SCALE);return view;
      }
      @Override public android.view.View getDropDownView(int position,android.view.View recycled,android.view.ViewGroup parent){
        android.view.View view=super.getDropDownView(position,recycled,parent);nativeTextTree(view);if(view instanceof TextView text)text.setTextSize(sizeSp*TEXT_SCALE);view.setBackgroundColor(SURFACE);return view;
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
    int[] colors=new int[CANVAS_SPECTRUM.length];
    for(int i=0;i<colors.length;i++)colors[i]=mix(BACKGROUND,CANVAS_SPECTRUM[i],i==colors.length-1?.90f:CANVAS_COLOR_STRENGTH);
    return new GradientDrawable(GradientDrawable.Orientation.TL_BR,PSYCHEDELIC_GRADIENTS?colors:
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
  public static void spaceButtons(LinearLayout row){
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
    int inset=dp(row,CONTROL_ROW_PADDING_DP),halfGap=dp(row,BUTTON_GAP_DP)/2;
    row.setPadding(dp(row,PANEL_INNER_PADDING_DP),inset,dp(row,PANEL_INNER_PADDING_DP),inset);
    for(int i=0;i<row.getChildCount();i++){
      android.view.View child=row.getChildAt(i);
      if(child instanceof Button && !(child instanceof CompoundButton)){
        LinearLayout.LayoutParams params=child.getLayoutParams() instanceof LinearLayout.LayoutParams current
            ?current:new LinearLayout.LayoutParams(-2,dp(row,32));
        params.height=dp(row,32);params.setMargins(halfGap,0,halfGap,0);child.setLayoutParams(params);
      }else if(child instanceof TextView label && !(child instanceof CompoundButton)){
        label.setGravity(android.view.Gravity.CENTER_VERTICAL);label.setMinHeight(dp(row,32));
      }
    }
  }
  /** Reflow existing settings buttons; listeners and asynchronous status references stay attached. */
  public static void compactSettings(android.view.ViewGroup group,int requestedColumns){
    int columns=Math.max(1,requestedColumns);
    java.util.List<android.view.View> children=new java.util.ArrayList<>();
    for(int i=0;i<group.getChildCount();i++)children.add(group.getChildAt(i));
    for(android.view.View child:children)if(child instanceof android.view.ViewGroup nested)compactSettings(nested,columns);
    if(!(group instanceof LinearLayout box))return;
    if(box.getOrientation()==LinearLayout.HORIZONTAL){
      boolean onlyButtons=box.getChildCount()>0&&box.getChildCount()<=columns;
      for(int i=0;i<box.getChildCount();i++)onlyButtons&=box.getChildAt(i) instanceof Button;
      if(onlyButtons){
        for(int i=box.getChildCount();i<columns;i++)box.addView(new android.view.View(box.getContext()),new LinearLayout.LayoutParams(0,1,1));
        spaceButtons(box);
      }
      return;
    }
    int index=0;
    while(index<box.getChildCount()){
      if(!(box.getChildAt(index) instanceof Button)){index++;continue;}
      java.util.List<Button> run=new java.util.ArrayList<>();
      while(index<box.getChildCount()&&box.getChildAt(index) instanceof Button button){box.removeViewAt(index);run.add(button);}
      for(int start=0;start<run.size();start+=columns){
        LinearLayout row=new LinearLayout(box.getContext());
        for(int c=0;c<columns;c++){
          if(start+c<run.size()){
            Button button=run.get(start+c);button.setSingleLine(false);button.setMaxLines(2);
            row.addView(button,new LinearLayout.LayoutParams(0,dp(box,44),1));
          }else row.addView(new android.view.View(box.getContext()),new LinearLayout.LayoutParams(0,1,1));
        }
        spaceButtons(row);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.setMargins(0,dp(box,2),0,dp(box,2));box.addView(row,index++,params);
      }
    }
  }
  public static void toggle(Switch toggle){
    toggle.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{PURPLE,CYAN}));
    toggle.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{mix(SURFACE,PURPLE,.55f),mix(SURFACE,CYAN,.55f)}));
  }
}
