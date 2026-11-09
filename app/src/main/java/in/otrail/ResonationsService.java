package in.otrail;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;

/** Independent user-started media playback; never participates in tracker startup or GPS writes. */
public final class ResonationsService extends Service {
  static final String PLAY="in.otrail.RESONATIONS_PLAY", PAUSE="in.otrail.RESONATIONS_PAUSE";
  static final Set<Runnable> listeners=new CopyOnWriteArraySet<>();
  static boolean active;static String message="";
  private MediaPlayer player;private AudioManager audio;private AudioFocusRequest focus;private MediaSession session;
  private List<ResonationsPlaylist.Track> tracks=List.of();private int index,failed;private boolean prepared;
  static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("resonations",0);}
  static List<ResonationsPlaylist.Track> tracks(Context c){return ResonationsPlaylist.decode(prefs(c).getString("playlist","[]"));}
  static void changed(){for(Runnable r:listeners)r.run();}
  @Override public void onCreate(){super.onCreate();audio=(AudioManager)getSystemService(AUDIO_SERVICE);session=new MediaSession(this,"Resonations");session.setCallback(new MediaSession.Callback(){@Override public void onPause(){pause();}@Override public void onStop(){pause();}});((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel("resonations","Resonations audio",NotificationManager.IMPORTANCE_LOW));}
  @Override public int onStartCommand(Intent intent,int flags,int id){
    if(intent==null||PAUSE.equals(intent.getAction())){pause();return START_NOT_STICKY;}
    if(active)return START_NOT_STICKY;
    tracks=tracks(this);if(tracks.isEmpty()){stopSelf();return START_NOT_STICKY;}
    index=Math.floorMod(prefs(this).getInt("index",0),tracks.size());
    startForeground(24,notification("Preparing audio"));
    AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).setOnAudioFocusChangeListener(change->{if(change<0)pause();},new Handler(getMainLooper())).build();
    if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){message="Audio busy · tap Play to retry";pause();return START_NOT_STICKY;}
    active=true;session.setActive(true);session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_STOP).setState(PlaybackState.STATE_BUFFERING,0,1).build());failed=0;load(prefs(this).getInt("position",0),attributes);return START_NOT_STICKY;
  }
  private Notification notification(String text){
    PendingIntent open=PendingIntent.getActivity(this,24,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent pause=PendingIntent.getService(this,25,new Intent(this,ResonationsService.class).setAction(PAUSE),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    return new Notification.Builder(this,"resonations").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Resonations · looping").setContentText(text).setContentIntent(open).setOngoing(true).setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0)).addAction(new Notification.Action.Builder(null,"Pause",pause).build()).build();
  }
  private void load(int position,AudioAttributes attributes){
    release();if(!active)return;message="Loading · "+tracks.get(index).name();changed();
    MediaPlayer current=new MediaPlayer();player=current;current.setAudioAttributes(attributes);current.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK);
    current.setOnPreparedListener(p->{if(player!=p||!active)return;prepared=true;try{if(p.getDuration()<=0){skip(attributes);return;}if(position>0&&position<p.getDuration())p.seekTo(position);p.start();}catch(RuntimeException error){skip(attributes);return;}message=tracks.get(index).name();session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,message).putLong(MediaMetadata.METADATA_KEY_DURATION,p.getDuration()).build());session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_STOP).setState(PlaybackState.STATE_PLAYING,position,1).build());prefs(this).edit().putInt("index",index).putInt("position",0).apply();((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(24,notification(message));changed();});
    current.setOnCompletionListener(p->{if(player!=p||!active)return;failed=0;index=ResonationsPlaylist.next(index,tracks.size());load(0,attributes);});
    current.setOnErrorListener((p,what,extra)->{if(player==p)skip(attributes);return true;});
    try{current.setDataSource(this,Uri.parse(tracks.get(index).uri()));current.prepareAsync();}catch(Exception error){skip(attributes);}
  }
  private void skip(AudioAttributes attributes){
    if(++failed>=tracks.size()){message="Files unavailable or unsupported · choose again";prefs(this).edit().putInt("position",0).apply();pause();return;}
    index=ResonationsPlaylist.next(index,tracks.size());new Handler(getMainLooper()).post(()->{if(active)load(0,attributes);});
  }
  private void pause(){if(prepared&&player!=null)try{prefs(this).edit().putInt("index",index).putInt("position",player.getCurrentPosition()).apply();}catch(IllegalStateException ignored){}active=false;release();if(focus!=null){audio.abandonAudioFocusRequest(focus);focus=null;}stopForeground(STOP_FOREGROUND_REMOVE);changed();stopSelf();}
  private void release(){prepared=false;if(player!=null){MediaPlayer old=player;player=null;old.setOnPreparedListener(null);old.setOnCompletionListener(null);old.setOnErrorListener(null);old.release();}}
  @Override public void onDestroy(){if(session!=null){session.setActive(false);session.release();}active=false;release();if(focus!=null)audio.abandonAudioFocusRequest(focus);changed();super.onDestroy();}
  @Override public IBinder onBind(Intent intent){return null;}
}
