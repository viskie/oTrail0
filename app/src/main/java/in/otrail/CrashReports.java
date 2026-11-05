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
  static void save(Context c,String stage,Throwable error) {
    try(FileOutputStream out=new FileOutputStream(new File(c.getFilesDir(),"last-crash.txt"))) {
      out.write(("oTraiL 0.2.7"+" · "+new java.util.Date()+"\n"+stage+"\n"+android.util.Log.getStackTraceString(error)).getBytes(StandardCharsets.UTF_8));out.getFD().sync();
    }catch(Throwable ignored){}
  }
  static String read(Context c) {
    StringBuilder text=new StringBuilder();
    try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(new File(c.getFilesDir(),"last-crash.txt")),StandardCharsets.UTF_8))) {
      String s;while((s=r.readLine())!=null&&text.length()<48000)text.append(s).append('\n');
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
    new AlertDialog.Builder(a).setTitle("Last app crash").setView(sc).setPositiveButton("Close",null).setNeutralButton("Copy report",(d,w)->{
      ((android.content.ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("oTraiL crash",report));
    }).show();
  }
}
