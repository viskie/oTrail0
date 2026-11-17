package in.otrail;
import android.app.Activity;
import android.content.*;
import android.media.*;
import android.os.Build;
import android.widget.*;
import java.util.*;

final class ResonationsAudioSettings {
 static final int PERMISSION=76;
 static String key(AudioDeviceInfo d){return d.getType()+":"+d.getAddress()+":"+d.getProductName();}
 static String name(AudioDeviceInfo d){return d.getProductName()+" · "+switch(d.getType()){
  case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth";
  case AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_AUX_LINE -> "wired / aux";
  case AudioDeviceInfo.TYPE_USB_DEVICE,AudioDeviceInfo.TYPE_USB_HEADSET -> "USB";
  case AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in microphone";
  case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker";
  default -> "audio device";
 };}
 static List<AudioDeviceInfo> devices(Context c,boolean input){return Arrays.asList(((AudioManager)c.getSystemService(Context.AUDIO_SERVICE)).getDevices(input?AudioManager.GET_DEVICES_INPUTS:AudioManager.GET_DEVICES_OUTPUTS));}
 static AudioDeviceInfo input(Context c){String key=ResonationsService.prefs(c).getString("input","default");return devices(c,true).stream().filter(d->key(d).equals(key)).findFirst().orElse(null);}
 static List<AudioDeviceInfo> outputs(Context c){Set<String> keys=ResonationsService.prefs(c).getStringSet("outputs",Set.of());return devices(c,false).stream().filter(d->keys.contains(key(d))).collect(java.util.stream.Collectors.toList());}
 static void show(Activity a){
  if(Build.VERSION.SDK_INT>=31&&a.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)!=android.content.pm.PackageManager.PERMISSION_GRANTED){a.requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT},PERMISSION);return;}
  LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(20,12,20,12);
  TextView note=new TextView(a);note.setText("WAV: uncompressed PCM16, 48 kHz or 44.1 kHz. Hardware controls actual capture quality. Select connected devices below. Multiple outputs are requested per player; Android may route them to one device. Two Bluetooth devices / Bluetooth + aux require phone support (e.g. Samsung Dual Audio). Outputs may have different delays.");box.addView(note);
  List<AudioDeviceInfo> inputs=devices(a,true);List<String> labels=new ArrayList<>();labels.add("Input · system default");for(var d:inputs)labels.add(name(d));
  Spinner in=new Spinner(a);in.setAdapter(AppStyle.choices(a,labels));box.addView(in);String saved=ResonationsService.prefs(a).getString("input","default");for(int i=0;i<inputs.size();i++)if(key(inputs.get(i)).equals(saved))in.setSelection(i+1);
  Spinner quality=new Spinner(a);quality.setAdapter(AppStyle.choices(a,List.of("48 kHz · stereo · PCM16", "48 kHz · mono · PCM16", "44.1 kHz · stereo · PCM16", "44.1 kHz · mono · PCM16")));quality.setSelection(Math.max(0,Math.min(3,ResonationsService.prefs(a).getInt("quality",0))));box.addView(quality);
  TextView heading=new TextView(a);heading.setText("Playback outputs · none checked = system default");box.addView(heading);
  Set<String> selected=new HashSet<>(ResonationsService.prefs(a).getStringSet("outputs",Set.of()));List<AudioDeviceInfo> outs=devices(a,false);
  for(var d:outs){CheckBox b=new CheckBox(a);b.setText(name(d));b.setChecked(selected.contains(key(d)));b.setOnCheckedChangeListener((v,on)->{if(on)selected.add(key(d));else selected.remove(key(d));});box.addView(b);}
  Button system=new Button(a);system.setText("System Bluetooth / Dual Audio");AppStyle.button(system);system.setOnClickListener(v->a.startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)));box.addView(system);
  ScrollView scroll=new ScrollView(a);scroll.addView(box);
  new AppDialogs(a).setTitle("Resonations · audio devices").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{
   if(WavRecordingService.active){Toast.makeText(a,"Stop recording before changing capture settings",Toast.LENGTH_LONG).show();return;}
   String input=in.getSelectedItemPosition()==0?"default":key(inputs.get(in.getSelectedItemPosition()-1));
   ResonationsService.prefs(a).edit().putString("input",input).putInt("quality",quality.getSelectedItemPosition()).putStringSet("outputs",selected).apply();
   Toast.makeText(a,"Saved · playback changes apply when Play is restarted",Toast.LENGTH_LONG).show();
  }).show();
 }
}
