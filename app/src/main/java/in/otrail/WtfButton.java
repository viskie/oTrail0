package in.otrail;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;

/** Base-app toggle, independent of downloaded modules. */
final class WtfButton extends Button {
  WtfButton(Context context,boolean enabled,Runnable toggle){super(context);setText(UiConfig.WTF_ICON_TEXT);setTextSize(UiConfig.WTF_ICON_SIZE_SP);setTypeface(Typeface.create(UiConfig.HEADING_FONT,Typeface.BOLD));setAllCaps(false);setMinWidth(0);setMinimumWidth(0);setPadding(0,0,0,0);setTooltipText(UiConfig.WTF_LABEL);setOnClickListener(v->toggle.run());update(enabled);}
  void update(boolean enabled){setSelected(enabled);setActivated(enabled);setTextColor(enabled?UiConfig.WTF_ENABLED_TEXT:UiConfig.WTF_DISABLED_TEXT);setContentDescription(UiConfig.WTF_LABEL+(enabled?" enabled · tap to disable":" disabled · tap to enable"));setStateDescription(enabled?"Enabled":"Disabled");
    GradientDrawable background=new GradientDrawable();background.setColor(enabled?UiConfig.WTF_ENABLED_BACKGROUND:UiConfig.WTF_DISABLED_BACKGROUND);background.setCornerRadius(12*getResources().getDisplayMetrics().density);background.setStroke(Math.round(2*getResources().getDisplayMetrics().density),enabled?UiConfig.WTF_ENABLED_TEXT:UiConfig.WTF_DISABLED_TEXT);setBackground(background);
  }
}
