package in.otrail;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Private diagnostic file is independent of trail storage. Does not intercept normal termination. */
public final class CrashReports extends Application {
  public void onCreate() {
    super.onCreate();
    Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
      save(this,"Uncaught on "+thread.getName(),error);
      if(previous!=null)previous.uncaughtException(thread,error);
      else{android.os.Process.killProcess(android.os.Process.myPid());System.exit(10);}
    });
  }
  static synchronized void save(Context c,String stage,Throwable error) {
    File file=new File(c.getFilesDir(),"otrail-errors.log");
    try {
      if(file.length()>1024*1024){File old=new File(c.getFilesDir(),"otrail-errors.previous.log");old.delete();file.renameTo(old);}
      try(FileOutputStream out=new FileOutputStream(file,true)) {
      String version="unknown";try{version=c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName;}catch(Exception ignored){}
      out.write(("\n=== oTraiL "+version+" · "+new java.util.Date()+" · process "+android.os.Process.myPid()+" ===\n"+stage+"\n"+android.util.Log.getStackTraceString(error)+"\n").getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
    }catch(Throwable ignored){}
  }
  static String read(Context c) {
    StringBuilder text=new StringBuilder();
    try(RandomAccessFile r=new RandomAccessFile(new File(c.getFilesDir(),"otrail-errors.log"),"r")) {
      long start=Math.max(0,r.length()-96000);r.seek(start);if(start>0)r.readLine();byte[] data=new byte[(int)(r.length()-r.getFilePointer())];r.readFully(data);text.append(new String(data,StandardCharsets.UTF_8));
    }catch(IOException ignored){}
    try {
      ActivityManager am=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
      for(ApplicationExitInfo e:am.getHistoricalProcessExitReasons(c.getPackageName(),0,3)) {
        text.append("\nAndroid exit: ").append(new java.util.Date(e.getTimestamp())).append(" reason=").append(e.getReason()).append(" ").append(e.getDescription()).append('\n');
      }
    }catch(Exception ignored){}
    return text.length()==0?"No saved crash report yet.":text.toString();
  }
  static void show(Activity a) {
    String report=read(a);
    android.widget.TextView t=new android.widget.TextView(a);t.setText(report);t.setTextIsSelectable(true);t.setPadding(24,16,24,16);
    android.widget.ScrollView sc=new android.widget.ScrollView(a);sc.addView(t);
    new AppDialogs(a).setTitle("App and module error log").setView(sc).setPositiveButton("Close",null).setNeutralButton("Copy report",(d,w)->{
      ((android.content.ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("oTraiL crash",report));
    }).setNegativeButton("Export file",(d,w)->a.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"otrail-errors.log"),24)).show();
  }
  static void export(Context c,android.net.Uri target)throws IOException{try(InputStream in=new FileInputStream(new File(c.getFilesDir(),"otrail-errors.log"));OutputStream out=c.getContentResolver().openOutputStream(target,"wt")){if(out==null)throw new IOException("Cannot open export destination");byte[] buffer=new byte[16384];int count;while((count=in.read(buffer))>=0)out.write(buffer,0,count);out.flush();}}
}
