package in.otrail;

import android.content.Context;
import android.widget.TextView;
import android.view.View;
import java.util.Locale;

/** Minimal working example: a read-only GPS diagnostics panel, with no network or journal writes. */
final class GpsDetailsModule implements TrailModule {
  private TextView view;
  @Override public View createView(Context context){
    view=new TextView(context);AppStyle.text(view);view.setTextSize(UiConfig.SECONDARY_SIZE_SP);
    view.setTextColor(UiConfig.STATUS_TEXT_COLOR);view.setText(UiConfig.text("module_waiting"));
    int padding=Math.round(8*context.getResources().getDisplayMetrics().density);
    view.setPadding(padding,padding,padding,padding);
    android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
    background.setColor(UiConfig.STATUS_BACKGROUND);background.setCornerRadius(padding);
    background.setStroke(Math.max(1,padding/8),UiConfig.STATUS_TEXT_COLOR);view.setBackground(background);
    return view;
  }
  @Override public void update(Snapshot s){
    view.setText(String.format(Locale.US,UiConfig.text("module_gps_details"),s.latitude(),s.longitude(),
        s.accuracy(),Math.max(0,(s.now()-s.fixTime())/1000)));
  }
}
