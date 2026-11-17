package in.otrail;
import android.app.*;
import android.content.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.util.*;

/** Explicitly user-started microphone capture. Never restarted from boot or by the GPS service. */
public final class WavRecordingService extends Service {
 static final String STOP="in.otrail.WAV_STOP";static volatile boolean active;static volatile String status="";
 private volatile boolean stopping;private AudioRecord recorder;private PowerManager.WakeLock wake;
 private final Handler main=new Handler(Looper.getMainLooper());
 private void update(String s){status=s;main.post(ResonationsService::changed);}
 @Override public int onStartCommand(Intent intent,int flags,int id){
  if(intent==null||STOP.equals(intent.getAction())){stopping=true;return START_NOT_STICKY;}
  if(active)return START_NOT_STICKY;
  if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){update("Microphone permission required");stopSelf();return START_NOT_STICKY;}
  try{
   NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.createNotificationChannel(new NotificationChannel("wav-recording","Resonations recording",NotificationManager.IMPORTANCE_LOW));
   PendingIntent stop=PendingIntent.getService(this,29,new Intent(this,WavRecordingService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
   PendingIntent open=PendingIntent.getActivity(this,28,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
   startForeground(28,new Notification.Builder(this,"wav-recording").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("oTraiL · recording WAV").setContentText("Microphone recording · tap Stop to save").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop & save",stop).build()).build());
   stopping=false;active=true;wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"oTraiL:WavRecording");wake.acquire(12*60*60*1000L);
   new Thread(this::capture,"wav-recording").start();
  }catch(RuntimeException e){active=false;update("Recording unavailable: "+e.getMessage());stopSelf();}
  return START_NOT_STICKY;
 }
 @android.annotation.SuppressLint("MissingPermission") // onStartCommand checks RECORD_AUDIO before creating the recorder.
 private void capture(){
  File file=null;long count=0;int rate=48000,channels=2;
  try {
   int quality=ResonationsService.prefs(this).getInt("quality",0);rate=quality>=2?44100:48000;channels=(quality%2)==1?1:2;
   int mask=channels==2?AudioFormat.CHANNEL_IN_STEREO:AudioFormat.CHANNEL_IN_MONO;
   int min=AudioRecord.getMinBufferSize(rate,mask,AudioFormat.ENCODING_PCM_16BIT);if(min<0)throw new IOException("Capture format unsupported; choose mono or a different sample rate");
   recorder=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(mask).build()).setBufferSizeInBytes(Math.max(min*4,65536)).build();
   if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("Microphone initialization failed; try mono");
   AudioDeviceInfo preferred=ResonationsAudioSettings.input(this);
   if(!ResonationsService.prefs(this).getString("input","default").equals("default")&&(preferred==null||!recorder.setPreferredDevice(preferred)))throw new IOException("Selected input is unavailable; choose a connected device");
   File root=new File(getFilesDir(),"transferred-media");root.mkdirs();file=new File(root,"Resonations-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+".wav");
   try(RandomAccessFile out=new RandomAccessFile(file,"rw")) {
    out.write(WavHeader.pcm16(0,rate,channels));recorder.startRecording();if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IOException("Microphone busy or unavailable");
    byte[] data=new byte[65536];long last=0;
    while(!stopping){
     int n=recorder.read(data,0,data.length,AudioRecord.READ_BLOCKING);if(n<=0)throw new IOException("Capture stopped ("+n+")");
     n-=n%(channels*2);if(count+n>0xffffffffL-36){update("WAV size limit reached; saving");break;}
     out.write(data,0,n);count+=n;
     if(System.currentTimeMillis()-last>2000){last=System.currentTimeMillis();out.seek(0);out.write(WavHeader.pcm16(count,rate,channels));out.seek(44+count);out.getFD().sync();AudioDeviceInfo actual=recorder.getRoutedDevice();update("REC · "+(count/(rate*channels*2))+"s · "+rate/1000f+" kHz · "+(actual==null?"input pending":actual.getProductName()));}
    }
    out.seek(0);out.write(WavHeader.pcm16(count,rate,channels));out.getFD().sync();
   }
   update("Saved WAV · "+file.getName());
  }catch(Exception e){update("Recording stopped: "+e.getMessage());}
  finally {
   if(recorder!=null){try{recorder.stop();}catch(IllegalStateException ignored){}recorder.release();recorder=null;}
   if(file!=null&&count>0){
    // Finalize even when the microphone disconnects or the writer stops unexpectedly.
    try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.seek(0);out.write(WavHeader.pcm16(count,rate,channels));out.setLength(count+44);}catch(IOException ignored){}
    List<ResonationsPlaylist.Track> tracks=new ArrayList<>(ResonationsService.tracks(this));tracks.add(new ResonationsPlaylist.Track("content://"+getPackageName()+".transferredmedia/"+Uri.encode(file.getName()),file.getName()));ResonationsService.prefs(this).edit().putString("playlist",ResonationsPlaylist.encode(tracks)).apply();
   }else if(file!=null)file.delete();
   active=false;if(wake!=null&&wake.isHeld())wake.release();main.post(()->{ResonationsService.changed();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});
  }
 }
 @Override public void onDestroy(){stopping=true;super.onDestroy();}
 @Override public IBinder onBind(Intent intent){return null;}
}
