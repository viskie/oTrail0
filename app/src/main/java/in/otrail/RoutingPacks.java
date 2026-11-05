package in.otrail;

import android.app.DownloadManager;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import btools.mapaccess.PhysicalFile;

/** DownloadManager owns network jobs. Validation and replacement never run on the UI thread. */
public final class RoutingPacks {
  public record State(String note,long bytes,long total,boolean busy,boolean ready) {}
  private static final ExecutorService worker=Executors.newSingleThreadExecutor();
  private static final Set<String> installing=ConcurrentHashMap.newKeySet();
  private final Context c;
  private final SharedPreferences prefs;
  private final DownloadManager dm;
  public RoutingPacks(Context c) {
    this.c=c.getApplicationContext();prefs=c.getSharedPreferences("routing-packs",0);
    dm=(DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);
  }
  public static List<String> indiaTiles() {
    List<String> names=new ArrayList<>();
    for(int lat=5;lat<=35;lat+=5) for(int lon=65;lon<=95;lon+=5)
      if(!(lat>=30&&lon>=85) && !(lat<=20&&lon==65) && !(lat<=15&&lon>=85)) names.add("E"+lon+"_N"+lat);
    return names;
  }
  File target(String key) {
    return new File(OfflineRouting.root(c),key.equals("valhalla")?"valhalla/india.tar":"segments4/"+key+".rd5");
  }
  public void download(String key,String url,String sha) throws Exception {
    if(!key.equals("valhalla") && !indiaTiles().contains(key))throw new IOException("Choose an India tile");
    Uri uri=Uri.parse(url.trim());
    if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null)throw new IOException("Use an HTTPS graph-pack URL");
    if(!sha.isEmpty()&&!sha.matches("[a-fA-F0-9]{64}"))throw new IOException("SHA-256 must have 64 hexadecimal characters");
    if(prefs.getLong(key+".job",-1)!=-1 || installing.contains(key))throw new IOException("Already queued or validating");
    String filename=key.equals("valhalla")?"india-valhalla.tar":key+".rd5";
    DownloadManager.Request r=new DownloadManager.Request(uri).setTitle("oTraiL · "+filename)
      .setDescription("Offline routing graph · Wi-Fi or cellular")
      .setAllowedOverMetered(true).setAllowedOverRoaming(false)
      .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
      .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,"oTraiL/"+System.currentTimeMillis()+"-"+filename);
    long id=dm.enqueue(r);
    prefs.edit().putLong(key+".job",id).putString(key+".sha",sha.toLowerCase(Locale.ROOT)).putString(key+".note","Queued").apply();
    log(key,"QUEUED","Phone download requested");
  }
  public void cancel(String key) {
    long job=prefs.getLong(key+".job",-1);
    if(job!=-1)dm.remove(job);
    prefs.edit().remove(key+".job").putString(key+".note","Cancelled · previous graph retained").apply();
  }
  public State state(String key) {
    File file=target(key);long id=prefs.getLong(key+".job",-1);
    if(installing.contains(key))return new State("Validating graph · previous version retained until ready",0,0,true,file.isFile());
    if(id==-1)return new State(prefs.getString(key+".note",file.isFile()?"Ready offline":"Not downloaded"),file.length(),file.length(),false,file.isFile());
    try(Cursor q=dm.query(new DownloadManager.Query().setFilterById(id))) {
      if(!q.moveToFirst()) { prefs.edit().remove(key+".job").putString(key+".note","Download removed; retry").apply();return state(key); }
      int status=q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
      long bytes=q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),total=q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
      if(status==DownloadManager.STATUS_SUCCESSFUL) {
        if(installing.add(key)) worker.execute(()->{
          try(InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(dm.openDownloadedFile(id))) {
            install(key,in,prefs.getString(key+".sha",""));
            prefs.edit().remove(key+".job").putString(key+".note","Ready offline").apply();
          }catch(Exception e){prefs.edit().remove(key+".job").putString(key+".note","Validation failed: "+e.getMessage()).apply();log(key,"FAILED",e.toString());}
          finally{installing.remove(key);}
        });
        return new State("Validating downloaded graph",bytes,total,true,file.isFile());
      }
      if(status==DownloadManager.STATUS_FAILED) {
        prefs.edit().remove(key+".job").putString(key+".note","Failed · Android reason "+q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))).apply();return state(key);
      }
      String note=status==DownloadManager.STATUS_RUNNING?"Downloading":status==DownloadManager.STATUS_PENDING?"Queued by Android":"Paused · network/server retry";
      return new State(note,bytes,total,true,file.isFile());
    }
  }
  public void complete() { for(String key:indiaTiles())state(key);state("valhalla"); }
  public void importFile(String key,Uri uri) throws Exception {
    if(!key.equals("valhalla") && !indiaTiles().contains(key)) throw new IOException("Unrecognized India tile name");
    if(!installing.add(key))throw new IOException("Validation already in progress");
    try(InputStream in=c.getContentResolver().openInputStream(uri)){install(key,in,"");prefs.edit().putString(key+".note","Ready offline · imported").apply();}
    finally{installing.remove(key);}
  }
  private void install(String key,InputStream in,String sha) throws Exception {
    File file=target(key);file.getParentFile().mkdirs();File part=new File(file.getParentFile(),file.getName()+".candidate");
    try {
      MessageDigest digest=MessageDigest.getInstance("SHA-256");
      try(FileOutputStream out=new FileOutputStream(part)) {
        byte[] b=new byte[65536];int n;
        while((n=in.read(b))!=-1){out.write(b,0,n);digest.update(b,0,n);}out.getFD().sync();
      }
      if(part.length()<512)throw new IOException("Graph file is empty or too small");
      StringBuilder sum=new StringBuilder();for(byte b:digest.digest())sum.append(String.format(Locale.ROOT,"%02x",b&255));
      if(!sha.isEmpty()&&!sha.equals(sum.toString()))throw new IOException("Graph checksum mismatch");
      if(key.equals("valhalla"))validateTar(part);
      else PhysicalFile.checkFileIntegrity(part);
      // Routes also synchronize on this lock so a native mmap cannot race graph replacement.
      synchronized(OfflineRouting.class) { Files.move(part.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
      log(key,"SUCCESS","Validated offline routing graph · "+file.length()+" bytes · SHA-256 "+sum);
    }finally{part.delete();}
  }
  static void validateTar(File file) throws Exception {
    boolean graph=false;long pos=0;
    try(RandomAccessFile in=new RandomAccessFile(file,"r")) {
      byte[] header=new byte[512];
      while(pos+512<=in.length()) {
        in.seek(pos);in.readFully(header);boolean empty=true;for(byte b:header)if(b!=0){empty=false;break;}if(empty)break;
        long stored=octal(header,148,8),sum=0;
        for(int i=0;i<512;i++)sum+=(i>=148&&i<156)?32:(header[i]&255);
        if(stored!=sum)throw new IOException("Invalid tar header checksum");
        String name=new String(header,0,100,java.nio.charset.StandardCharsets.UTF_8).split("\u0000",2)[0];
        long length=octal(header,124,12);
        if(name.endsWith(".gph")&&length>0)graph=true;
        if(length<0||length>in.length()-pos-512)throw new IOException("Truncated graph archive");
        pos+=512+((length+511)/512)*512;
      }
    }
    if(!graph)throw new IOException("Expected an uncompressed Valhalla .tar containing .gph tiles");
  }
  private static long octal(byte[] b,int start,int length)throws IOException {
    String s=new String(b,start,length,java.nio.charset.StandardCharsets.US_ASCII).replace("\u0000","").trim();
    try{return s.isEmpty()?0:Long.parseLong(s,8);}catch(NumberFormatException e){throw new IOException("Invalid tar size",e);}
  }
  private void log(String key,String status,String detail){try(TrailDb db=new TrailDb(c)){db.log("Routing · "+key,status,detail);}}
}
