package in.otrail;

import android.app.Activity;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.*;
import android.widget.*;

/** In-app collapsible navigation, with the same editable gradient as the right-hand panel. */
final class MapMenu extends FrameLayout {
  private final Activity activity;private final FrameLayout shade;private final LinearLayout panel;
  MapMenu(Activity a,boolean history,Runnable tracker,Runnable places,Runnable stops,Runnable journeys,Runnable settings){super(a);activity=a;setElevation(dp(9));
    ImageButton trigger=new ImageButton(a);trigger.setContentDescription("Open navigation menu");trigger.setTooltipText("Navigation menu");trigger.setImageDrawable(new Hamburger());trigger.setColorFilter(UiConfig.LOCATE_LOGO_COLOR);trigger.setScaleType(ImageView.ScaleType.FIT_CENTER);
    int inset=dp(Math.round((UiConfig.LOCATE_SIZE_DP-28*UiConfig.LOCATE_VISUAL_SCALE)/2));trigger.setPadding(inset,inset,inset,inset);android.graphics.drawable.GradientDrawable icon=new android.graphics.drawable.GradientDrawable();icon.setColor(UiConfig.LOCATE_BACKGROUND);icon.setCornerRadius(dp(10));icon.setStroke(dp(1),UiConfig.LOCATE_BORDER_COLOR);trigger.setBackground(icon);
    LayoutParams iconPosition=new LayoutParams(dp(UiConfig.LOCATE_SIZE_DP),dp(UiConfig.LOCATE_SIZE_DP),Gravity.TOP|Gravity.START);iconPosition.setMargins(dp(10),dp(10),0,0);addView(trigger,iconPosition);
    shade=new FrameLayout(a);shade.setBackgroundColor(0x66000000);shade.setVisibility(GONE);shade.setOnClickListener(v->closeIfOpen());addView(shade,new LayoutParams(-1,-1));
    ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setBackground(AppStyle.canvas());scroll.setOnClickListener(v->{});scroll.setClickable(true);shade.addView(scroll,new LayoutParams(dp(260),-1,Gravity.START));
    panel=new LinearLayout(a);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(8),dp(8),dp(8),dp(8));scroll.addView(panel,new ScrollView.LayoutParams(-1,-1));
    TextView heading=new TextView(a);heading.setText("Navigation");AppStyle.text(heading);heading.setTextSize(18);heading.setTextColor(UiConfig.HEADING);panel.addView(heading);
    add("× Close menu",()->closeIfOpen());add(UiConfig.text("tracker"),tracker);add(UiConfig.text("places"),places);add(UiConfig.text("stops"),stops);add(history?UiConfig.text("live"):UiConfig.text("history"),journeys);add(UiConfig.text("settings"),settings);
    trigger.setOnClickListener(v->{shade.setVisibility(VISIBLE);shade.bringToFront();});
  }
  private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
  private void add(String label,Runnable action){Button b=new Button(activity);b.setText(label);AppStyle.button(b);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(46));p.setMargins(0,dp(4),0,dp(4));panel.addView(b,p);b.setOnClickListener(v->{closeIfOpen();action.run();});}
  boolean closeIfOpen(){if(shade.getVisibility()!=VISIBLE)return false;shade.setVisibility(GONE);return true;}
  @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);if(shade.getChildCount()>0){View child=shade.getChildAt(0);LayoutParams p=(LayoutParams)child.getLayoutParams();p.width=Math.min(dp(260),Math.round(w*.82f));child.setLayoutParams(p);}}
  private static final class Hamburger extends Drawable {private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);@Override public void draw(Canvas c){Rect b=getBounds();p.setColor(UiConfig.LOCATE_LOGO_COLOR);p.setStrokeWidth(b.height()*.1f);p.setStrokeCap(Paint.Cap.ROUND);for(int i=0;i<3;i++){float y=b.top+b.height()*(.22f+i*.28f);c.drawLine(b.left+b.width()*.1f,y,b.right-b.width()*.1f,y,p);}}@Override public int getIntrinsicWidth(){return 28;}@Override public int getIntrinsicHeight(){return 28;}@Override public void setAlpha(int alpha){p.setAlpha(alpha);invalidateSelf();}@Override public void setColorFilter(ColorFilter f){p.setColorFilter(f);invalidateSelf();}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}}
}
