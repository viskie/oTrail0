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
    activity.startActivity(new android.content.Intent(activity,ModuleManagerActivity.class));
  }
}
