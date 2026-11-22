package in.otrail;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** All module management UI belongs to oTraiL, in its separate modules process. */
public final class ModuleManagerActivity extends Activity {
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private ModuleStore store;private LinearLayout cards;private TextView status;private ProgressBar progress;
  private final Set<String> expanded=new LinkedHashSet<>();
  @Override public void onCreate(Bundle state){super.onCreate(state);store=new ModuleStore(this);
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(12,12,12,12);root.setBackground(AppStyle.canvas());
    root.addView(button("← Back to settings",this::finish));root.addView(label("Extension modules",20));
    root.addView(label("Repository: viskie/oTrail0 · Signed catalog · API 1",12));
    root.addView(button("Refresh versions from GitHub",()->refresh(true)));
    status=label("Loading installed modules…",12);root.addView(status);progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);root.addView(progress);
    ScrollView scroll=new ScrollView(this);cards=new LinearLayout(this);cards.setOrientation(LinearLayout.VERTICAL);scroll.addView(cards);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    setContentView(root);refresh(false);
  }
  private TextView label(String text,int size){TextView v=new TextView(this);AppStyle.text(v);v.setTextSize(size);v.setTextColor(UiConfig.INK);v.setText(text);v.setPadding(8,8,8,8);return v;}
  private Button button(String text,Runnable action){Button b=new Button(this);b.setText(text);AppStyle.button(b);b.setOnClickListener(v->action.run());return b;}
  private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
  private void ui(Runnable r){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())r.run();});}
  private void refresh(boolean online){worker.execute(()->{try{
    List<SignedModuleCatalog.Release> versions=store.catalog(online);
    ui(()->{
      cards.removeAllViews();
      status.setText(versions.isEmpty()?"No cached catalog. Refresh from GitHub.":"Catalog verified · "+versions.size()+" version(s) · downloads use Wi-Fi or cellular");
      // Group all versions by module id so each module is a single collapsed card.
      Map<String,List<SignedModuleCatalog.Release>> grouped=new LinkedHashMap<>();
      for(SignedModuleCatalog.Release r:versions)grouped.computeIfAbsent(r.id(),k->new ArrayList<>()).add(r);
      for(Map.Entry<String,List<SignedModuleCatalog.Release>> entry:grouped.entrySet()){
        List<SignedModuleCatalog.Release> list=entry.getValue();
        String id=entry.getKey();
        SignedModuleCatalog.Release activeVersion=null;
        for(SignedModuleCatalog.Release r:list)if(store.active(id).equals(r.version()))activeVersion=r;
        boolean installed=store.installed(list.get(0));
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);AppStyle.decoratePanel(card,activeVersion!=null?UiConfig.LIME:UiConfig.CYAN);
        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.HORIZONTAL);header.setGravity(Gravity.CENTER_VERTICAL);
        String title=list.get(0).name()+" · "+(activeVersion!=null?activeVersion.version():"not active");
        TextView name=label(title,16);name.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));header.addView(name);
        TextView meta=label(list.size()+" version"+(list.size()==1?"":"s")+(installed?" · installed":" · available"),11);meta.setTextColor(UiConfig.MUTED);header.addView(meta);
        Button toggle=button(expanded.contains(id)?"▾":"▸",()->{if(expanded.contains(id))expanded.remove(id);else expanded.add(id);refresh(false);});
        toggle.setLayoutParams(new LinearLayout.LayoutParams(-2,-2));header.addView(toggle);
        card.addView(header);
        // Body: one row per version, shown only when expanded.
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setVisibility(expanded.contains(id)?View.VISIBLE:View.GONE);
        for(SignedModuleCatalog.Release r:list){
          boolean active=store.active(r.id()).equals(r.version());
          LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(6),dp(6),dp(6),dp(6));
          row.addView(label(r.version()+(active?" · ACTIVE":store.installed(r)?" · Installed":" · Available"),14));
          row.addView(label(String.format(Locale.US,"%.2f MB · host >= %d · Android API >= %d · module API %d\n%s",r.size()/1048576.0,r.minApp(),r.minSdk(),r.api(),r.notes()),11));
          if(!store.installed(r))row.addView(button("Download & install module",()->password(r)));
          else{
            row.addView(button(active?"Open module":"Activate this version",()->{if(active)open(r);else activate(r);}));
            if(active){row.addView(button("Deactivate",()->{store.deactivate(r.id());refresh(false);}));row.addView(button("Roll back version",()->worker.execute(()->{try{store.rollback(r.id());ui(()->refresh(false));}catch(Exception e){error(e);}})));}
          }
          body.addView(row);
        }
        card.addView(body);
        LinearLayout.LayoutParams margins=new LinearLayout.LayoutParams(-1,-2);margins.setMargins(0,10,0,10);cards.addView(card,margins);
      }
    });
  }catch(Exception e){error(e);}});}

  private void password(SignedModuleCatalog.Release r){
    if(r.installHash().isEmpty()){download(r,new char[0]);return;}
    EditText input=new EditText(this);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    new AppDialogs(this).setTitle("Install "+r.name()).setMessage("Enter this module's installation password.").setView(input)
      .setPositiveButton("Install",(d,w)->{char[] password=input.getText().toString().toCharArray();input.setText("");download(r,password);})
      .setNegativeButton("Cancel",null).show();
  }
  private void download(SignedModuleCatalog.Release r,char[] password){status.setText("Downloading "+r.name()+" "+r.version());progress.setProgress(0);worker.execute(()->{try{
    store.install(r,password,p->ui(()->{progress.setProgress(p);status.setText("Downloading "+r.name()+" · "+p+"%");}));if(r.id().equals("mod3"))store.activate(r);ui(()->{refresh(false);if(r.id().equals("mod3"))open(r);});
  }catch(Exception e){error(e);}});}
  private void activate(SignedModuleCatalog.Release r){worker.execute(()->{try{store.activate(r);ui(()->{refresh(false);open(r);});}catch(Exception e){error(e);}});}
  private void open(SignedModuleCatalog.Release r){startActivity(new Intent(this,ModuleActivity.class).putExtra("id",r.id()));}
  private void error(Exception e){ui(()->{status.setText("Module operation failed: "+e.getMessage());progress.setProgress(0);});CrashReports.save(this,"Module manager",e);}
  @Override protected void onResume(){super.onResume();if(store!=null)refresh(false);}
  @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
  @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus){getWindow().setDecorFitsSystemWindows(false);android.view.WindowInsetsController controller=getWindow().getDecorView().getWindowInsetsController();if(controller!=null){controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);controller.hide(android.view.WindowInsets.Type.systemBars());}}}

}
