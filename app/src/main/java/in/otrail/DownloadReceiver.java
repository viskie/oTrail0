package in.otrail;
import android.content.*;
public final class DownloadReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent){if(!android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction()))return;PendingResult result=goAsync();new Thread(()->{try(TrailDb db=new TrailDb(c)){MapPacks packs=new MapPacks(c,db);for(MapPacks.Pack p:MapPacks.PACKS)packs.state(p);}finally{result.finish();}},"pack-completion").start();}
}
