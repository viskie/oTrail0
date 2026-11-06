package in.otrail;

import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** User-selected SAF documents work with local storage and Google Drive's document provider. */
public final class TrailBackup {
    private final Context c;private final SharedPreferences prefs;
    public static final long INTERVAL_MS=15*60*1000;
    private static final Object LOCK=new Object();
    public TrailBackup(Context c){this.c=c.getApplicationContext();prefs=c.getSharedPreferences("trail_backup",0);}
    public void configure(Uri uri,int flags){
        c.getContentResolver().takePersistableUriPermission(uri,flags&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        prefs.edit().putString("document",uri.toString()).putLong("last_success",0).putString("status","Backup location configured").apply();
    }
    public boolean configured(){return prefs.contains("document");}
    public String status(){long time=prefs.getLong("last_success",0);return prefs.getString("status","Choose a backup file in Google Drive or a persistent phone folder.")+(time==0?"":"\nLast successful write: "+new java.util.Date(time))+"\nAutomatic backup: every 15 minutes while tracking. Backup now saves immediately. A Drive write may sync later when connectivity returns.";}
    public void save(TrailDb db,boolean force)throws Exception{
        synchronized(LOCK){if(!configured()||!force&&System.currentTimeMillis()-prefs.getLong("last_success",0)<INTERVAL_MS)return;
            Uri uri=Uri.parse(prefs.getString("document",""));
            // Keep a complete local candidate before touching a remote document.
            File candidate=new File(c.getCacheDir(),"trail-backup-candidate.json");
            try{try(Writer w=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(candidate),StandardCharsets.UTF_8),65536)){db.writeBackup(w);}
                try(InputStream in=new FileInputStream(candidate);OutputStream out=c.getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Backup provider did not open the file");copy(in,out);}
                prefs.edit().putLong("last_success",System.currentTimeMillis()).putString("status","Trail backup saved to selected document").apply();
            }catch(Exception e){prefs.edit().putString("status","Backup needs attention: "+e.getMessage()).apply();throw e;}finally{candidate.delete();}
        }
    }
    public void restore(TrailDb db,Uri uri)throws Exception{
        synchronized(LOCK){try(InputStream in=c.getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Cannot open backup");db.restoreBackup(new InputStreamReader(in,StandardCharsets.UTF_8));}db.log("Trail backup","RESTORED","Normalized trail snapshot restored from user-selected document");}
    }
    static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
}
