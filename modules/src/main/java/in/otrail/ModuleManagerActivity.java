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
  @Override public void onCreate(Bundle state){super.onCreate(state);store=new ModuleStore(this);ModuleLog.event("manager","open","");
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(12,12,12,12);root.setBackground(AppStyle.canvas());
    root.addView(button("← Back to settings",this::finish));root.addView(label("Extension modules",20));
    root.addView(label("Repository: viskie/oTrail0 · Signed catalog · API 1",12));
    root.addView(button("Refresh versions from GitHub",()->refresh(true)));
    status=label("Loading installed modules…",12);root.addView(status);progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);root.addView(progress);
    ScrollView scroll=new ScrollView(this);cards=new LinearLayout(this);cards.setOrientation(LinearLayout.VERTICAL);scroll.addView(cards);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    setContentView(root);addFloatingBack();refresh(false);
  }
  private TextView label(String text,int size){TextView v=new TextView(this);AppStyle.text(v);v.setTextSize(size);v.setTextColor(UiConfig.INK);v.setText(text);v.setPadding(8,8,8,8);return v;}
  private Button button(String text,Runnable action){Button b=new Button(this);b.setText(text);AppStyle.button(b);b.setOnClickListener(v->action.run());return b;}
  private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
  private void ui(Runnable r){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())r.run();});}
  private void refresh(boolean online){worker.execute(()->{try{
    if(online)ModuleLog.event("manager","catalog.refresh","");
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
        String activeId=store.active(id);
        SignedModuleCatalog.Release activeVersion=null,newestNew=null;boolean installed=false;
        for(SignedModuleCatalog.Release r:list){
          if(activeId.equals(r.version()))activeVersion=r;
          if(store.installed(r))installed=true;
          boolean newer=activeId.isEmpty()||compareVersions(r.version(),activeId)>0;
          if(!activeId.equals(r.version())&&newer&&(newestNew==null||compareVersions(r.version(),newestNew.version())>0))newestNew=r;
        }
        final SignedModuleCatalog.Release newVersion=newestNew;
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);AppStyle.decoratePanel(card,activeVersion!=null?UiConfig.LIME:UiConfig.CYAN);
        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.HORIZONTAL);header.setGravity(Gravity.CENTER_VERTICAL);
        String title=list.get(0).name()+" · "+(activeVersion!=null?activeVersion.version():"not active");
        TextView name=label(title,16);name.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));header.addView(name);
        String metaText=list.size()+" version"+(list.size()==1?"":"s")+" · "+(newVersion!=null?"NEW "+newVersion.version():installed?"installed":"available");
        TextView meta=label(metaText,11);meta.setTextColor(newVersion!=null?UiConfig.LIME:UiConfig.MUTED);if(newVersion!=null)meta.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);header.addView(meta);
        // Collapsing is a local view change: it must never wait on the catalog or the network.
        final LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setVisibility(expanded.contains(id)?View.VISIBLE:View.GONE);
        final Button toggle=button(expanded.contains(id)?"▾":"▸",()->{});
        toggle.setLayoutParams(new LinearLayout.LayoutParams(-2,-2));header.addView(toggle);
        final String moduleId=id;
        toggle.setOnClickListener(v->setExpanded(moduleId,body,toggle));
        header.setOnClickListener(v->setExpanded(moduleId,body,toggle));
        final SignedModuleCatalog.Release openVersion=activeVersion;
        if(openVersion!=null){
          // Open the active (latest installed) version without expanding the card first.
          Button open=button("Open",()->open(openVersion));
          open.setLayoutParams(new LinearLayout.LayoutParams(-2,-2));header.addView(open);
        }
        card.addView(header);
        // Body: one row per version, shown only when expanded.
        for(SignedModuleCatalog.Release r:list){
          boolean active=store.active(r.id()).equals(r.version());
          boolean fresh=newestNew!=null&&r.version().equals(newestNew.version());
          String rowState=active?"ACTIVE":fresh?(store.installed(r)?"NEW · Downloaded":"NEW · Available"):store.installed(r)?"Installed":"Available";
          LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(6),dp(6),dp(6),dp(6));
          row.addView(label(r.version()+" · "+rowState,14));
          row.addView(label(String.format(Locale.US,"%.2f MB · host >= %d · Android API >= %d · module API %d\n%s",r.size()/1048576.0,r.minApp(),r.minSdk(),r.api(),r.notes()),11));
          if(!store.installed(r))row.addView(button("Download & install module",()->password(r)));
          else{
            row.addView(button(active?"Open module":"Activate this version",()->{if(active)open(r);else activate(r);}));
            if(active){row.addView(button("Deactivate",()->{ModuleLog.event(r.id(),"deactivate",r.version());store.deactivate(r.id());refresh(false);}));row.addView(button("Roll back version",()->worker.execute(()->{try{ModuleLog.event(r.id(),"rollback","from "+r.version());store.rollback(r.id());ui(()->refresh(false));}catch(Exception e){error(e);}})));}
            else row.addView(button("Delete downloaded version",()->confirmDelete(r)));
          }
          body.addView(row);
        }
        card.addView(body);
        LinearLayout.LayoutParams margins=new LinearLayout.LayoutParams(-1,-2);margins.setMargins(0,10,0,10);cards.addView(card,margins);
      }
    });
  }catch(Exception e){error(e);}});}

  private void setExpanded(String id,LinearLayout body,Button toggle){
    boolean show=body.getVisibility()!=View.VISIBLE;
    body.setVisibility(show?View.VISIBLE:View.GONE);
    if(show)expanded.add(id);else expanded.remove(id);
    toggle.setText(show?"▾":"▸");
  }

  private void confirmDelete(SignedModuleCatalog.Release r){
    new AppDialogs(this).setTitle("Delete "+r.name()+" "+r.version()+"?")
      .setMessage("Removes this downloaded module package from the phone. The active version and module data are kept.")
      .setPositiveButton("Delete",(d,w)->deleteOne(r)).setNegativeButton("Cancel",null).show();
  }
  private void deleteOne(SignedModuleCatalog.Release r){status.setText("Deleting "+r.name()+" "+r.version()+"…");ModuleLog.event(r.id(),"delete",r.version());worker.execute(()->{try{store.deleteVersion(r);ui(()->refresh(false));}catch(Exception e){error(e);}});}
  /** Numeric component comparison so 1.1.10 ranks above 1.1.9. */
  private static int compareVersions(String left,String right){
    String[] a=left.split("[^0-9]+"),b=right.split("[^0-9]+");int count=Math.max(a.length,b.length);
    for(int i=0;i<count;i++){int x=versionPart(a,i),y=versionPart(b,i);if(x!=y)return Integer.compare(x,y);}
    return left.compareTo(right);
  }
  private static int versionPart(String[] parts,int index){if(index>=parts.length||parts[index].isEmpty())return 0;try{return Integer.parseInt(parts[index]);}catch(NumberFormatException ignored){return 0;}}

  private void password(SignedModuleCatalog.Release r){
    if(r.installHash().isEmpty()){download(r,new char[0]);return;}
    EditText input=new EditText(this);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    new AppDialogs(this).setTitle("Install "+r.name()).setMessage("Enter this module's installation password.").setView(input)
      .setPositiveButton("Install",(d,w)->{char[] password=input.getText().toString().toCharArray();input.setText("");download(r,password);})
      .setNegativeButton("Cancel",null).show();
  }
  private void download(SignedModuleCatalog.Release r,char[] password){status.setText("Downloading "+r.name()+" "+r.version());progress.setProgress(0);ModuleLog.event(r.id(),"install.start","version "+r.version());worker.execute(()->{try{
    store.install(r,password,p->ui(()->{progress.setProgress(p);status.setText("Downloading "+r.name()+" · "+p+"%");}));ModuleLog.event(r.id(),"install.complete","version "+r.version());if(r.id().equals("mod3"))store.activate(r);ui(()->{refresh(false);if(r.id().equals("mod3"))open(r);});
  }catch(Exception e){error(e);}});}
  private void activate(SignedModuleCatalog.Release r){ModuleLog.event(r.id(),"activate",r.version());worker.execute(()->{try{store.activate(r);ui(()->{refresh(false);open(r);});}catch(Exception e){error(e);}});}
  private void open(SignedModuleCatalog.Release r){ModuleLog.event(r.id(),"launcher.open",r.version());startActivity(new Intent(this,ModuleActivity.class).putExtra("id",r.id()));}
  private void error(Exception e){
    if(worker.isShutdown() || Thread.currentThread().isInterrupted()) {
      ModuleLog.event("manager","cancelled",e.toString());
      return;
    }
    ui(()->{status.setText("Module operation failed: "+e.getMessage());progress.setProgress(0);});ModuleLog.event("manager","failure",e.getClass().getSimpleName()+": "+e.getMessage());if (!(e instanceof java.io.IOException)) CrashReports.save(this,"Module manager",e);}
  @Override protected void onResume(){super.onResume();if(store!=null)refresh(false);}
  @Override protected void onDestroy(){removeFloatingBack();worker.shutdownNow();super.onDestroy();}
  private View floatingBack;
  private void addFloatingBack(){
    View decor=getWindow().getDecorView();
    if(!(decor instanceof ViewGroup))return;
    if(floatingBack!=null)return;
    Button button=new Button(this);
    button.setText("←");
    button.setTextSize(14);
    button.setTextColor(0xffffffff);
    button.setAllCaps(false);
    android.graphics.drawable.GradientDrawable frame=new android.graphics.drawable.GradientDrawable();
    frame.setColor(0xaa111111);
    frame.setCornerRadius(dp(21));
    button.setBackground(frame);
    button.setElevation(dp(6));
    button.setOnClickListener(v->finish());
    android.widget.FrameLayout.LayoutParams params=new android.widget.FrameLayout.LayoutParams(dp(42),dp(42));
    params.gravity=android.view.Gravity.TOP|android.view.Gravity.START;
    params.leftMargin=dp(8);params.topMargin=dp(8);
    button.setLayoutParams(params);
    ((ViewGroup)decor).addView(button);
    floatingBack=button;
  }
  private void removeFloatingBack(){
    if(floatingBack==null)return;
    View parent=(View)floatingBack.getParent();
    if(parent instanceof ViewGroup)((ViewGroup)parent).removeView(floatingBack);
    floatingBack=null;
  }
  @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus){getWindow().setDecorFitsSystemWindows(false);android.view.WindowInsetsController controller=getWindow().getDecorView().getWindowInsetsController();if(controller!=null){controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);controller.hide(android.view.WindowInsets.Type.systemBars());}}}

}
