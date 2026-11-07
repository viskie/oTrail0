package in.otrail.extensions.mod1;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.text.style.ForegroundColorSpan;
import android.view.*;
import android.widget.*;
import in.otrail.AppStyle;
import in.otrail.UiConfig;
import in.otrail.modules.ModuleV1;
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
  private Activity activity;private Host host;private LinearLayout root,content,fileTabs;private TextView status,log;
  private ProgressBar progress;private EditText editor;private CheckBox offline,licenses;
  private final ExecutorService io=Executors.newSingleThreadExecutor();private final Handler handler=new Handler(Looper.getMainLooper());
  private final LinkedHashMap<String,Buffer> buffers=new LinkedHashMap<>();
  private String current="",draftProject="",tab="Project";private JSONObject metadata,state=new JSONObject();
  private boolean running,closed,polling,suppress;private File exportFile;private String exportMime;
  private static final int EXPORT=710,KEYSTORE=711;
  private static final int BG=0xff10101b,TEXT=0xff40e0d0;
  private final Runnable poll=new Runnable(){public void run(){if(!running||closed)return;poll();handler.postDelayed(this,2000);}};
  private static final class Buffer {String text="",saved="";long undoTime;final Deque<String> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();Button button;boolean dirty(){return !text.equals(saved);}}
  @Override public String id(){return "mod1";}
  @Override public View create(Activity a,Host h)throws Exception{
    activity=a;host=h;
    metadata=new JSONObject(asset("studio.json"));try{mergeMetadata(new JSONObject(host.selectedToolchain()));}catch(Exception ignored){}restoreDrafts();
    root=new LinearLayout(a);root.setOrientation(1);root.setPadding(dp(8),dp(6),dp(8),dp(6));root.setBackgroundColor(BG);
    LinearLayout top=row();top.addView(button("← Modules",()->activity.finish()),new LinearLayout.LayoutParams(0,dp(44),1));top.addView(label("GOD · Source Studio",18),new LinearLayout.LayoutParams(0,-2,2));root.addView(top);
    HorizontalScrollView tabs=new HorizontalScrollView(a);LinearLayout names=row();for(String name:new String[]{"Project","Editor","Build","APKs","Tools"})names.addView(button(name,()->show(name)));tabs.addView(names);root.addView(tabs);
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
    saveDrafts();tab=name;content.removeAllViews();editor=null;fileTabs=null;log=null;
    if(name.equals("Editor")){editorScreen();return;}
    ScrollView scroll=new ScrollView(activity);LinearLayout panel=new LinearLayout(activity);panel.setOrientation(1);scroll.addView(panel);content.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
    if(name.equals("Project")){
      panel.addView(label("Project source stays in a separate private workspace. Saved edits are backed up; builds take source snapshots.",14));
      panel.addView(label("No external app or build server is used.",12));
      try{JSONArray sources=metadata.getJSONArray("sources");for(int i=0;i<sources.length();i++){final JSONObject source=sources.getJSONObject(i);panel.addView(button("Download source · "+source.getString("version"),()->work(()->{call(obj("action","source","source",source));ui(()->status.setText("Source download started; progress below"));})));}}catch(Exception e){error(e);}
      panel.addView(button("Browse project files",this::browse));
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
  }
  private void mergeMetadata(JSONObject update)throws Exception{for(Iterator<String> it=update.keys();it.hasNext();){String key=it.next();metadata.put(key,update.get(key));}}
  private void toolsScreen(LinearLayout panel){
    panel.addView(label("In-app toolchains · no Termux, external app, shell or build server. Compiler code, SDK classpath and verified native-library template download separately from the APK.",14));
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
    panel.addView(label("Add the exported public key to your GitHub account or repository deploy keys (enable write access for publishing). Then configure and test here. Publishing commits named APKs under releases/; no GitHub Releases workflow. Initial Git synchronization can be large because this repository contains historical APK files.",12));
    panel.addView(label("Workspace: "+state.optString("project","not selected")+"\nFree: "+String.format(Locale.US,"%.2f GB",host.privateDirectory().getUsableSpace()/1073741824.0)+"\nTools downloaded: "+state.optBoolean("toolsReady")+" · APK signing key: "+state.optBoolean("signingConfigured"),12));
  }
  private void chooseToolchain(){work(()->{JSONArray chains=new JSONArray(host.toolchains(true));String[] titles=new String[chains.length()];for(int i=0;i<titles.length;i++){JSONObject chain=chains.getJSONObject(i);long total=0;JSONArray artifacts=chain.getJSONArray("tools");for(int j=0;j<artifacts.length();j++)total+=artifacts.getJSONObject(j).getLong("sizeBytes");titles[i]=chain.getString("name")+" · "+chain.getString("version")+String.format(Locale.US," · %.1f MB",total/1048576.0);}ui(()->new AlertDialog.Builder(activity).setTitle("Verified toolchains").setItems(titles,(d,index)->work(()->{host.selectToolchain(chains.getJSONObject(index).getString("id"));final JSONObject selected=new JSONObject(host.selectedToolchain());ui(()->{try{mergeMetadata(selected);show("Tools");}catch(Exception e){error(e);}});})).setNegativeButton("Cancel",null).show());});}
  private void githubConfig(){LinearLayout form=new LinearLayout(activity);form.setOrientation(1);EditText repo=new EditText(activity),branch=new EditText(activity);repo.setHint("owner/repository");repo.setText("viskie/oTrail0");branch.setHint("Branch");branch.setText("main");form.addView(repo);form.addView(branch);
    new AlertDialog.Builder(activity).setTitle("GitHub repository").setMessage("Add GOD-github.pub to GitHub first. APK publication never force-pushes.").setView(form).setPositiveButton("Save & test key",(d,w)->{final String repository=repo.getText().toString().trim(),ref=branch.getText().toString().trim();work(()->{if(!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")||!ref.matches("[A-Za-z0-9_.-]+"))throw new IOException("Invalid repository or branch");StudioFiles.write(new File(host.privateDirectory(),"github.json"),obj("repository",repository,"branch",ref,"verified",false).toString().getBytes(StandardCharsets.UTF_8));call(obj("action","githubCheck"));});}).setNegativeButton("Cancel",null).show();
  }
  private void publish(String file){EditText name=new EditText(activity);name.setText(file);new AlertDialog.Builder(activity).setTitle("Publish named APK").setMessage("Commit to the configured GitHub repository's releases/ folder using the phone SSH key.").setView(name).setPositiveButton("Publish",(d,w)->{final String release=name.getText().toString().trim();work(()->call(obj("action","githubPublish","file",file,"releaseName",release)));}).setNegativeButton("Cancel",null).show();}
  private void poll(){if(polling)return;polling=true;work(()->{try{
    JSONObject result=call(obj("action","status"));ui(()->{state=result;status.setText(result.optString("phase")+" · "+(result.optBoolean("busy")?"Running":"Idle"));progress.setIndeterminate(result.optBoolean("busy")&&result.optInt("percent")==0);progress.setProgress(result.optInt("percent"));if(log!=null)log.setText(result.optString("log"));});
  }catch(Exception ignored){}finally{polling=false;}});}
  private void browse(){work(()->{
    JSONArray files=call(obj("action","files")).getJSONArray("files");final ArrayList<String> paths=new ArrayList<>();for(int i=0;i<files.length();i++)paths.add(files.getString(i));
    ui(()->{LinearLayout box=new LinearLayout(activity);box.setOrientation(1);EditText filter=new EditText(activity);filter.setHint("Filter filenames");box.addView(filter);ListView list=new ListView(activity);ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_list_item_1,new ArrayList<>(paths));list.setAdapter(adapter);box.addView(list,new LinearLayout.LayoutParams(-1,dp(330)));
      AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Project files").setView(box).setNegativeButton("Close",null).create();
      filter.addTextChangedListener(new SimpleWatcher(){public void afterTextChanged(Editable text){adapter.getFilter().filter(text);}});
      list.setOnItemClickListener((p,v,index,id)->{String path=adapter.getItem(index);dialog.dismiss();openFile(path);});dialog.show();});
  });}
  private void openFile(String path){
    synchronized(buffers){if(buffers.containsKey(path)){current=path;show("Editor");return;}}
    work(()->{final String project=StudioFiles.project(host.privateDirectory()).getCanonicalPath();
      if(!draftProject.isEmpty()&&!draftProject.equals(project))throw new IOException("Close saved editor tabs before switching source workspaces");
      final String text=call(obj("action","read","path",path)).getString("text");ui(()->{draftProject=project;Buffer b=new Buffer();b.text=b.saved=text;synchronized(buffers){buffers.put(path,b);}current=path;show("Editor");});});
  }
  private void editorScreen(){
    HorizontalScrollView tabs=new HorizontalScrollView(activity);fileTabs=row();tabs.addView(fileTabs);content.addView(tabs);
    synchronized(buffers){for(Map.Entry<String,Buffer> entry:buffers.entrySet()){String path=entry.getKey();Buffer b=entry.getValue();b.button=button(shortName(path)+(b.dirty()?" *":""),()->{current=path;show("Editor");});fileTabs.addView(b.button);}}
    content.addView(label(current.isEmpty()?"Open a file from Project":current,12));
    HorizontalScrollView tools=new HorizontalScrollView(activity);LinearLayout actions=row();
    actions.addView(button("Files",this::browse));actions.addView(button("Save",()->work(()->saveOne(current))));actions.addView(button("Save all",()->work(()->saveAllNetwork())));
    actions.addView(button("Undo",()->undo(false)));actions.addView(button("Redo",()->undo(true)));actions.addView(button("Find",this::find));actions.addView(button("Close file",this::closeFile));tools.addView(actions);content.addView(tools);
    editor=new EditText(activity);editor.setTextColor(TEXT);editor.setBackgroundColor(0xff181827);editor.setTextSize(14);editor.setTypeface(Typeface.MONOSPACE);editor.setGravity(Gravity.TOP|Gravity.START);editor.setSingleLine(false);editor.setHorizontallyScrolling(true);editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    Buffer b=buffers.get(current);editor.setText(b==null?"":b.text);editor.setEnabled(b!=null);content.addView(editor,new LinearLayout.LayoutParams(-1,0,1));highlight();
    editor.addTextChangedListener(new SimpleWatcher(){
      public void beforeTextChanged(CharSequence s,int start,int count,int after){Buffer buffer=buffers.get(current);if(buffer==null||suppress)return;long now=System.currentTimeMillis();if(now-buffer.undoTime>700){buffer.undo.push(s.toString());while(buffer.undo.size()>20)buffer.undo.removeLast();buffer.undoTime=now;}buffer.redo.clear();}
      public void afterTextChanged(Editable text){Buffer buffer=buffers.get(current);if(buffer==null)return;buffer.text=text.toString();if(buffer.button!=null)buffer.button.setText(shortName(current)+(buffer.dirty()?" *":""));highlight();}
    });
  }
  private String shortName(String path){return path.substring(path.lastIndexOf('/')+1);}
  private void highlight(){if(editor==null||editor.length()>100000)return;Editable text=editor.getText();for(ForegroundColorSpan span:text.getSpans(0,text.length(),ForegroundColorSpan.class))text.removeSpan(span);
    Matcher matcher=Pattern.compile("\\b(class|public|private|protected|static|final|void|return|if|else|new|import|package|int|long|boolean|record|extends|implements|try|catch|throw)\\b").matcher(text);
    while(matcher.find())text.setSpan(new ForegroundColorSpan(0xffff40d0),matcher.start(),matcher.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
  }
  private void undo(boolean redo){Buffer b=buffers.get(current);if(b==null)return;Deque<String> from=redo?b.redo:b.undo,to=redo?b.undo:b.redo;if(from.isEmpty())return;to.push(b.text);suppress=true;editor.setText(from.pop());suppress=false;}
  private void find(){if(editor==null)return;EditText query=new EditText(activity);new AlertDialog.Builder(activity).setTitle("Find in file").setView(query).setPositiveButton("Find",(d,w)->{String needle=query.getText().toString();int at=editor.getText().toString().indexOf(needle,Math.max(0,editor.getSelectionEnd()));if(at<0)at=editor.getText().toString().indexOf(needle);if(at>=0){editor.requestFocus();editor.setSelection(at,at+needle.length());}else status.setText("Text not found");}).setNegativeButton("Cancel",null).show();}
  private void closeFile(){Buffer b=buffers.get(current);if(b==null)return;final String path=current;Runnable close=()->{buffers.remove(path);if(buffers.isEmpty())draftProject="";current=buffers.isEmpty()?"":buffers.keySet().iterator().next();show("Editor");};
    if(b.dirty())new AlertDialog.Builder(activity).setTitle("Unsaved changes").setMessage(path).setPositiveButton("Save & close",(d,w)->work(()->{saveOne(path);ui(close);})).setNeutralButton("Discard",(d,w)->close.run()).setNegativeButton("Cancel",null).show();else close.run();
  }
  private void saveOne(String path)throws Exception{final Buffer b;final String text;synchronized(buffers){b=buffers.get(path);if(b==null||!b.dirty())return;text=b.text;}if(!draftProject.isEmpty()&&!draftProject.equals(StudioFiles.project(host.privateDirectory()).getCanonicalPath()))throw new IOException("This draft belongs to another source workspace; reopen that version before saving");call(obj("action","write","path",path,"text",text));ui(()->{b.saved=text;if(b.button!=null)b.button.setText(shortName(path)+(b.dirty()?" *":""));status.setText("Saved "+path);saveDrafts();});}
  private void saveAllNetwork()throws Exception{List<String> paths;synchronized(buffers){paths=new ArrayList<>(buffers.keySet());}for(String path:paths)saveOne(path);}
  private void openError(){String output=state.optString("log");Matcher m=Pattern.compile("([^\\s:]+\\.java):(\\d+):").matcher(output);if(m.find()){String path=m.group(1);int index=path.indexOf("/app/");if(index>=0)path=path.substring(index+1);else{index=path.indexOf("/modules/");if(index>=0)path=path.substring(index+1);}openFile(path);}else status.setText("No Java error location in current log");}
  private void saveDrafts(){if(host==null)return;try{JSONObject root=new JSONObject();JSONArray files=new JSONArray();synchronized(buffers){for(Map.Entry<String,Buffer> e:buffers.entrySet())files.put(obj("path",e.getKey(),"text",e.getValue().text,"saved",e.getValue().saved));}root.put("files",files).put("current",current).put("project",draftProject);android.util.AtomicFile atomic=new android.util.AtomicFile(new File(host.privateDirectory(),"drafts.json"));FileOutputStream out=atomic.startWrite();try{out.write(root.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(out);}catch(Exception e){atomic.failWrite(out);throw e;}}catch(Exception e){error(e);}}
  private void restoreDrafts(){try{File file=new File(host.privateDirectory(),"drafts.json");if(!file.isFile())return;JSONObject data;try(InputStream in=new FileInputStream(file)){data=new JSONObject(new String(read(in,10000000),StandardCharsets.UTF_8));}JSONArray files=data.getJSONArray("files");for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);Buffer b=new Buffer();b.text=f.getString("text");b.saved=f.getString("saved");buffers.put(f.getString("path"),b);}current=data.optString("current","");draftProject=data.optString("project","");}catch(Exception e){error(e);}}
  private JSONObject call(JSONObject request)throws Exception{
    String action=request.getString("action");File data=host.privateDirectory();
    if(action.equals("status")){
      JSONObject result=new JSONObject(host.taskStatus());
      try{result.put("project",StudioFiles.project(data).getPath());}catch(Exception ignored){}
      result.put("freeBytes",data.getUsableSpace()).put("signingConfigured",new File(data,"signing.keystore").isFile()).put("toolsReady",new File(data,"tools/ready.json").isFile());
      JSONArray outputs=new JSONArray();File[] files=new File(data,"outputs").listFiles();if(files!=null){Arrays.sort(files,Comparator.comparing(File::getName).reversed());for(File f:files)if(f.getName().endsWith(".apk"))outputs.put(obj("name",f.getName(),"size",f.length()));}return result.put("outputs",outputs);
    }
    if(action.equals("cancel")){host.cancelTask();return obj("ok",true);}
    if(action.equals("source")||action.equals("setup")||action.equals("build")||action.startsWith("github")){
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
  @Override public void pause(){running=false;handler.removeCallbacks(poll);saveDrafts();}
  @Override public void close(){pause();closed=true;io.shutdownNow();}
  private static class SimpleWatcher implements TextWatcher{public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){}public void afterTextChanged(Editable e){}}
}
