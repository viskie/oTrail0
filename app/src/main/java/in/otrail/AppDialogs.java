package in.otrail;

import android.app.AlertDialog;
import android.content.Context;

/** Apply configured text colors after Android inflates native dialog/list layouts. */
final class AppDialogs extends AlertDialog.Builder {
  AppDialogs(Context context){super(context);}
  @Override public AlertDialog create(){
    AlertDialog dialog=super.create();
    dialog.setOnShowListener(ignored->{
      if(dialog.getWindow()!=null){
        AppStyle.nativeTextTree(dialog.getWindow().getDecorView());
        dialog.getWindow().setBackgroundDrawable(AppStyle.panel(dialog.getWindow().getDecorView(),UiConfig.PURPLE));
      }
      int title=dialog.getContext().getResources().getIdentifier("alertTitle","id","android");
      android.widget.TextView heading=dialog.findViewById(title);
      if(heading!=null){heading.setTextColor(UiConfig.HEADING);heading.setTextSize(UiConfig.HEADING_SIZE_SP*UiConfig.TEXT_SCALE);}
    });
    return dialog;
  }
}
