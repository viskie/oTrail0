package in.otrail;

import android.app.Activity;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.*;
import android.widget.*;

/** In-app collapsible navigation, with a transparent floating icon-and-label list. */
final class MapMenu extends FrameLayout {
  private final Activity activity;private final FrameLayout shade;private final LinearLayout panel;
  MapMenu(Activity a,boolean history,Runnable tracker,Runnable places,Runnable stops,Runnable journeys,Runnable navigationHistory,Runnable religiousDirectory,Runnable settings,Runnable updates,Runnable historicalTrails){super(a);activity=a;setElevation(dp(9));
    ImageButton trigger=new ImageButton(a);trigger.setContentDescription("Open navigation menu");trigger.setTooltipText("Navigation menu");trigger.setImageDrawable(new Hamburger());trigger.setColorFilter(UiConfig.LOCATE_LOGO_COLOR);trigger.setScaleType(ImageView.ScaleType.FIT_CENTER);
    int inset=dp(Math.round((UiConfig.LOCATE_SIZE_DP-28*UiConfig.LOCATE_VISUAL_SCALE)/2));trigger.setPadding(inset,inset,inset,inset);trigger.setBackground(NeonBorderDrawable.menuIcon(trigger,UiConfig.LOCATE_BORDER_COLOR));
    LayoutParams iconPosition=new LayoutParams(dp(UiConfig.LOCATE_SIZE_DP),dp(UiConfig.LOCATE_SIZE_DP),Gravity.TOP|Gravity.START);iconPosition.setMargins(dp(10),dp(10),0,0);addView(trigger,iconPosition);
    shade=new FrameLayout(a);shade.setBackgroundColor(Color.TRANSPARENT);shade.setVisibility(GONE);shade.setOnClickListener(v->closeIfOpen());addView(shade,new LayoutParams(-1,-1));
    ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.TRANSPARENT);scroll.setOnClickListener(v->{});scroll.setClickable(true);shade.addView(scroll,new LayoutParams(dp(260),-1,Gravity.START));
    panel=new LinearLayout(a);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(8),dp(8),dp(8),dp(8));scroll.addView(panel,new ScrollView.LayoutParams(-1,-1));
    TextView heading=new TextView(a);heading.setText("Navigation");AppStyle.text(heading);heading.setTextSize(18);heading.setTextColor(UiConfig.ORANGE);heading.setShadowLayer(dp(3),0,dp(1),Color.BLACK);panel.addView(heading);
    add("Collapse menu",android.R.drawable.ic_menu_close_clear_cancel,()->closeIfOpen());
    add(UiConfig.text("tracker"),android.R.drawable.ic_menu_mylocation,tracker);
    add(UiConfig.text("places"),android.R.drawable.ic_menu_mapmode,places);
    add(UiConfig.text("stops"),android.R.drawable.ic_menu_myplaces,stops);
    add(history?UiConfig.text("live"):UiConfig.text("history"),android.R.drawable.ic_menu_recent_history,journeys);
    add("Historical trails",android.R.drawable.ic_menu_upload,historicalTrails);
    add("Navigation history",android.R.drawable.ic_menu_directions,navigationHistory);
    add("Religious places",android.R.drawable.ic_menu_compass,religiousDirectory);
    add(UiConfig.text("settings"),android.R.drawable.ic_menu_preferences,settings);
    add("App updates",android.R.drawable.stat_sys_download_done,updates);
    trigger.setOnClickListener(v->{shade.setVisibility(VISIBLE);shade.bringToFront();});
  }
  private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
  private void add(String label,int icon,Runnable action){
    LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(10),0,dp(10),0);row.setContentDescription(label);row.setFocusable(true);
    android.graphics.drawable.GradientDrawable touch=new android.graphics.drawable.GradientDrawable();touch.setColor(0x33000000);touch.setCornerRadius(dp(12));row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(UiConfig.PURPLE),null,touch));
    TextView text=new TextView(activity);text.setText(label);AppStyle.text(text);text.setTextColor(UiConfig.PURPLE);text.setTextSize(UiConfig.BUTTON_SIZE_SP);text.setTypeface(Typeface.create(UiConfig.BUTTON_FONT,UiConfig.BUTTON_FONT_STYLE));text.setShadowLayer(dp(3),0,dp(1),Color.BLACK);row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
    ImageView image=new ImageView(activity);image.setImageResource(icon);image.setColorFilter(UiConfig.PURPLE);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);LinearLayout.LayoutParams imageSize=new LinearLayout.LayoutParams(dp(28),dp(28));imageSize.setMargins(dp(8),0,0,0);row.addView(image,imageSize);
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.setMargins(0,dp(2),0,dp(2));panel.addView(row,p);row.setOnClickListener(v->{closeIfOpen();action.run();});
  }
  boolean closeIfOpen(){if(shade.getVisibility()!=VISIBLE)return false;shade.setVisibility(GONE);return true;}
  @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);if(shade.getChildCount()>0){View child=shade.getChildAt(0);LayoutParams p=(LayoutParams)child.getLayoutParams();p.width=Math.min(dp(260),Math.round(w*.82f));child.setLayoutParams(p);}}
  private static final class Hamburger extends Drawable {private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);@Override public void draw(Canvas c){Rect b=getBounds();p.setColor(UiConfig.LOCATE_LOGO_COLOR);p.setStrokeWidth(b.height()*.1f);p.setStrokeCap(Paint.Cap.ROUND);for(int i=0;i<3;i++){float y=b.top+b.height()*(.22f+i*.28f);c.drawLine(b.left+b.width()*.1f,y,b.right-b.width()*.1f,y,p);}}@Override public int getIntrinsicWidth(){return 28;}@Override public int getIntrinsicHeight(){return 28;}@Override public void setAlpha(int alpha){p.setAlpha(alpha);invalidateSelf();}@Override public void setColorFilter(ColorFilter f){p.setColorFilter(f);invalidateSelf();}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}}
}
