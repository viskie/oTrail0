package in.otrail;

import android.app.*;
import android.content.*;
import android.os.*;
import in.otrail.modules.ModuleV1;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import org.json.JSONObject;

/** In-app, separate-process compiler worker. No external app, shell, server or trail DB access. */
public final class ModuleTaskService extends Service {
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private volatile boolean busy,cancelled;
  private String moduleId="mod1";
  @Override public IBinder onBind(Intent i){return null;}
  @Override public int onStartCommand(Intent intent,int flags,int startId){
    if(intent!=null&&"cancel".equals(intent.getAction())){cancelled=true;if(!busy)stopSelf();return START_NOT_STICKY;}
    NotificationManager manager=getSystemService(NotificationManager.class);
    manager.createNotificationChannel(new NotificationChannel("module_tasks","Module build tools",NotificationManager.IMPORTANCE_LOW));
    startForeground(2403,new Notification.Builder(this,"module_tasks").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("oTraiL module tools").setContentText("Running on this phone").setOngoing(true).build());
    if(busy)return START_NOT_STICKY;if(intent==null){stopSelf();return START_NOT_STICKY;}
    moduleId=intent.getStringExtra("id");String version=intent.getStringExtra("version"),request=intent.getStringExtra("request");
    busy=true;cancelled=false;save("Starting",0,"",true);
    worker.execute(()->{ModuleV1 module=null;try{
      ModuleStore store=new ModuleStore(this);SignedModuleCatalog.Release release=null;
      for(SignedModuleCatalog.Release r:store.catalog(false))if(r.id().equals(moduleId)&&r.version().equals(version))release=r;
      if(release==null)throw new IOException("Module version unavailable");module=store.load(release);
      JSONObject task=new JSONObject(request);task.put("_verifiedToolchain",store.selectedToolchain(moduleId));
      module.task(store.file(release),directory(this,moduleId),task.toString(),new ModuleV1.Progress(){
        public void update(String phase,int percent,String log){save(phase,percent,log,true);}
        public boolean cancelled(){return cancelled||Thread.currentThread().isInterrupted();}
      });
      save(cancelled?"Cancelled":"Complete",cancelled?0:100,lastLog(),false);
    }catch(Exception|LinkageError error){save("Failed",0,lastLog()+"\n"+error.toString(),false);CrashReports.save(this,"Module task",error);}
    finally{if(module!=null)try{module.close();}catch(RuntimeException ignored){}busy=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}});
    return START_NOT_STICKY;
  }
  private static File directory(Context c,String id){if(id==null||!id.matches("[a-z][a-z0-9-]{0,63}"))throw new IllegalArgumentException("Invalid module ID");File f=new File(c.getFilesDir(),"modules/data/"+id);f.mkdirs();return f;}
  static String readStatus(Context context,String id){try{
    File file=new File(directory(context,id),"job.json");
    if(!file.isFile())return "{\"phase\":\"Ready\",\"busy\":false,\"percent\":0,\"log\":\"\"}";
    String text=new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
    JSONObject state=new JSONObject(text);
    if(state.optBoolean("busy")){
      boolean alive=false;java.util.List<ActivityManager.RunningAppProcessInfo> processes=context.getSystemService(ActivityManager.class).getRunningAppProcesses();
      if(processes!=null)for(ActivityManager.RunningAppProcessInfo process:processes)if(process.pid==state.optInt("pid"))alive=true;
      if(!alive)state.put("busy",false).put("phase","Interrupted; source and prior APK retained");
    }
    return state.toString();
  }catch(Exception e){return "{\"phase\":\"Status unavailable\",\"busy\":false}";}}
  private String lastLog(){try{return new JSONObject(readStatus(this,moduleId)).optString("log","");}catch(Exception e){return "";}}
  private synchronized void save(String phase,int percent,String log,boolean running){try{
    if(log.length()>180000)log=log.substring(log.length()-180000);
    JSONObject json=new JSONObject().put("phase",phase).put("percent",percent).put("log",log).put("busy",running).put("pid",android.os.Process.myPid()).put("updated",System.currentTimeMillis());
    android.util.AtomicFile atomic=new android.util.AtomicFile(new File(directory(this,moduleId),"job.json"));FileOutputStream out=atomic.startWrite();
    try{out.write(json.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(out);}catch(Exception e){atomic.failWrite(out);}
  }catch(Exception e){CrashReports.save(this,"Module job status",e);}}
  @Override public void onDestroy(){cancelled=true;worker.shutdownNow();super.onDestroy();}
}
