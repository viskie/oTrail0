package in.otrail.extensions.mod1;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.widget.*;
import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme;
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.EditorSearcher;
import in.otrail.AppStyle;
import in.otrail.UiConfig;
import in.otrail.modules.ModuleV1;
import org.eclipse.tm4e.core.registry.IThemeSource;
import org.json.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.zip.ZipFile;

/** Downloaded implementation: all editor/build screens remain inside the oTraiL module Activity. */
public final class SourceStudio implements ModuleV1 {
  private Activity activity;private Host host;private LinearLayout root,content,fileTabs;private View headerBar,navigationBar;private TextView status,log;
  private ProgressBar progress;private CodeEditor editor;private CheckBox offline,licenses;private LinearLayout findBar;private EditText findQuery,replaceQuery;private TextView findStatus;
  private final ExecutorService io=Executors.newSingleThreadExecutor();private final Handler handler=new Handler(Looper.getMainLooper());
  private final ExecutorService shellIo=Executors.newCachedThreadPool();private java.lang.Process shellProcess;private OutputStream shellInput;private TextView shellOutput;private final StringBuilder shellTranscript=new StringBuilder();
  private final LinkedHashMap<String,Buffer> buffers=new LinkedHashMap<>();
  private String current="",editorPath="",draftProject="",tab="Project";private JSONObject metadata,state=new JSONObject();
  private final Runnable draftSave=()->saveDrafts();
  private boolean running,closed,polling,suppress,treeExpanded,autoShellLaunch=true;private int pendingLine=-1;private File exportFile;private String exportMime;
  private static final int EXPORT=710,KEYSTORE=711;
  private static final int BG=0xff10101b,TEXT=0xff40e0d0;
  private static boolean textMateReady;
  private final Runnable poll=new Runnable(){public void run(){if(!running||closed)return;poll();handler.postDelayed(this,2000);}};
  private static final class Buffer {String text="",saved="";Button button;boolean dirty(){return !text.equals(saved);}}
  private static final class TreeNode {final String name,path;final boolean directory;final List<TreeNode> children=new ArrayList<>();boolean expanded;TreeNode(String n,String p,boolean d){name=n;path=p;directory=d;}}
  private static final class SearchHit {final String path,text;final int line;SearchHit(String p,int l,String t){path=p;line=l;text=t;}@Override public String toString(){return path+":"+line+"  "+text.trim();}}
  @Override public String id(){return "mod1";}
  @Override public View create(Activity a,Host h)throws Exception{
    activity=a;host=h;
    metadata=new JSONObject(asset("studio.json"));try{mergeMetadata(new JSONObject(host.selectedToolchain()));}catch(Exception ignored){}restoreDrafts();
    root=new LinearLayout(a);root.setOrientation(1);root.setPadding(dp(8),dp(6),dp(8),dp(6));root.setBackgroundColor(BG);
    LinearLayout top=row();top.addView(button("← Modules",()->activity.finish()),new LinearLayout.LayoutParams(0,dp(44),1));top.addView(label("GOD · Source Studio",18),new LinearLayout.LayoutParams(0,-2,2));headerBar=top;root.addView(top);
    GridLayout names=new GridLayout(a);names.setColumnCount(3);int index=0;for(String name:new String[]{"Project","Editor","Build","APKs","Tools"}){Button b=button(name,()->show(name));GridLayout.LayoutParams p=new GridLayout.LayoutParams(GridLayout.spec(index/3),GridLayout.spec(index%3,1f));p.width=0;p.height=dp(44);p.setMargins(dp(3),dp(2),dp(3),dp(2));names.addView(b,p);index++;}navigationBar=names;root.addView(names,new LinearLayout.LayoutParams(-1,-2));
    status=label("In-app engine ready · select source and download compiler tools",12);root.addView(status);progress=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);root.addView(progress,new LinearLayout.LayoutParams(-1,dp(5)));
    content=new LinearLayout(a);content.setOrientation(1);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));show("Project");return root;
  }
  private SharedPreferences preferences(){return activity.getSharedPreferences("mod1_studio",0);}
  private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
  private LinearLayout row(){LinearLayout l=new LinearLayout(activity);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
  private TextView label(String value,int sp){TextView t=new TextView(activity);t.setText(value);t.setTextColor(TEXT);t.setTextSize(sp);t.setPadding(dp(6),dp(4),dp(6),dp(4));return t;}
  private Button button(String value,Runnable action){Button b=new Button(activity);b.setText(value);AppStyle.button(b);b.setPadding(dp(6),0,dp(6),0);b.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(44));p.setMargins(dp(2),dp(2),dp(2),dp(2));b.setLayoutParams(p);return b;}
  private void ui(Runnable action){activity.runOnUiThread(()->{if(!closed&&!activity.isDestroyed()&&!activity.isFinishing())action.run();});}
  private void error(Exception error){ui(()->{String message=error.getMessage()==null?error.toString():error.getMessage();if(status!=null)status.setText(message);else Toast.makeText(activity,message,Toast.LENGTH_LONG).show();});}
  private JSONObject obj(Object... pairs)throws JSONException{JSONObject j=new JSONObject();for(int i=0;i<pairs.length;i+=2)j.put((String)pairs[i],pairs[i+1]);return j;}
  private interface Work {void run()throws Exception;}
  private void work(Work work){io.execute(()->{try{work.run();}catch(Exception e){error(e);}});}
  private String asset(String name)throws Exception{try(ZipFile zip=new ZipFile(host.packageFile());InputStream in=zip.getInputStream(zip.getEntry(name))){return new String(read(in,300000),StandardCharsets.UTF_8);}}
  private void show(String name){
    saveDrafts();if(editor!=null){Buffer b=buffers.get(editorPath);if(b!=null)b.text=editor.getText().toString();editor.release();editor=null;editorPath="";}tab=name;content.removeAllViews();fileTabs=null;log=null;
    moduleChrome(true);
    if(name.equals("Editor")){editorScreen();return;}
    if(name.equals("Shell")){shellScreen();return;}
    ScrollView scroll=new ScrollView(activity);LinearLayout panel=new LinearLayout(activity);panel.setOrientation(1);scroll.addView(panel);content.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
    if(name.equals("Project")){
      panel.addView(label("Project source stays in a separate private workspace. Saved edits are backed up; builds take source snapshots.",14));
      panel.addView(label("No external app or build server is used.",12));
      try{JSONArray sources=metadata.getJSONArray("sources");for(int i=0;i<sources.length();i++){final JSONObject source=sources.getJSONObject(i);panel.addView(button("Download source · "+source.getString("version"),()->work(()->{call(obj("action","source","source",source));ui(()->status.setText("Source download started; progress below"));})));}}catch(Exception e){error(e);}
      panel.addView(button("Browse project files",this::browse));
      panel.addView(button("Open internal-storage source folder",this::importFolder));
      panel.addView(button("SCP workspace configuration",this::scpConfig));
      panel.addView(button("Functions added in this release",this::latestFunctions));
      panel.addView(button("Export source snapshot ZIP",()->work(()->{JSONObject result=call(obj("action","export"));download(result.getString("name"),false,true);})));
      panel.addView(label("Return to an existing workspace by selecting its source version; existing edits are retained. Source downloads work over Wi-Fi or cellular.",12));
    }else if(name.equals("Build")){
      offline=new CheckBox(activity);offline.setText("Offline compilation after toolchain download");offline.setTextColor(TEXT);offline.setChecked(preferences().getBoolean("offline",false));panel.addView(offline);
      panel.addView(button("Save all & build APK on phone",()->{preferences().edit().putBoolean("offline",offline.isChecked()).apply();final boolean off=offline.isChecked();work(()->{saveAllNetwork();call(obj("action","build","offline",off));ui(()->status.setText("Phone build started"));});}));
      panel.addView(button("Cancel current compiler job",()->work(()->call(obj("action","cancel")))));
      panel.addView(label("Java UI/logic rebuild. Resource/XML and build-system changes are rejected until a compatible resource-capable toolchain is selected.",12));
      panel.addView(label("Compiler errors remain in the log. Tap a Java error line to open its source file.",12));
      log=label(state.optString("log","No build yet"),12);log.setTypeface(Typeface.MONOSPACE);log.setTextIsSelectable(true);log.setOnClickListener(v->openError());panel.addView(log);
    }else if(name.equals("APKs")){
      panel.addView(label("Phone-built APKs. Installation checks the existing package, version and signing identity; your trail database is never removed.",14));
      panel.addView(button("Refresh APK list",()->work(()->{JSONObject s=call(obj("action","status"));ui(()->{state=s;show("APKs");});})));
      JSONArray outputs=state.optJSONArray("outputs");if(outputs!=null)for(int i=0;i<outputs.length();i++)try{
        JSONObject out=outputs.getJSONObject(i);final String file=out.getString("name");panel.addView(label(file+String.format(Locale.US," · %.2f MB",out.getLong("size")/1048576.0),12));
        panel.addView(button("Save APK to phone downloads",()->work(()->download(file,false,true))));
        panel.addView(button("Install this APK",()->work(()->download(file,true,false))));
        panel.addView(button("Publish APK to GitHub",()->publish(file)));
      }catch(Exception e){error(e);}
    }else toolsScreen(panel);
    compactButtons(panel);
  }
  private void compactButtons(LinearLayout panel){
    if(activity.getResources().getConfiguration().orientation!=android.content.res.Configuration.ORIENTATION_LANDSCAPE)return;
    ArrayList<View> views=new ArrayList<>();for(int i=0;i<panel.getChildCount();i++)views.add(panel.getChildAt(i));panel.removeAllViews();
    LinearLayout buttons=null;int column=0;
    for(View view:views){
      if(view instanceof Button&&!(view instanceof android.widget.CompoundButton)){
        if(buttons==null||column==3){buttons=row();panel.addView(buttons,new LinearLayout.LayoutParams(-1,-2));column=0;}
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);p.setMargins(dp(3),dp(3),dp(3),dp(3));buttons.addView(view,p);column++;
      }else{if(buttons!=null)while(column++<3)buttons.addView(new View(activity),new LinearLayout.LayoutParams(0,dp(48),1));buttons=null;column=0;panel.addView(view);}
    }
    if(buttons!=null)while(column++<3)buttons.addView(new View(activity),new LinearLayout.LayoutParams(0,dp(48),1));
  }
  private void mergeMetadata(JSONObject update)throws Exception{for(Iterator<String> it=update.keys();it.hasNext();){String key=it.next();metadata.put(key,update.get(key));}}
  private void toolsScreen(LinearLayout panel){
    panel.addView(label("In-app toolchains · no Termux or build server. Compiler code, SDK classpath and verified native-library template download separately from the APK. The optional Shell tab runs a private Debian rootfs through the host APK's PRoot executable.",14));
    panel.addView(button("Refresh / choose toolchain",this::chooseToolchain));
    panel.addView(label("Selected: "+metadata.optString("name","GOD Java toolchain")+" · "+metadata.optString("version","1.0.0"),12));
    licenses=new CheckBox(activity);licenses.setText("I accept the Android SDK and open-source tool licenses");licenses.setTextColor(TEXT);panel.addView(licenses);
    panel.addView(button("Download / repair selected toolchain",()->{if(!licenses.isChecked()){status.setText("Review and accept tool licenses first");return;}work(()->call(obj("action","setup","acceptLicenses",true)));}));
    panel.addView(button("Tool licenses and limitations",()->new AlertDialog.Builder(activity).setTitle("Toolchain details").setMessage("Eclipse ECJ (EPL-2.0), Android D8/apksig (BSD/Apache licenses), Android SDK platform (Android SDK license), OpenJDK compiler API (GPLv2 + Classpath Exception), JGit/JSch and their open-source dependencies. LICENSES/ is included in the tool downloads.\n\nFirst release compiles application Java into a signed APK while retaining verified resources/native libraries. Unsupported resource/build-system edits stop the build. Hardware/runtime validation on your phone is still required.").setPositiveButton("Close",null).show()));
    panel.addView(label("Import the existing PKCS12 signing keystore to preserve installation identity. Keys and passwords remain in private module storage and are excluded from snapshots/exports.",12));
    panel.addView(button("Import existing APK signing keystore",()->activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),KEYSTORE)));
    panel.addView(button("Generate GitHub SSH key",()->work(()->{GitKeys.generate(host.privateDirectory());ui(()->status.setText("Phone-specific SSH key ready; private key stays in Android Keystore"));})));
    panel.addView(button("Download SSH public key",()->work(()->{exportFile=GitKeys.generate(host.privateDirectory());exportMime="text/plain";ui(()->activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(exportMime).putExtra(Intent.EXTRA_TITLE,"GOD-github.pub"),EXPORT));})));
    panel.addView(button("Configure GitHub / test uploaded key",this::githubConfig));
    try{JSONObject github=StudioFiles.json(new File(host.privateDirectory(),"github.json"));panel.addView(label("GitHub: "+github.optString("repository")+" · "+(github.optBoolean("verified")?"Key checked":"Not checked"),12));}catch(Exception ignored){}
    panel.addView(label("Add the exported public key to your GitHub account or repository deploy keys (enable write access for publishing). Then configure and test here. Publishing commits named APKs under releases/; no GitHub Releases workflow. Initial Git synchronization can be large because this repository contains historical APK files.",12));
    panel.addView(label("Workspace: "+state.optString("project","not selected")+"\nFree: "+String.format(Locale.US,"%.2f GB",host.privateDirectory().getUsableSpace()/1073741824.0)+"\nTools downloaded: "+state.optBoolean("toolsReady")+" · APK signing key: "+state.optBoolean("signingConfigured"),12));
  }
  private void latestFunctions(){
    JSONObject latest=metadata.optJSONObject("latestFunctions");if(latest==null){status.setText("No feature-function manifest in this toolchain");return;}
    StringBuilder text=new StringBuilder(latest.optString("commit")).append(" · ").append(latest.optString("title"));JSONArray functions=latest.optJSONArray("functions");if(functions!=null)for(int i=0;i<functions.length();i++)text.append("\n\n• ").append(functions.optString(i));
    new AlertDialog.Builder(activity).setTitle("GOD v3 functions").setMessage(text.toString()).setPositiveButton("Close",null).show();
  }
  private void importFolder(){EditText path=new EditText(activity);path.setHint("/storage/emulated/0/Projects/my-source");new AlertDialog.Builder(activity).setTitle("Open source folder").setMessage("GOD copies the selected folder into its protected editable workspace. Enable shared-storage access first.").setView(path).setPositiveButton("Import",(d,w)->work(()->call(obj("action","importFolder","path",path.getText().toString().trim())))).setNegativeButton("Cancel",null).show();}
  private void scpConfig(){LinearLayout form=new LinearLayout(activity);form.setOrientation(1);EditText hostName=new EditText(activity),user=new EditText(activity),remote=new EditText(activity);hostName.setHint("SSH host");user.setHint("SSH user");remote.setHint("Remote project path");form.addView(hostName);form.addView(user);form.addView(remote);try{JSONObject saved=StudioFiles.json(new File(host.privateDirectory(),"scp.json"));hostName.setText(saved.optString("host"));user.setText(saved.optString("user"));remote.setText(saved.optString("path"));}catch(Exception ignored){}new AlertDialog.Builder(activity).setTitle("SCP project").setMessage("Configuration is shared with the standalone Debian module. Use its scp command to sync this path into /app-data/files/modules/data/mod1/projects/scp-workspace, then refresh GOD.").setView(form).setPositiveButton("Save",(d,w)->work(()->StudioFiles.write(new File(host.privateDirectory(),"scp.json"),obj("host",hostName.getText().toString().trim(),"user",user.getText().toString().trim(),"path",remote.getText().toString().trim()).toString().getBytes(StandardCharsets.UTF_8)))).setNegativeButton("Cancel",null).show();}

  private void shellScreen(){
    LinearLayout panel=new LinearLayout(activity);panel.setOrientation(1);content.addView(panel,new LinearLayout.LayoutParams(-1,-1));
    panel.addView(label("Debian Bookworm ARM64 runs under PRoot with the oTraiL app UID. /app-data and /god-data are writable. /project is the selected GOD source. Other apps' private data remains inaccessible by Android design.",12));
    LinearLayout actions=row();
    actions.addView(button("Install / repair included Debian",()->work(()->call(obj("action","shellSetup")))));
    actions.addView(button("Grant shared-storage access",this::requestAllFiles));
    actions.addView(button(shellProcess==null?"Launch Debian shell":"Shell is running",this::confirmShellLaunch));
    actions.addView(button("Exit shell",this::exitShell));HorizontalScrollView actionScroll=new HorizontalScrollView(activity);actionScroll.setHorizontalScrollBarEnabled(false);actionScroll.addView(actions);panel.addView(actionScroll);
    shellOutput=label(shellTranscript.length()==0?"Shell is not running. Install the included Debian image once, then launch it.":shellTranscript.toString(),12);shellOutput.setTypeface(Typeface.MONOSPACE);shellOutput.setTextIsSelectable(true);shellOutput.setBackgroundColor(0xff050509);
    ScrollView scroll=new ScrollView(activity);scroll.addView(shellOutput);panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    LinearLayout keys=row();EditText input=new EditText(activity);input.setSingleLine(true);input.setHint("command · type exit to return to GOD");input.setTextColor(TEXT);input.setHintTextColor(0xff808090);keys.addView(input,new LinearLayout.LayoutParams(0,dp(48),1));keys.addView(button("Send",()->sendShell(input.getText().toString()+"\n",input)),new LinearLayout.LayoutParams(-2,dp(48)));panel.addView(keys);
    input.setOnEditorActionListener((v,id,event)->{sendShell(input.getText().toString()+"\n",input);return true;});
    LinearLayout controls=row();controls.addView(button("Ctrl-C",()->sendShell("\u0003",null)));controls.addView(button("Tab",()->sendShell("\t",null)));controls.addView(button("↑",()->sendShell("\u001b[A",null)));controls.addView(button("↓",()->sendShell("\u001b[B",null)));panel.addView(controls);
    panel.addView(label(sharedStorageGranted()?"Shared storage: enabled at /shared-storage":"Shared storage: not bound; app-specific external files remain at /external-app",11));
    if(state.optString("phase").equals("Failed"))panel.addView(label("Setup error:\n"+state.optString("log","Unknown shell setup error"),11));
  }

  private boolean sharedStorageGranted(){return Build.VERSION.SDK_INT<30||Environment.isExternalStorageManager();}
  private void requestAllFiles(){
    if(Build.VERSION.SDK_INT<30){status.setText("Shared storage access is already available on this Android version");return;}
    try{activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+activity.getPackageName())));}catch(Exception e){activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));}
  }
  private void confirmShellLaunch(){
    if(shellProcess!=null){status.setText("Debian shell is already running");return;}
    new AlertDialog.Builder(activity).setTitle("Launch writable Debian shell?").setMessage("Commands run with the oTraiL app UID and can modify or delete the journal, GOD workspace, keys and app files through /app-data. Debian's fake root is not Android root. Type exit or tap Exit shell to return here.").setPositiveButton("Launch",(d,w)->work(this::launchShell)).setNegativeButton("Cancel",null).show();
  }
  private void ensureShell(){work(()->{File bash=new File(host.privateDirectory(),"debian/rootfs/usr/bin/bash");if(bash.isFile()){autoShellLaunch=false;launchShell();return;}JSONObject job=new JSONObject(host.taskStatus());if(!job.optBoolean("busy"))call(obj("action","shellSetup"));});}
  private void addBind(List<String> command,File source,String guest)throws IOException{if(source==null||!source.exists())return;command.add("-b");command.add(source.getCanonicalPath()+":"+guest);}
  private void launchShell()throws Exception{
    if(!Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a"))throw new IOException("Debian shell currently supports ARM64 phones only");
    File binary=new File(activity.getApplicationInfo().nativeLibraryDir,"libproot.so"),rootfs=new File(host.privateDirectory(),"debian/rootfs");
    if(!binary.isFile()||!binary.canExecute())throw new IOException("This oTraiL APK does not contain executable ARM64 PRoot");
    if(!new File(rootfs,"usr/bin/bash").isFile())throw new IOException("Install / repair the included Debian image first");
    List<String> command=new ArrayList<>(Arrays.asList(binary.getAbsolutePath(),"--kill-on-exit","--link2symlink","-0","-r",rootfs.getCanonicalPath(),"-b","/dev","-b","/proc","-b","/sys"));
    File data=new File(activity.getApplicationInfo().dataDir);addBind(command,data,"/app-data");addBind(command,host.privateDirectory(),"/god-data");addBind(command,activity.getFilesDir(),"/app-files");addBind(command,activity.getCacheDir(),"/app-cache");addBind(command,activity.getExternalFilesDir(null),"/external-app");
    try{addBind(command,StudioFiles.project(host.privateDirectory()),"/project");command.add("-w");command.add("/project");}catch(Exception missing){command.add("-w");command.add("/root");}
    if(sharedStorageGranted())addBind(command,Environment.getExternalStorageDirectory(),"/shared-storage");
    command.add("/usr/bin/script");command.add("-qfec");command.add("/usr/bin/bash --login");command.add("/dev/null");
    ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true);Map<String,String> env=builder.environment();env.put("HOME","/root");env.put("TERM","xterm-256color");env.put("PATH","/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");env.put("PROOT_TMP_DIR",activity.getCacheDir().getAbsolutePath());env.put("PROOT_TMPDIR",activity.getCacheDir().getAbsolutePath());
    java.lang.Process process=builder.start();synchronized(this){shellProcess=process;shellInput=process.getOutputStream();}appendShell("\n[GOD] Debian shell started. Type exit to return to the app.\n");
    shellIo.execute(()->readShell(process));shellIo.execute(()->waitShell(process));ui(()->show("Shell"));
  }
  private void readShell(java.lang.Process process){try(InputStream in=process.getInputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)appendShell(new String(b,0,n,StandardCharsets.UTF_8));}catch(Exception e){if(process.isAlive())appendShell("\n[GOD] "+e.getMessage()+"\n");}}
  private void waitShell(java.lang.Process process){int code=-1;try{code=process.waitFor();}catch(InterruptedException e){Thread.currentThread().interrupt();}synchronized(this){if(shellProcess==process){shellProcess=null;shellInput=null;}}final int exitCode=code;appendShell("\n[GOD] Shell exited with code "+exitCode+". Returned to app UI.\n");ui(()->{status.setText("Debian shell exited · code "+exitCode);if(tab.equals("Shell"))show("Shell");});}
  private void appendShell(String value){String clean=value.replaceAll("\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)","").replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]","");synchronized(shellTranscript){shellTranscript.append(clean);if(shellTranscript.length()>120000)shellTranscript.delete(0,shellTranscript.length()-100000);clean=shellTranscript.toString();}final String display=clean;ui(()->{if(shellOutput!=null)shellOutput.setText(display);});}
  private void sendShell(String value,EditText input){OutputStream out; synchronized(this){out=shellInput;}if(out==null){status.setText("Launch the Debian shell first");return;}shellIo.execute(()->{try{out.write(value.getBytes(StandardCharsets.UTF_8));out.flush();if(input!=null)ui(()->input.setText(""));}catch(Exception e){error(e);}});}
  private void exitShell(){java.lang.Process process;synchronized(this){process=shellProcess;}if(process==null){if(status!=null)status.setText("Shell is not running");return;}sendShell("exit\n",null);shellIo.execute(()->{try{if(!process.waitFor(2,TimeUnit.SECONDS)){process.destroy();if(!process.waitFor(1,TimeUnit.SECONDS))process.destroyForcibly();}}catch(InterruptedException e){Thread.currentThread().interrupt();}});}
  private void chooseToolchain(){work(()->{JSONArray chains=new JSONArray(host.toolchains(true));String[] titles=new String[chains.length()];for(int i=0;i<titles.length;i++){JSONObject chain=chains.getJSONObject(i);long total=0;JSONArray artifacts=chain.getJSONArray("tools");for(int j=0;j<artifacts.length();j++)total+=artifacts.getJSONObject(j).getLong("sizeBytes");titles[i]=chain.getString("name")+" · "+chain.getString("version")+String.format(Locale.US," · %.1f MB",total/1048576.0);}ui(()->new AlertDialog.Builder(activity).setTitle("Verified toolchains").setItems(titles,(d,index)->work(()->{host.selectToolchain(chains.getJSONObject(index).getString("id"));final JSONObject selected=new JSONObject(host.selectedToolchain());ui(()->{try{mergeMetadata(selected);show("Tools");}catch(Exception e){error(e);}});})).setNegativeButton("Cancel",null).show());});}
  private void githubConfig(){LinearLayout form=new LinearLayout(activity);form.setOrientation(1);EditText repo=new EditText(activity),branch=new EditText(activity);repo.setHint("owner/repository");repo.setText("viskie/oTrail0");branch.setHint("Branch");branch.setText("main");form.addView(repo);form.addView(branch);
    new AlertDialog.Builder(activity).setTitle("GitHub repository").setMessage("Add GOD-github.pub to GitHub first. APK publication never force-pushes.").setView(form).setPositiveButton("Save & test key",(d,w)->{final String repository=repo.getText().toString().trim(),ref=branch.getText().toString().trim();work(()->{if(!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")||!ref.matches("[A-Za-z0-9_.-]+"))throw new IOException("Invalid repository or branch");StudioFiles.write(new File(host.privateDirectory(),"github.json"),obj("repository",repository,"branch",ref,"verified",false).toString().getBytes(StandardCharsets.UTF_8));call(obj("action","githubCheck"));});}).setNegativeButton("Cancel",null).show();
  }
  private void publish(String file){EditText name=new EditText(activity);name.setText(file);new AlertDialog.Builder(activity).setTitle("Publish named APK").setMessage("Commit to the configured GitHub repository's releases/ folder using the phone SSH key.").setView(name).setPositiveButton("Publish",(d,w)->{final String release=name.getText().toString().trim();work(()->call(obj("action","githubPublish","file",file,"releaseName",release)));}).setNegativeButton("Cancel",null).show();}
  private void poll(){if(polling)return;polling=true;work(()->{try{
    JSONObject result=call(obj("action","status"));ui(()->{state=result;status.setText(result.optString("phase")+" · "+(result.optBoolean("busy")?"Running":"Idle"));progress.setIndeterminate(result.optBoolean("busy")&&result.optInt("percent")==0);progress.setProgress(result.optInt("percent"));if(log!=null)log.setText(result.optString("log"));});if(autoShellLaunch&&!result.optBoolean("busy")&&new File(host.privateDirectory(),"debian/rootfs/usr/bin/bash").isFile()&&shellProcess==null){autoShellLaunch=false;launchShell();}
  }catch(Exception ignored){}finally{polling=false;}});}
  private void browse(){work(()->{
    JSONArray files=call(obj("action","files")).getJSONArray("files");final ArrayList<String> paths=new ArrayList<>();for(int i=0;i<files.length();i++)paths.add(files.getString(i));
    ui(()->showFileTree(paths));
  });}
  private void showFileTree(List<String> paths){
    TreeNode root=new TreeNode("project","",true);for(String path:paths){TreeNode parent=root;StringBuilder full=new StringBuilder();String[] parts=path.split("/");for(int i=0;i<parts.length;i++){if(full.length()>0)full.append('/');full.append(parts[i]);boolean directory=i<parts.length-1;TreeNode found=null;for(TreeNode n:parent.children)if(n.name.equals(parts[i])&&n.directory==directory){found=n;break;}if(found==null){found=new TreeNode(parts[i],full.toString(),directory);parent.children.add(found);}parent=found;}}
    sortTree(root);root.expanded=true;LinearLayout box=new LinearLayout(activity);box.setOrientation(1);EditText filter=new EditText(activity);filter.setHint("Filter project tree");box.addView(filter);ListView list=new ListView(activity);box.addView(list,new LinearLayout.LayoutParams(-1,dp(430)));ArrayList<TreeNode> visible=new ArrayList<>();ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_list_item_1,new ArrayList<>());list.setAdapter(adapter);
    Runnable refresh=()->{String query=filter.getText().toString().toLowerCase(Locale.ROOT);visible.clear();flattenTree(root,0,query,visible,adapter);};refresh.run();AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Project file tree").setView(box).setNeutralButton("Search all files",(d,w)->globalSearch()).setNegativeButton("Close",null).create();
    filter.addTextChangedListener(new SimpleWatcher(){public void afterTextChanged(Editable text){refresh.run();}});list.setOnItemClickListener((p,v,index,id)->{TreeNode node=visible.get(index);if(node.directory){node.expanded=!node.expanded;refresh.run();}else{dialog.dismiss();openFile(node.path);}});dialog.show();
  }
  private void sortTree(TreeNode node){node.children.sort(Comparator.comparing((TreeNode n)->!n.directory).thenComparing(n->n.name.toLowerCase(Locale.ROOT)));for(TreeNode child:node.children)sortTree(child);}
  private boolean treeMatches(TreeNode node,String query){if(query.isEmpty()||node.path.toLowerCase(Locale.ROOT).contains(query))return true;for(TreeNode child:node.children)if(treeMatches(child,query))return true;return false;}
  private void flattenTree(TreeNode node,int depth,String query,List<TreeNode> visible,ArrayAdapter<String> adapter){adapter.clear();flattenTreeRows(node,depth,query,visible,adapter);adapter.notifyDataSetChanged();}
  private void flattenTreeRows(TreeNode node,int depth,String query,List<TreeNode> visible,ArrayAdapter<String> adapter){for(TreeNode child:node.children){if(!treeMatches(child,query))continue;visible.add(child);StringBuilder row=new StringBuilder();for(int i=0;i<depth;i++)row.append("   ");row.append(child.directory?(child.expanded||!query.isEmpty()?"▾ ":"▸ "):"  ").append(child.name);adapter.add(row.toString());if(child.directory&&(child.expanded||!query.isEmpty()))flattenTreeRows(child,depth+1,query,visible,adapter);}}
  private void openFile(String path){openFile(path,-1);}
  private void openFile(String path,int line){
    pendingLine=line;
    synchronized(buffers){Buffer existing=buffers.get(path);if(existing!=null&&(!existing.text.isEmpty()||!existing.saved.isEmpty())){current=path;show("Editor");return;}if(existing!=null)buffers.remove(path);}
    work(()->{final String project=StudioFiles.project(host.privateDirectory()).getCanonicalPath();
      if(!draftProject.isEmpty()&&!draftProject.equals(project))throw new IOException("Close saved editor tabs before switching source workspaces");
      final String text=call(obj("action","read","path",path)).getString("text");ui(()->{draftProject=project;Buffer b=new Buffer();b.text=b.saved=text;synchronized(buffers){buffers.put(path,b);}current=path;show("Editor");});});
  }
  private void editorScreen(){
    boolean fullscreen=!current.isEmpty();moduleChrome(!fullscreen);
    HorizontalScrollView tabs=new HorizontalScrollView(activity);fileTabs=row();tabs.addView(fileTabs);content.addView(tabs);
    synchronized(buffers){for(Map.Entry<String,Buffer> entry:buffers.entrySet()){String path=entry.getKey();Buffer b=entry.getValue();b.button=button(shortName(path)+(b.dirty()?" *":""),()->{current=path;show("Editor");});fileTabs.addView(b.button);}}
    TextView path=label(current.isEmpty()?"No file open":current,12);path.setSingleLine(true);path.setEllipsize(TextUtils.TruncateAt.MIDDLE);content.addView(path);
    LinearLayout actions=row();
    actions.addView(button("Files",()->toggleTree(null)));actions.addView(button("Search project",this::globalSearch));actions.addView(button("Reload",this::reloadCurrent));actions.addView(button("Save",()->work(()->saveOne(current))));actions.addView(button("Save all",()->work(()->saveAllNetwork())));
    actions.addView(button("Undo",()->undo(false)));actions.addView(button("Redo",()->undo(true)));actions.addView(button("Find / replace",this::find));actions.addView(button("Close",this::closeFile));HorizontalScrollView actionScroll=new HorizontalScrollView(activity);actionScroll.setHorizontalScrollBarEnabled(false);actionScroll.addView(actions);content.addView(actionScroll);
    boolean landscape=activity.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    LinearLayout workspace=new LinearLayout(activity);workspace.setOrientation(landscape?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);LinearLayout treePane=new LinearLayout(activity);treePane.setTag("editor-tree");treePane.setOrientation(1);treePane.setBackgroundColor(0xff0a0a12);LinearLayout editorPane=new LinearLayout(activity);editorPane.setOrientation(1);workspace.addView(treePane,landscape?new LinearLayout.LayoutParams(dp(260),-1):new LinearLayout.LayoutParams(-1,treeExpanded?dp(260):dp(52)));workspace.addView(editorPane,landscape?new LinearLayout.LayoutParams(0,-1,1):new LinearLayout.LayoutParams(-1,0,1));FrameLayout stage=new FrameLayout(activity);stage.addView(workspace,new FrameLayout.LayoutParams(-1,-1));if(fullscreen){Button back=button("↙ GOD",()->{moduleChrome(true);show("Project");});FrameLayout.LayoutParams floating=new FrameLayout.LayoutParams(dp(92),dp(46),Gravity.TOP|Gravity.END);floating.setMargins(dp(4),dp(4),dp(4),dp(4));stage.addView(back,floating);}content.addView(stage,new LinearLayout.LayoutParams(-1,0,1));inlineFileTree(treePane);
    if(current.isEmpty()){
      LinearLayout empty=new LinearLayout(activity);empty.setOrientation(1);empty.setGravity(Gravity.CENTER);empty.addView(label("Open a project file to start editing",16));empty.addView(label("Choose a file in the project tree · Search project finds text across the workspace",12));LinearLayout choices=row();choices.setGravity(Gravity.CENTER);choices.addView(button("Open files",this::browse));choices.addView(button("Search project",this::globalSearch));empty.addView(choices);editorPane.addView(empty,new LinearLayout.LayoutParams(-1,0,1));return;
    }
    createFindBar();editorPane.addView(findBar);
    editor=new CodeEditor(activity);editorPath=current;editor.setTextSize(14);editor.setTypefaceText(Typeface.MONOSPACE);editor.setTypefaceLineNumber(Typeface.MONOSPACE);editor.setWordwrap(false);editor.setPinLineNumber(true);editor.setHighlightCurrentLine(true);editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);configureTextMate(editor);
    Buffer b=buffers.get(current);editor.setText(b==null?"":b.text);editor.setEditable(b!=null);editorPane.addView(editor,new LinearLayout.LayoutParams(-1,0,1));
    editor.subscribeEvent(ContentChangeEvent.class,(event,unsubscribe)->{if(suppress)return;Buffer buffer=buffers.get(editorPath);if(buffer==null)return;buffer.text=editor.getText().toString();if(buffer.button!=null)buffer.button.setText(shortName(editorPath)+(buffer.dirty()?" *":""));handler.removeCallbacks(draftSave);handler.postDelayed(draftSave,300);});
    editor.setOnKeyListener((v,key,event)->{if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;if(key==KeyEvent.KEYCODE_ESCAPE){moduleChrome(true);show("Project");return true;}if(!event.isCtrlPressed())return false;if(key==KeyEvent.KEYCODE_F){if(event.isShiftPressed())globalSearch();else find();return true;}if(key==KeyEvent.KEYCODE_S){if(event.isShiftPressed())work(()->saveAllNetwork());else work(()->saveOne(editorPath));return true;}if(key==KeyEvent.KEYCODE_Z){undo(false);return true;}if(key==KeyEvent.KEYCODE_Y){undo(true);return true;}if(key==KeyEvent.KEYCODE_W){closeFile();return true;}return false;});
    if(pendingLine>0&&b!=null){int line=Math.min(pendingLine-1,Math.max(0,editor.getLineCount()-1));editor.setSelection(line,0);editor.ensurePositionVisible(line,0);pendingLine=-1;}
  }
  private void moduleChrome(boolean visible){int state=visible?View.VISIBLE:View.GONE;if(headerBar!=null)headerBar.setVisibility(state);if(navigationBar!=null)navigationBar.setVisibility(state);if(status!=null)status.setVisibility(state);if(progress!=null)progress.setVisibility(state);}
  private void inlineFileTree(LinearLayout panel){panel.removeAllViews();LinearLayout title=row();title.addView(button(treeExpanded?"▾ PROJECT FILES":"▸ PROJECT FILES",()->toggleTree(panel)),new LinearLayout.LayoutParams(0,dp(46),1));title.addView(button("↻",()->inlineFileTree(panel)),new LinearLayout.LayoutParams(dp(52),dp(42)));panel.addView(title);TextView loading=label("Loading project tree…",12);panel.addView(loading);work(()->{JSONArray files=call(obj("action","files")).getJSONArray("files");ArrayList<String> paths=new ArrayList<>();for(int i=0;i<files.length();i++)paths.add(files.getString(i));ui(()->renderInlineFileTree(panel,paths));});}
  private void toggleTree(LinearLayout known){LinearLayout panel=known;if(panel==null)panel=content.findViewWithTag("editor-tree");if(panel==null){browse();return;}if(activity.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE)return;treeExpanded=!treeExpanded;ViewGroup.LayoutParams params=panel.getLayoutParams();params.height=treeExpanded?dp(260):dp(52);panel.setLayoutParams(params);inlineFileTree(panel);}
  private void renderInlineFileTree(LinearLayout panel,List<String> paths){panel.removeAllViews();LinearLayout title=row();title.addView(button(treeExpanded?"▾ PROJECT FILES":"▸ PROJECT FILES",()->toggleTree(panel)),new LinearLayout.LayoutParams(0,dp(46),1));title.addView(button("↻",()->inlineFileTree(panel)),new LinearLayout.LayoutParams(dp(52),dp(42)));panel.addView(title);TreeNode tree=new TreeNode("project","",true);for(String path:paths){TreeNode parent=tree;StringBuilder full=new StringBuilder();String[] parts=path.split("/");for(int i=0;i<parts.length;i++){if(full.length()>0)full.append('/');full.append(parts[i]);boolean directory=i<parts.length-1;TreeNode found=null;for(TreeNode n:parent.children)if(n.name.equals(parts[i])&&n.directory==directory){found=n;break;}if(found==null){found=new TreeNode(parts[i],full.toString(),directory);parent.children.add(found);}if(directory&&(!current.isEmpty()&&(current.equals(full.toString())||current.startsWith(full+"/"))))found.expanded=true;parent=found;}}sortTree(tree);tree.expanded=true;EditText filter=new EditText(activity);filter.setHint("Type to find any file");filter.setSingleLine(true);panel.addView(filter,new LinearLayout.LayoutParams(-1,dp(44)));ListView list=new ListView(activity);list.setDividerHeight(0);panel.addView(list,new LinearLayout.LayoutParams(-1,0,1));ArrayList<TreeNode> visible=new ArrayList<>();ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_list_item_1,new ArrayList<>());list.setAdapter(adapter);Runnable refresh=()->{String query=filter.getText().toString().toLowerCase(Locale.ROOT);visible.clear();flattenTree(tree,0,query,visible,adapter);};refresh.run();filter.addTextChangedListener(new SimpleWatcher(){public void afterTextChanged(Editable text){refresh.run();}});list.setOnItemClickListener((p,v,index,id)->{TreeNode node=visible.get(index);if(node.directory){node.expanded=!node.expanded;refresh.run();}else{treeExpanded=false;openFile(node.path);}});}
  private void reloadCurrent(){final String path=current;if(path.isEmpty())return;new AlertDialog.Builder(activity).setTitle("Reload from project?").setMessage("Discard the in-memory draft and read "+path+" again.").setPositiveButton("Reload",(d,w)->work(()->{String text=call(obj("action","read","path",path)).getString("text");ui(()->{Buffer b=buffers.get(path);if(b!=null)b.text=b.saved=text;show("Editor");});})).setNegativeButton("Cancel",null).show();}
  private String shortName(String path){return path.substring(path.lastIndexOf('/')+1);}
  private void configureTextMate(CodeEditor target){try{synchronized(SourceStudio.class){if(!textMateReady){FileProviderRegistry.getInstance().addFileProvider(new AssetsFileResolver(activity.getApplicationContext().getAssets()));try(InputStream theme=activity.getAssets().open("textmate/darcula.json")){ThemeRegistry.getInstance().loadTheme(IThemeSource.fromInputStream(theme,"textmate/darcula.json",StandardCharsets.UTF_8),true);}GrammarRegistry.getInstance().loadGrammars("textmate/languages.json");textMateReady=true;}}target.setColorScheme(TextMateColorScheme.create(ThemeRegistry.getInstance()));if(current.endsWith(".java"))target.setEditorLanguage(TextMateLanguage.create("source.java",true));}catch(Exception e){status.setText("TextMate fallback: "+e.getMessage());}}
  private void createFindBar(){findBar=new LinearLayout(activity);findBar.setOrientation(1);findBar.setVisibility(View.GONE);LinearLayout fields=row(),commands=row();findQuery=new EditText(activity);findQuery.setHint("Find");replaceQuery=new EditText(activity);replaceQuery.setHint("Replace");findQuery.setSingleLine(true);replaceQuery.setSingleLine(true);findStatus=label("",11);fields.addView(findQuery,new LinearLayout.LayoutParams(0,dp(44),1));fields.addView(replaceQuery,new LinearLayout.LayoutParams(0,dp(44),1));commands.addView(button("‹ Prev",()->{searchNow();editor.getSearcher().gotoPrevious();updateFindStatus();}));commands.addView(button("Next ›",()->{searchNow();editor.getSearcher().gotoNext();updateFindStatus();}));commands.addView(button("Replace",()->{editor.getSearcher().replaceCurrentMatch(replaceQuery.getText().toString());updateFindStatus();}));commands.addView(button("Replace all",()->editor.getSearcher().replaceAll(replaceQuery.getText().toString(),this::updateFindStatus)));commands.addView(findStatus);HorizontalScrollView commandScroll=new HorizontalScrollView(activity);commandScroll.setHorizontalScrollBarEnabled(false);commandScroll.addView(commands);findBar.addView(fields);findBar.addView(commandScroll);findQuery.addTextChangedListener(new SimpleWatcher(){public void afterTextChanged(Editable e){searchNow();}});}
  private void searchNow(){if(editor==null)return;String query=findQuery==null?"":findQuery.getText().toString();if(query.isEmpty()){editor.getSearcher().stopSearch();updateFindStatus();return;}editor.getSearcher().search(query,new EditorSearcher.SearchOptions(EditorSearcher.SearchOptions.TYPE_NORMAL,false));handler.postDelayed(this::updateFindStatus,120);}
  private void updateFindStatus(){if(findStatus==null||editor==null)return;EditorSearcher s=editor.getSearcher();int count=s.getMatchedPositionCount(),at=s.getCurrentMatchedPositionIndex();findStatus.setText(count==0?"0":(Math.max(0,at)+1)+"/"+count);}
  private void undo(boolean redo){if(editor==null)return;if(redo)editor.redo();else editor.undo();}
  private void find(){if(editor==null)return;findBar.setVisibility(findBar.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);if(findBar.getVisibility()==View.VISIBLE){findQuery.requestFocus();((android.view.inputmethod.InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(findQuery,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);}}
  private void globalSearch(){
    LinearLayout form=new LinearLayout(activity);form.setOrientation(1);EditText query=new EditText(activity);query.setHint("Search every project text file");CheckBox exact=new CheckBox(activity);exact.setText("Case sensitive");exact.setTextColor(TEXT);form.addView(query);form.addView(exact);
    new AlertDialog.Builder(activity).setTitle("Project search · Ctrl+Shift+F").setView(form).setPositiveButton("Search",(d,w)->{String term=query.getText().toString();boolean caseSensitive=exact.isChecked();if(term.isEmpty()){status.setText("Enter project search text");return;}status.setText("Searching project…");work(()->searchProject(term,caseSensitive));}).setNegativeButton("Cancel",null).show();
  }
  private void searchProject(String term,boolean caseSensitive)throws Exception{
    File project=StudioFiles.project(host.privateDirectory());String needle=caseSensitive?term:term.toLowerCase(Locale.ROOT);ArrayList<SearchHit> hits=new ArrayList<>();int scanned=0;
    for(File file:StudioFiles.files(project)){if(hits.size()>=500)break;String relative=StudioFiles.relative(project,file);String lower=relative.toLowerCase(Locale.ROOT);if(lower.contains("/.git/")||lower.endsWith(".apk")||lower.endsWith(".jar")||lower.endsWith(".zip")||lower.endsWith(".dex")||file.length()>500000)continue;byte[] raw=StudioFiles.read(file,500000);boolean binary=false;for(int i=0;i<Math.min(raw.length,4096);i++)if(raw[i]==0){binary=true;break;}if(binary)continue;scanned++;try(BufferedReader reader=new BufferedReader(new InputStreamReader(new ByteArrayInputStream(raw),StandardCharsets.UTF_8))){String line;int number=0;while((line=reader.readLine())!=null){number++;String hay=caseSensitive?line:line.toLowerCase(Locale.ROOT);if(hay.contains(needle)){hits.add(new SearchHit(relative,number,line.length()>240?line.substring(0,240):line));if(hits.size()>=500)break;}}}}
    final int files=scanned;ui(()->showSearchResults(term,files,hits));
  }
  private void showSearchResults(String term,int files,List<SearchHit> hits){
    LinearLayout box=new LinearLayout(activity);box.setOrientation(1);box.addView(label(hits.size()+" matches in "+files+" files"+(hits.size()>=500?" · first 500 shown":""),12));ListView list=new ListView(activity);ArrayAdapter<SearchHit> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_list_item_1,hits);list.setAdapter(adapter);box.addView(list,new LinearLayout.LayoutParams(-1,dp(460)));AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Project search · "+term).setView(box).setPositiveButton("New search",(d,w)->globalSearch()).setNegativeButton("Close",null).create();list.setOnItemClickListener((p,v,index,id)->{SearchHit hit=adapter.getItem(index);dialog.dismiss();openFile(hit.path,hit.line);});dialog.show();status.setText(hits.size()+" project matches");
  }
  private void closeFile(){Buffer b=buffers.get(current);if(b==null)return;final String path=current;Runnable close=()->{buffers.remove(path);if(buffers.isEmpty())draftProject="";current=buffers.isEmpty()?"":buffers.keySet().iterator().next();show("Editor");};
    if(b.dirty())new AlertDialog.Builder(activity).setTitle("Unsaved changes").setMessage(path).setPositiveButton("Save & close",(d,w)->work(()->{saveOne(path);ui(close);})).setNeutralButton("Discard",(d,w)->close.run()).setNegativeButton("Cancel",null).show();else close.run();
  }
  private void saveOne(String path)throws Exception{final Buffer b;final String text;synchronized(buffers){b=buffers.get(path);if(b==null||!b.dirty())return;text=b.text;}if(!draftProject.isEmpty()&&!draftProject.equals(StudioFiles.project(host.privateDirectory()).getCanonicalPath()))throw new IOException("This draft belongs to another source workspace; reopen that version before saving");call(obj("action","write","path",path,"text",text));ui(()->{b.saved=text;if(b.button!=null)b.button.setText(shortName(path)+(b.dirty()?" *":""));status.setText("Saved "+path);saveDrafts();});}
  private void saveAllNetwork()throws Exception{List<String> paths;synchronized(buffers){paths=new ArrayList<>(buffers.keySet());}for(String path:paths)saveOne(path);}
  private void openError(){String output=state.optString("log");Matcher m=Pattern.compile("([^\\s:]+\\.java):(\\d+):").matcher(output);if(m.find()){String path=m.group(1);int index=path.indexOf("/app/");if(index>=0)path=path.substring(index+1);else{index=path.indexOf("/modules/");if(index>=0)path=path.substring(index+1);}openFile(path);}else status.setText("No Java error location in current log");}
  private void saveDrafts(){if(host==null)return;try{JSONObject root=new JSONObject();JSONArray files=new JSONArray();synchronized(buffers){for(Map.Entry<String,Buffer> e:buffers.entrySet())files.put(obj("path",e.getKey(),"text",e.getValue().text,"saved",e.getValue().saved));}root.put("format",2).put("files",files).put("current",current).put("project",draftProject);android.util.AtomicFile atomic=new android.util.AtomicFile(new File(host.privateDirectory(),"drafts.json"));FileOutputStream out=atomic.startWrite();try{out.write(root.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(out);}catch(Exception e){atomic.failWrite(out);throw e;}}catch(Exception e){error(e);}}
  private void restoreDrafts()throws Exception{File file=new File(host.privateDirectory(),"drafts.json");if(!file.isFile()&&!new File(file.getPath()+".bak").isFile())return;JSONObject data;try(InputStream in=new android.util.AtomicFile(file).openRead()){data=new JSONObject(new String(read(in,64000000),StandardCharsets.UTF_8));}boolean legacy=data.optInt("format",1)<2;JSONArray files=data.getJSONArray("files");for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);Buffer b=new Buffer();b.text=f.getString("text");b.saved=f.getString("saved");if(legacy&&b.text.isEmpty()&&!b.saved.isEmpty())b.text=b.saved;buffers.put(f.getString("path"),b);}current=data.optString("current","");draftProject=data.optString("project","");}
  private JSONObject call(JSONObject request)throws Exception{
    String action=request.getString("action");File data=host.privateDirectory();
    if(action.equals("status")){
      JSONObject result=new JSONObject(host.taskStatus());
      try{result.put("project",StudioFiles.project(data).getPath());}catch(Exception ignored){}
      result.put("freeBytes",data.getUsableSpace()).put("signingConfigured",new File(data,"signing.keystore").isFile()).put("toolsReady",new File(data,"tools/ready.json").isFile());
      JSONArray outputs=new JSONArray();File[] files=new File(data,"outputs").listFiles();if(files!=null){Arrays.sort(files,Comparator.comparing(File::getName).reversed());for(File f:files)if(f.getName().endsWith(".apk"))outputs.put(obj("name",f.getName(),"size",f.length()));}return result.put("outputs",outputs);
    }
    if(action.equals("cancel")){host.cancelTask();return obj("ok",true);}
    if(action.equals("source")||action.equals("importFolder")||action.equals("setup")||action.equals("shellSetup")||action.equals("build")||action.startsWith("github")){
      if(new JSONObject(host.taskStatus()).optBoolean("busy"))throw new IOException("Another tool task is running");
      if(action.equals("source")&&!buffers.isEmpty())throw new IOException("Save and close editor tabs before selecting a source workspace");
      host.startTask(request.toString());return obj("ok",true);
    }
    if(action.equals("signing")){
      byte[] key=Base64.getDecoder().decode(request.getString("keystore"));if(key.length>200000)throw new IOException("Keystore too large");
      StudioFiles.write(new File(data,"signing.keystore"),key);StudioFiles.write(new File(data,"signing.json"),obj("alias",request.getString("alias"),"password",request.getString("password"),"keyPassword",request.getString("keyPassword")).toString().getBytes(StandardCharsets.UTF_8));return obj("ok",true);
    }
    File project=StudioFiles.project(data);
    if(action.equals("files")){JSONArray files=new JSONArray();for(File f:StudioFiles.files(project))files.put(StudioFiles.relative(project,f));return obj("files",files);}
    if(action.equals("read"))return obj("text",new String(StudioFiles.read(StudioFiles.safe(project,request.getString("path")),500000),StandardCharsets.UTF_8));
    if(action.equals("write")){
      if(new JSONObject(host.taskStatus()).optBoolean("busy"))throw new IOException("Wait for the current tool task before saving");
      File target=StudioFiles.safe(project,request.getString("path"));byte[] text=request.getString("text").getBytes(StandardCharsets.UTF_8);if(text.length>500000)throw new IOException("File exceeds editor size limit");
      if(target.isFile()){File backup=new File(data,"backups/"+System.currentTimeMillis()+"-"+target.getName());backup.getParentFile().mkdirs();java.nio.file.Files.copy(target.toPath(),backup.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}StudioFiles.write(target,text);return obj("ok",true);
    }
    if(action.equals("export")){File snapshot=StudioFiles.snapshot(project,data),output=new File(data,"outputs/oTraiL-source-"+System.currentTimeMillis()+".zip");output.getParentFile().mkdirs();java.nio.file.Files.copy(snapshot.toPath(),output.toPath());return obj("name",output.getName());}
    throw new IOException("Unknown in-app operation");
  }
  private void download(String name,boolean install,boolean export)throws Exception{
    if(!name.matches("oTraiL-(phone-[0-9-]+\\.apk|source-[0-9]+\\.zip)"))throw new IOException("Invalid output filename");
    File target=new File(host.privateDirectory(),"outputs/"+name);if(!target.isFile())throw new IOException("Build output not found");
    if(install)ui(()->{try{host.installBuiltApk(target);}catch(Exception e){error(e);}});
    if(export){exportFile=target;exportMime=name.endsWith(".apk")?"application/vnd.android.package-archive":"application/zip";ui(()->activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(exportMime).putExtra(Intent.EXTRA_TITLE,name).addCategory(Intent.CATEGORY_OPENABLE),EXPORT));}
  }
  @Override public void task(File packageFile,File privateDirectory,String request,Progress progress)throws Exception{StudioTasks.run(packageFile,privateDirectory,request,progress);}
  @Override public void activityResult(int request,int result,Intent data){if(result!=Activity.RESULT_OK||data==null||data.getData()==null)return;final Uri uri=data.getData();
    if(request==EXPORT&&exportFile!=null){final File file=exportFile;work(()->{try(InputStream in=new FileInputStream(file);OutputStream out=activity.getContentResolver().openOutputStream(uri)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}ui(()->status.setText("Saved "+file.getName()));});}
    if(request==KEYSTORE){LinearLayout form=new LinearLayout(activity);form.setOrientation(1);EditText alias=new EditText(activity);alias.setHint("Key alias (for example androiddebugkey)");EditText password=new EditText(activity);password.setHint("Store password");password.setInputType(129);EditText keyPassword=new EditText(activity);keyPassword.setHint("Key password (blank = store password)");keyPassword.setInputType(129);form.addView(alias);form.addView(password);form.addView(keyPassword);
      new AlertDialog.Builder(activity).setTitle("Import signing keystore").setView(form).setPositiveButton("Import privately",(d,w)->{final String a=alias.getText().toString(),p=password.getText().toString(),kp=keyPassword.getText().toString();work(()->{byte[] key;try(InputStream in=activity.getContentResolver().openInputStream(uri)){key=read(in,200000);}call(obj("action","signing","keystore",Base64.getEncoder().encodeToString(key),"alias",a,"password",p,"keyPassword",kp.isEmpty()?p:kp));ui(()->status.setText("Signing keystore imported privately on this phone"));});}).setNegativeButton("Cancel",null).show();
    }
  }
  private byte[] read(InputStream in,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("File too large");out.write(b,0,n);}return out.toByteArray();}
  @Override public void resume(){running=true;handler.removeCallbacks(poll);handler.post(poll);}
  @Override public void pause(){running=false;handler.removeCallbacks(poll);handler.removeCallbacks(draftSave);saveDrafts();}
  @Override public void close(){pause();exitShell();closed=true;io.shutdownNow();shellIo.shutdown();}
  private static class SimpleWatcher implements TextWatcher{public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){}public void afterTextChanged(Editable e){}}
}
