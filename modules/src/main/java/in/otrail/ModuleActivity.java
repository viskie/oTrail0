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
  private static ModuleV1 retainedModule;
  private static String retainedId="",retainedVersion="";
  private ModuleV1 module;private ModuleStore store;private SignedModuleCatalog.Release release;private View floatingBack;
  @Override public void onCreate(Bundle state){super.onCreate(state);store=new ModuleStore(this);
    try{
      String id=getIntent().getStringExtra("id"),version=store.active(id);
      for(SignedModuleCatalog.Release r:store.catalog(false))if(r.id().equals(id)&&r.version().equals(version))release=r;
      if(release==null)throw new IOException("Activate a module version first");
      log("open", "version " + release.version());
      if(retainedModule!=null&&id.equals(retainedId)&&version.equals(retainedVersion)){
        module=retainedModule;retainedModule=null;log("reuse", "retained module instance");
      }else{
        releaseRetained(id);module=store.load(release);
      }
      View view=module.create(this,this);setContentView(view);
      log("create.complete", "");
      addFloatingBack();
    }catch(Exception|LinkageError e){failed(e);}
  }
  private void failed(Throwable e){
    log("failure", e.getClass().getSimpleName() + ": " + e.getMessage());
    CrashReports.save(this,"Downloaded module",e);Toast.makeText(this,"Module stopped: "+e.getMessage(),Toast.LENGTH_LONG).show();
    if(release!=null){try{store.rollback(release.id());}catch(Exception ignored){store.deactivate(release.id());}}finish();
  }
  private void photoAccess(){if(release==null||!release.id().equals("mod2"))throw new SecurityException("CAM photo capability required");}
  @Override public String photoContext(long time)throws Exception{photoAccess();try(TrailDb db=new TrailDb(this)){return PhotoIndex.context(db,time).toString();}}
  @Override public void savePhoto(String json)throws Exception{photoAccess();try(TrailDb db=new TrailDb(this)){PhotoIndex.save(db,json);}}
  @Override public String photos(long stop)throws Exception{photoAccess();try(TrailDb db=new TrailDb(this)){return PhotoIndex.list(db,stop);}}
  @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);log("permission.result","request "+request);if(module!=null)try{module.permissionResult(request,permissions,grants);}catch(RuntimeException e){failed(e);}}
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
  @Override public void cancelTask(){log("task.cancel","");startService(new Intent(this,ModuleTaskService.class).setAction("cancel"));}
  @Override public void startTask(String request) throws Exception {
    String action="";
    try{action=new org.json.JSONObject(request).optString("action","");}catch(Exception ignored){}
    log("task.request", action.isEmpty()?"request "+request.length()+" chars":"action "+action+" · "+request.length()+" chars");
    startTask(release.id(),release.version(),request);
  }
  @Override public File moduleDirectory(String id) throws Exception {
    String safe=moduleId(id);
    File directory=new File(getFilesDir(),"modules/data/"+safe);
    directory.mkdirs();
    return directory;
  }
  @Override public String moduleVersion(String id) throws Exception {
    return store.active(moduleId(id));
  }
  @Override public void startModuleTask(String id,String request) throws Exception {
    String safe=moduleId(id);
    String version=store.active(safe);
    if(version.isBlank())throw new IOException("Activate "+safe+" before starting its task");
    startTask(safe,version,request);
  }
  private void startTask(String id,String version,String request) throws Exception {
    if(request.length()>500000)throw new IOException("Module task too large");
    ModuleLog.event(id,"task.start","version "+version);
    startForegroundService(new Intent(this,ModuleTaskService.class)
        .putExtra("id",id)
        .putExtra("version",version)
        .putExtra("request",request));
  }
  @Override public String moduleTaskStatus(String id) throws Exception {
    return ModuleTaskService.readStatus(this,moduleId(id));
  }
  private static String moduleId(String id) throws IOException {
    if(id==null||!id.matches("[a-z][a-z0-9-]{0,63}"))throw new IOException("Invalid module ID");
    return id;
  }
  @Override public void installBuiltApk(File file) throws Exception {log("apk.install",file==null?"":file.getName());AppUpdater.prepareBuiltApk(this,file);AppUpdater.install(this,new File(getFilesDir(),"updates/verified.apk"));}
  @Override public void log(String event,String detail){ModuleLog.event(release==null?"module-host":release.id(),event,detail);}
  @Override public String logs(int limit){return ModuleLog.readJson(limit);}
  @Override public String crashLog(){return CrashReports.read(this);}
  @Override public void clearLogs(){ModuleLog.clear();}
  @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);log("activity.result","request "+request+" result "+result);if(module!=null)try{module.activityResult(request,result,data);}catch(RuntimeException e){failed(e);}}
  @Override protected void onResume(){super.onResume();log("resume","");if(module!=null)try{module.resume();}catch(RuntimeException e){failed(e);}}
  @Override protected void onPause(){log("pause","");if(module!=null)try{module.pause();}catch(RuntimeException e){CrashReports.save(this,"Module pause",e);}super.onPause();}
  @Override public void onBackPressed(){if(module!=null&&module.backPressed())return;super.onBackPressed();}
  @Override protected void onDestroy(){
    log("close","");
    if(module!=null){
      if(release!=null&&"mod3".equals(release.id())){
        try{module.detach();retainedModule=module;retainedId=release.id();retainedVersion=release.version();}
        catch(RuntimeException e){CrashReports.save(this,"Module detach",e);}
      }else try{module.close();}catch(RuntimeException e){CrashReports.save(this,"Module close",e);}
      module=null;
    }
    removeFloatingBack();super.onDestroy();
  }
  static void releaseRetained(String id){
    if(retainedModule==null||!id.equals(retainedId))return;
    try{retainedModule.close();}catch(RuntimeException ignored){}
    retainedModule=null;retainedId="";retainedVersion="";
  }
  @Override public void keepProcessAlive(boolean active){
    if(active)ModuleSessionService.start(this);else ModuleSessionService.stop(this);
  }
  private void addFloatingBack(){
    View decor=getWindow().getDecorView();
    if(!(decor instanceof android.view.ViewGroup))return;
    if(floatingBack!=null)return;
    android.widget.Button button=new android.widget.Button(this);
    button.setText("←");
    button.setTextSize(14);
    button.setTextColor(0xffffffff);
    button.setAllCaps(false);
    android.graphics.drawable.GradientDrawable frame=new android.graphics.drawable.GradientDrawable();
    frame.setColor(0xaa111111);
    frame.setCornerRadius(dp(21));
    button.setBackground(frame);
    button.setElevation(dp(6));
    button.setOnClickListener(v->onBackPressed());
    android.widget.FrameLayout.LayoutParams params=new android.widget.FrameLayout.LayoutParams(dp(42),dp(42));
    params.gravity=android.view.Gravity.TOP|android.view.Gravity.START;
    params.leftMargin=dp(8);params.topMargin=dp(8);
    button.setLayoutParams(params);
    ((android.view.ViewGroup)decor).addView(button);
    floatingBack=button;
  }
  private void removeFloatingBack(){
    if(floatingBack==null)return;
    View parent=(View)floatingBack.getParent();
    if(parent instanceof android.view.ViewGroup)((android.view.ViewGroup)parent).removeView(floatingBack);
    floatingBack=null;
  }
  private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
  @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus){getWindow().setDecorFitsSystemWindows(false);android.view.WindowInsetsController controller=getWindow().getDecorView().getWindowInsetsController();if(controller!=null){controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);controller.hide(android.view.WindowInsets.Type.systemBars());}}}

}
