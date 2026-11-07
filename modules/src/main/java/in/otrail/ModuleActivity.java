package in.otrail;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.View;
import android.widget.Toast;
import in.otrail.modules.ModuleV1;
import java.io.*;

public final class ModuleActivity extends Activity implements ModuleV1.Host {
  private ModuleV1 module;private ModuleStore store;private SignedModuleCatalog.Release release;
  @Override public void onCreate(Bundle state){super.onCreate(state);store=new ModuleStore(this);
    try{
      String id=getIntent().getStringExtra("id"),version=store.active(id);
      for(SignedModuleCatalog.Release r:store.catalog(false))if(r.id().equals(id)&&r.version().equals(version))release=r;
      if(release==null)throw new IOException("Activate a module version first");
      module=store.load(release);View view=module.create(this,this);setContentView(view);
    }catch(Exception|LinkageError e){failed(e);}
  }
  private void failed(Throwable e){
    CrashReports.save(this,"Downloaded module",e);Toast.makeText(this,"Module stopped: "+e.getMessage(),Toast.LENGTH_LONG).show();
    if(release!=null){try{store.rollback(release.id());}catch(Exception ignored){store.deactivate(release.id());}}finish();
  }
  @Override public File packageFile(){return store.file(release);}
  @Override public File privateDirectory(){File f=new File(getFilesDir(),"modules/data/"+release.id());f.mkdirs();return f;}
  @Override public String taskStatus(){return ModuleTaskService.readStatus(this,release.id());}
  @Override public String toolchains(boolean refresh)throws Exception{return store.toolchains(refresh);}
  @Override public String selectedToolchain()throws Exception{return store.selectedToolchain(release.id()).toString();}
  @Override public void selectToolchain(String id)throws Exception{
    org.json.JSONArray list=new org.json.JSONArray(store.toolchains(false));boolean found=false;for(int i=0;i<list.length();i++)if(list.getJSONObject(i).getString("id").equals(id))found=true;
    if(!found)throw new IOException("Toolchain is not in the verified catalog");
    android.util.AtomicFile atomic=new android.util.AtomicFile(new File(privateDirectory(),"selected-toolchain"));FileOutputStream out=atomic.startWrite();try{out.write(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));atomic.finishWrite(out);}catch(Exception e){atomic.failWrite(out);throw e;}
  }
  @Override public void cancelTask(){startService(new Intent(this,ModuleTaskService.class).setAction("cancel"));}
  @Override public void startTask(String request) throws Exception {
    if(request.length()>500000)throw new IOException("Module task too large");
    startForegroundService(new Intent(this,ModuleTaskService.class).putExtra("id",release.id()).putExtra("version",release.version()).putExtra("request",request));
  }
  @Override public void installBuiltApk(File file) throws Exception {AppUpdater.prepareBuiltApk(this,file);AppUpdater.install(this,new File(getFilesDir(),"updates/verified.apk"));}
  @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(module!=null)try{module.activityResult(request,result,data);}catch(RuntimeException e){failed(e);}}
  @Override protected void onResume(){super.onResume();if(module!=null)try{module.resume();}catch(RuntimeException e){failed(e);}}
  @Override protected void onPause(){if(module!=null)try{module.pause();}catch(RuntimeException e){CrashReports.save(this,"Module pause",e);}super.onPause();}
  @Override protected void onDestroy(){if(module!=null)try{module.close();}catch(RuntimeException e){CrashReports.save(this,"Module close",e);}super.onDestroy();}
  @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus){getWindow().setDecorFitsSystemWindows(false);android.view.WindowInsetsController controller=getWindow().getDecorView().getWindowInsetsController();if(controller!=null){controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);controller.hide(android.view.WindowInsets.Type.systemBars());}}}

}
