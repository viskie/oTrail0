package in.otrail;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Compact player pinned directly above total trail statistics. */
final class ResonationsWidget extends LinearLayout {
  static final int PICK_FILES=74;
  private final Activity activity;private final TextView title;private final LinearLayout names;private final Button play;
  private final Runnable listener=this::refresh;
 ResonationsWidget(Activity activity) {
    super(activity);
    this.activity = activity;

    setOrientation(VERTICAL);
    setPadding(dp(5), dp(3), dp(5), dp(3));

    AppStyle.decoratePanel(this, UiConfig.CYAN);

    // Black background with cyan border
    android.graphics.drawable.GradientDrawable bg =
        new android.graphics.drawable.GradientDrawable();

    bg.setColor(0xff000000);
    bg.setCornerRadius(dp(7));
    bg.setStroke(dp(1), UiConfig.CYAN);

    setBackground(bg);

    title = new TextView(activity);
    AppStyle.text(title);
    title.setTextSize(UiConfig.RESONATIONS_TITLE_SP);
    title.setMaxLines(2);
    title.setEllipsize(
        android.text.TextUtils.TruncateAt.END
    );
    title.setTextColor(UiConfig.RESONATIONS_TEXT);
    addView(title);

    names = new LinearLayout(activity);
    names.setOrientation(VERTICAL);
    addView(names);
    LinearLayout controls=new LinearLayout(activity);
    Button choose=button(UiConfig.text("resonations_files"),this::pick);controls.addView(choose,new LayoutParams(0,dp(22),1));
    play=button(UiConfig.text("resonations_play"),()->{Intent intent=new Intent(activity,ResonationsService.class).setAction(ResonationsService.active?ResonationsService.PAUSE:ResonationsService.PLAY);try{if(ResonationsService.active)activity.startService(intent);else activity.startForegroundService(intent);}catch(RuntimeException error){Toast.makeText(activity,"Cannot start audio: "+error.getMessage(),Toast.LENGTH_LONG).show();}});controls.addView(play,new LayoutParams(0,dp(22),1));
    Button clear=button(UiConfig.text("resonations_clear"),()->{activity.stopService(new Intent(activity,ResonationsService.class));ResonationsService.prefs(activity).edit().putString("playlist","[]").putInt("position",0).putInt("index",0).apply();ResonationsService.message="";refresh();});controls.addView(clear,new LayoutParams(0,dp(22),1));
    for(int i=0;i<controls.getChildCount();i++){LayoutParams p=(LayoutParams)controls.getChildAt(i).getLayoutParams();p.setMargins(dp(2),dp(2),dp(2),0);}addView(controls);refresh();
  }
  private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
  private Button button(String text,Runnable action){Button b=new Button(activity);b.setText(text);AppStyle.button(b);b.setMinHeight(0);b.setMinimumHeight(0);b.setOnClickListener(v->action.run());return b;}
  private void pick(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true).putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/*","video/mp4","video/3gpp","application/ogg"}).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);activity.startActivityForResult(intent,PICK_FILES);}
  void selected(Intent data){
    LinkedHashSet<Uri> uris=new LinkedHashSet<>();if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());else if(data.getData()!=null)uris.add(data.getData());
    List<ResonationsPlaylist.Track> selected=new ArrayList<>();for(Uri uri:uris){if(!"content".equals(uri.getScheme()))continue;try{activity.getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException error){Toast.makeText(activity,"Cannot retain this file · choose from local storage",Toast.LENGTH_LONG).show();continue;}String name="Audio";try(Cursor c=activity.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))name=c.getString(0);}catch(RuntimeException ignored){}selected.add(new ResonationsPlaylist.Track(uri.toString(),name));}
    if(selected.isEmpty())return;activity.stopService(new Intent(activity,ResonationsService.class));ResonationsService.prefs(activity).edit().putString("playlist",ResonationsPlaylist.encode(selected)).putInt("index",0).putInt("position",0).apply();ResonationsService.message="";refresh();
  }
  private void refresh(){List<ResonationsPlaylist.Track> tracks=ResonationsService.tracks(activity);title.setText(UiConfig.text("resonations_title")+(ResonationsService.active?" · ↻":""));title.setContentDescription(ResonationsService.message);names.removeAllViews();List<String> lines=ResonationsPlaylist.visibleNames(tracks);if(lines.isEmpty())lines=List.of(UiConfig.text("resonations_empty"));for(String name:lines){TextView line=new TextView(activity);line.setText(name);line.setTextColor(UiConfig.RESONATIONS_TEXT);line.setTextSize(UiConfig.RESONATIONS_TEXT_SP);line.setSingleLine(true);line.setEllipsize(android.text.TextUtils.TruncateAt.END);line.setIncludeFontPadding(false);names.addView(line);}play.setText(UiConfig.text(ResonationsService.active?"resonations_pause":"resonations_play"));play.setEnabled(!tracks.isEmpty());if(!ResonationsService.message.isBlank()&&!ResonationsService.active)title.setText(UiConfig.text("resonations_title")+" · "+ResonationsService.message);}
  @Override protected void onAttachedToWindow(){super.onAttachedToWindow();ResonationsService.listeners.add(listener);refresh();}
  @Override protected void onDetachedFromWindow(){ResonationsService.listeners.remove(listener);super.onDetachedFromWindow();}
}
