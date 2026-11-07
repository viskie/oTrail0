package in.otrail;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Owns extension views/lifecycle; core GPS recording never depends on module enable flags. */
final class ModuleHost implements AutoCloseable {
  private final LinearLayout container;
  private final List<TrailModule> active=new ArrayList<>();
  private final Activity activity;
  ModuleHost(Activity activity){
    this.activity=activity;container=new LinearLayout(activity);container.setOrientation(LinearLayout.VERTICAL);
    SharedPreferences prefs=activity.getSharedPreferences("modules",Context.MODE_PRIVATE);
    for(ModuleCatalog.Entry entry:ModuleCatalog.ENTRIES){
      if(!entry.available()||!prefs.getBoolean(entry.id(),false))continue;
      TrailModule module=null;
      try{
        module=ModuleCatalog.create(entry.id());View view=module.createView(activity);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.topMargin=params.bottomMargin=Math.round(3*activity.getResources().getDisplayMetrics().density);
        container.addView(view,params);active.add(module);
      }catch(RuntimeException error){
        if(module!=null)try{module.close();}catch(RuntimeException ignored){}
        failure(error);
      }
    }
    container.setVisibility(active.isEmpty()?View.GONE:View.VISIBLE);
  }
  View view(){return container;}
  void update(TrailModule.Snapshot snapshot){
    for(TrailModule module:new ArrayList<>(active))try{module.update(snapshot);}
    catch(RuntimeException error){active.remove(module);try{module.close();}catch(RuntimeException ignored){}failure(error);}
  }
  private void failure(RuntimeException error){CrashReports.save(activity,"Extension module",error);}
  @Override public void close(){for(TrailModule module:active)try{module.close();}catch(RuntimeException error){failure(error);}active.clear();}
  static void settings(Activity activity){
    SharedPreferences prefs=activity.getSharedPreferences("modules",Context.MODE_PRIVATE);
    String[] names=ModuleCatalog.ENTRIES.stream().map(e->UiConfig.text(e.titleKey())+
        (e.available()?"":" · "+UiConfig.text("module_planned"))).toArray(String[]::new);
    new AppDialogs(activity).setTitle(UiConfig.text("modules_title"))
        .setItems(names,(dialog,index)->{
          ModuleCatalog.Entry entry=ModuleCatalog.ENTRIES.get(index);
          android.app.AlertDialog.Builder details=new AppDialogs(activity).setTitle(UiConfig.text(entry.titleKey()))
              .setMessage(UiConfig.text(entry.summaryKey())+"\n\n"+UiConfig.text(entry.available()?"module_bundled":"module_plan_only"))
              .setNegativeButton(UiConfig.text("close"),null);
          if(entry.available())details.setPositiveButton(UiConfig.text(prefs.getBoolean(entry.id(),false)?"module_disable":"module_enable"),
              (d,w)->prefs.edit().putBoolean(entry.id(),!prefs.getBoolean(entry.id(),false)).apply());
          details.show();
        }).setNegativeButton(UiConfig.text("close"),null).show();
  }
}
