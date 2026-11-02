package in.otrail;

import android.app.DownloadManager;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import org.mapsforge.map.reader.MapFile;
import java.io.*;
import java.nio.file.*;

/** Large datasets are downloaded by Android directly to phone storage, never bundled. */
public final class MapPacks {
    public record Pack(String key,String name,String description,String url,String filename,long estimatedBytes){}
    public static final Pack[] PACKS={
        new Pack("mapsforge","Mapsforge · India offline","Roads, places and labels. Works in the offline map.","https://download.mapsforge.org/maps/v5/asia/india.map","india.map",1626726400L),
        new Pack("geofabrik","Geofabrik · India source data","Raw OSM PBF for future routing/data tools. Not a viewable map pack.","https://download.geofabrik.de/asia/india-latest.osm.pbf","india.osm.pbf",1712324608L)
    };
    public record State(String status,long bytes,long total,boolean pending,boolean installed){}
    private final Context c;private final TrailDb db;private final SharedPreferences prefs;private final DownloadManager dm;
    public MapPacks(Context c,TrailDb db){this.c=c;this.db=db;prefs=c.getSharedPreferences("packs",0);dm=(DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);}
    public File active(){return new File(c.getFilesDir(),"maps/india.map");}
    public File raw(){return new File(prefs.getString("raw_path",new File(c.getExternalFilesDir("datasets"),"india.osm.pbf").getAbsolutePath()));}
    private File candidate(Pack p){return new File(prefs.getString(p.key()+"_candidate",new File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),p.filename()+".candidate").getAbsolutePath()));}
    public synchronized void download(Pack p){
        if(prefs.getLong(p.key(),-1)!=-1)throw new IllegalStateException("This download is already queued");
        String relative="oTraiL/"+System.currentTimeMillis()+"-"+p.filename();File tmp=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),relative);
        prefs.edit().putString(p.key()+"_candidate",tmp.getAbsolutePath()).apply();
        DownloadManager.Request r=new DownloadManager.Request(Uri.parse(p.url())).setTitle("oTraiL · "+p.name()).setDescription("India · Wi-Fi or cellular · "+size(p.estimatedBytes())).setAllowedOverMetered(true).setAllowedOverRoaming(false).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,relative);
        long id=dm.enqueue(r);prefs.edit().putLong(p.key(),id).putString(p.key()+"_status","Queued").apply();db.log(p.name(),"QUEUED",p.url());
    }
    public synchronized void cancel(Pack p){long id=prefs.getLong(p.key(),-1);if(id!=-1)dm.remove(id);prefs.edit().remove(p.key()).putString(p.key()+"_status","Cancelled").apply();db.log(p.name(),"CANCELLED","Previous installed dataset retained");}
    public synchronized State state(Pack p){
        File installed=p.key().equals("mapsforge")?active():raw();long id=prefs.getLong(p.key(),-1);
        if(id==-1)return new State(prefs.getString(p.key()+"_status",installed.exists()?"Ready offline":"Not downloaded"),installed.exists()?installed.length():0,p.estimatedBytes(),false,installed.exists());
        try(Cursor cur=dm.query(new DownloadManager.Query().setFilterById(id))){
            if(!cur.moveToFirst()){finish(p,"Removed from Android downloads");return state(p);}
            int status=cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));long bytes=cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));long total=cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));if(total<=0)total=p.estimatedBytes();
            if(status==DownloadManager.STATUS_FAILED){finish(p,"Failed · reason "+cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)));return state(p);}
            if(status==DownloadManager.STATUS_SUCCESSFUL){
                try{if(p.key().equals("mapsforge")){try(InputStream in=new FileInputStream(candidate(p))){install(in);}}else{File file=candidate(p);if(file.length()<1000000)throw new IOException("Dataset is unexpectedly small");prefs.edit().putString("raw_path",file.getAbsolutePath()).apply();}
                    prefs.edit().putString(p.key()+"_retained",candidate(p).getAbsolutePath()).apply();finish(p,"Ready offline");
                }catch(Exception e){finish(p,"Failed validation: "+e.getMessage());}
                return state(p);
            }
            String s=status==DownloadManager.STATUS_PAUSED?"Paused · waiting for network / Android scheduler":status==DownloadManager.STATUS_PENDING?"Queued · waiting for network":"Downloading";
            return new State(s,bytes,total,true,installed.exists());
        }
    }
    private void finish(Pack p,String status){prefs.edit().remove(p.key()).putString(p.key()+"_status",status).apply();db.log(p.name(),status.startsWith("Ready")?"SUCCESS":"FINISHED",status);}
    public synchronized void install(InputStream in)throws Exception{
        File target=active();target.getParentFile().mkdirs();File part=new File(target.getParentFile(),"candidate.map");
        try{try(FileOutputStream out=new FileOutputStream(part)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);out.getFD().sync();}
            MapFile map=new MapFile(part);try{org.mapsforge.core.model.BoundingBox b=map.boundingBox();if(b.maxLatitude<6||b.minLatitude>38||b.maxLongitude<68||b.minLongitude>98)throw new IOException("Map does not overlap India");}finally{map.close();}
            Files.move(part.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            db.log("India offline pack","SUCCESS","Validated map installed; "+target.length()+" bytes");
        }catch(Exception e){part.delete();throw e;}
    }
    public static String size(long bytes){return bytes>=1073741824?String.format(java.util.Locale.US,"%.2f GB",bytes/1073741824.0):String.format(java.util.Locale.US,"%.1f MB",bytes/1048576.0);}
}
