package in.otrail.extensions.mod1;

import android.app.*;import android.content.*;import android.graphics.Typeface;import android.graphics.drawable.GradientDrawable;import android.net.Uri;import android.os.*;import android.provider.Settings;import android.text.Editable;import android.text.TextUtils;import android.text.TextWatcher;import android.view.*;import android.view.inputmethod.EditorInfo;import android.view.inputmethod.InputMethodManager;import android.widget.*;
import in.otrail.AppStyle;import in.otrail.UiConfig;import in.otrail.modules.ModuleV1;import org.json.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.concurrent.*;

/** Separate downloadable Debian shell module. */
public final class DebianShell implements ModuleV1 {
  private Activity activity;private Host host;private LinearLayout root;private TextView status,terminal,debug,title;private EditText command;private ScrollView terminalScroll;
  private String inputPrevious="";private boolean suppressInputWatch;
  private final Handler handler=new Handler(Looper.getMainLooper());private final ExecutorService io=Executors.newCachedThreadPool();
  private java.lang.Process process;private OutputStream input;private boolean closed,launchAttempted;private final StringBuilder transcript=new StringBuilder();
  private static final String[] KEYS={"ESC","TAB","CTRL-C","CTRL-D","CTRL-L","↑","↓","←","→"};
  private static final String[] CODES={"\u001b","\t","\u0003","\u0004","\u000c","\u001b[A","\u001b[B","\u001b[D","\u001b[C"};

  public String id(){return "mod3";}

  public View create(Activity a,Host h){
    activity=a;host=h;
    try{activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED);}catch(Exception ignored){}
    root=new LinearLayout(a);root.setOrientation(1);root.setBackgroundColor(UiConfig.MODULE_BACKGROUND);root.setPadding(dp(8),dp(6),dp(8),dp(6));
    root.addView(header());
    status=label("Preparing included Debian…",UiConfig.MODULE_LABEL_SIZE_SP,UiConfig.MODULE_MUTED);status.setMaxLines(2);status.setEllipsize(TextUtils.TruncateAt.END);root.addView(status);
    root.addView(terminalPanel(),new LinearLayout.LayoutParams(-1,0,3));
    View spacer=new View(a);spacer.setBackgroundColor(UiConfig.MODULE_BACKGROUND);root.addView(spacer,new LinearLayout.LayoutParams(-1,0,2));
    root.addView(keyStrip());
    root.addView(hiddenInput());
    debug=label("ready",9,UiConfig.MODULE_MUTED);debug.setSingleLine(true);debug.setEllipsize(TextUtils.TruncateAt.START);root.addView(debug);
    root.addView(navRow());
    setup(false);handler.post(poll);return root;
  }

  private View header(){
    LinearLayout bar=row();bar.setBackgroundColor(UiConfig.MODULE_HEADER_BACKGROUND);bar.setPadding(dp(6),0,dp(4),0);
    TextView dot=label("●",12,UiConfig.MODULE_DOT_ONLINE);dot.setPadding(dp(2),0,dp(5),0);bar.addView(dot);
    title=label("Debian Shell",UiConfig.MODULE_HEADER_SIZE_SP,UiConfig.MODULE_TEXT);title.setTypeface(Typeface.create(UiConfig.STATUS_FONT,Typeface.BOLD));bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    bar.addView(chip("REPAIR",this::repair),new LinearLayout.LayoutParams(-2,dp(UiConfig.MODULE_KEY_HEIGHT_DP)));
    bar.addView(chip("HALT",this::exit),new LinearLayout.LayoutParams(-2,dp(UiConfig.MODULE_KEY_HEIGHT_DP)));
    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(UiConfig.MODULE_HEADER_HEIGHT_DP));params.setMargins(0,0,0,dp(2));bar.setLayoutParams(params);return bar;
  }

  private View terminalPanel(){
    FrameLayout frame=new FrameLayout(activity);GradientDrawable background=new GradientDrawable();background.setColor(UiConfig.TERMINAL_BACKGROUND);background.setCornerRadius(dp(10));frame.setBackground(background);frame.setPadding(dp(7),dp(5),dp(7),dp(5));
    terminal=label("",UiConfig.TERMINAL_TEXT_SIZE_SP,UiConfig.TERMINAL_TEXT);terminal.setTypeface(Typeface.MONOSPACE);terminal.setTextIsSelectable(true);terminal.setLineSpacing(0,UiConfig.TERMINAL_LINE_SPACING);terminal.setText("Connecting to the included Debian image…");
    terminalScroll=new ScrollView(activity);terminalScroll.setFillViewport(true);terminalScroll.setVerticalScrollBarEnabled(false);terminalScroll.addView(terminal,new FrameLayout.LayoutParams(-1,-2));
    frame.addView(terminalScroll,new FrameLayout.LayoutParams(-1,-1));return frame;
  }

  private View keyStrip(){
    LinearLayout keys=row();for(int i=0;i<KEYS.length;i++){final String code=CODES[i];Button key=chip(KEYS[i],()->send(code));key.setTextSize(9);key.setTypeface(Typeface.MONOSPACE);    keys.addView(key,new LinearLayout.LayoutParams(0,dp(UiConfig.MODULE_KEY_HEIGHT_DP),1));}
    keys.setPadding(0,dp(2),0,dp(2));return keys;
  }

  private View hiddenInput(){
    command=new EditText(activity);command.setSingleLine(true);command.setTextColor(UiConfig.TERMINAL_INPUT_TEXT);command.setTypeface(Typeface.MONOSPACE);command.setTextSize(UiConfig.TERMINAL_TEXT_SIZE_SP);command.setBackgroundColor(0x00000000);command.setPadding(0,0,0,0);command.setAlpha(0f);command.setImeOptions(EditorInfo.IME_ACTION_SEND|EditorInfo.IME_FLAG_NO_EXTRACT_UI);command.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    command.addTextChangedListener(new TextWatcher(){
      public void beforeTextChanged(CharSequence s,int start,int count,int after){}
      public void onTextChanged(CharSequence s,int start,int before,int count){}
      public void afterTextChanged(Editable s){
        if(suppressInputWatch)return;
        String now=s.toString();
        if(now.equals(inputPrevious))return;
        if(now.startsWith(inputPrevious)){
          String added=now.substring(inputPrevious.length());
          if(!added.isEmpty())send(added);
        }else if(inputPrevious.startsWith(now)){
          int removed=inputPrevious.length()-now.length();
          StringBuilder bs=new StringBuilder();
          for(int i=0;i<removed;i++)bs.append('\u007f');
          if(bs.length()>0)send(bs.toString());
        }else{
          StringBuilder bs=new StringBuilder();
          for(int i=0;i<inputPrevious.length();i++)bs.append('\u007f');
          send(bs.toString()+now);
        }
        inputPrevious=now;
      }
    });
    command.setOnEditorActionListener((v,id,event)->{submit();return true;});
    command.setFocusable(true);command.setFocusableInTouchMode(true);
    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(1));command.setLayoutParams(params);return command;
  }

  private View navRow(){
    LinearLayout nav=row();String[][] items={{"▣ FILES","scp"},{"</> GOD","god"},{">_ START","start"},{"⚙ STORAGE","storage"},{"↻ REINSTALL","repair"}};
    for(String[] item:items){Button b=chip(item[0],()->{if(item[1].equals("scp"))scpSync();else if(item[1].equals("god"))activity.finish();else if(item[1].equals("start"))startShell();else if(item[1].equals("storage"))storage();else repair();});b.setTextSize(9);nav.addView(b,new LinearLayout.LayoutParams(0,dp(UiConfig.MODULE_NAV_HEIGHT_DP),1));}
    return nav;
  }

  private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
  private LinearLayout row(){LinearLayout l=new LinearLayout(activity);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
  private TextView label(String s,int size,int color){TextView v=new TextView(activity);v.setText(s);v.setTextColor(color);v.setTextSize(size);v.setPadding(dp(4),dp(2),dp(4),dp(2));return v;}
  private Button chip(String s,Runnable r){
    Button b=new Button(activity);b.setText(s);b.setTextSize(10);b.setTextColor(UiConfig.MODULE_BUTTON_TEXT);b.setAllCaps(false);b.setSingleLine(true);b.setEllipsize(TextUtils.TruncateAt.END);b.setMinHeight(0);b.setMinimumHeight(0);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(6),0,dp(6),0);
    GradientDrawable frame=new GradientDrawable();frame.setColor(UiConfig.TERMINAL_KEY_BACKGROUND);frame.setCornerRadius(dp(7));frame.setStroke(dp(1),UiConfig.MODULE_DIVIDER);b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff),frame,null));
    b.setOnClickListener(v->{debug("RUN · "+s);try{r.run();}catch(Throwable error){debug("FAIL · "+s+" · "+error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage()));}});return b;
  }
  private void debug(String message){post(()->{if(debug!=null)debug.setText(new java.text.SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date())+" · "+message);});}

  private void setup(boolean force){io.execute(()->{try{File bash=new File(host.privateDirectory(),"debian/rootfs/usr/bin/bash");if(!force&&bash.isFile()){if(!launchAttempted)launch();return;}JSONObject state=new JSONObject(host.taskStatus());if(!state.optBoolean("busy"))host.startTask(new JSONObject().put("action","shellSetup").put("force",force).toString());}catch(Exception e){show("Setup failed: "+e.getMessage());debug("FAIL · setup · "+e);}});}
  private void repair(){launchAttempted=false;setup(true);}
  private void startShell(){io.execute(()->{try{launchAttempted=false;launch();}catch(Exception e){show("Launch failed: "+e.getMessage());debug("FAIL · launch · "+e);}});}

  private final Runnable poll=this::pollNow;
  private void pollNow(){
    if(closed)return;
    try{JSONObject state=new JSONObject(host.taskStatus());show(state.optString("phase")+(state.optBoolean("busy")?" · "+state.optInt("percent")+"%":""));if(!state.optBoolean("busy")&&!launchAttempted&&new File(host.privateDirectory(),"debian/rootfs/usr/bin/bash").isFile()&&process==null)io.execute(()->{try{launch();}catch(Exception e){show("Launch failed: "+e.getMessage());debug("FAIL · auto launch · "+e);}});}catch(Exception error){debug("FAIL · status poll · "+error);}
    handler.postDelayed(poll,1500);
  }

  /** Runs bash under a pty: script(1) allocates the terminal, so job control works and the
   *  "cannot set terminal process group" warning no longer appears. */
  private synchronized void launch()throws Exception{
    if(process!=null)return;launchAttempted=true;
    File proot=new File(activity.getApplicationInfo().nativeLibraryDir,"libproot.so"),fs=new File(host.privateDirectory(),"debian/rootfs");
    if(!proot.canExecute())throw new IOException("Executable PRoot is missing from oTraiL");
    if(!new File(fs,"usr/bin/bash").isFile())throw new IOException("Debian image is not installed yet");
    List<String> c=new ArrayList<>(Arrays.asList(proot.getPath(),"--kill-on-exit","--link2symlink","-0","-r",fs.getCanonicalPath(),"-b","/dev","-b","/proc","-b","/sys"));
    bind(c,new File("/system"),"/system");bind(c,new File("/vendor"),"/vendor");bind(c,new File("/apex"),"/apex");
    bind(c,new File(activity.getApplicationInfo().dataDir),"/app-data");bind(c,host.privateDirectory(),"/shell-data");bind(c,activity.getExternalFilesDir(null),"/external-app");
    if(Build.VERSION.SDK_INT<30||Environment.isExternalStorageManager())bind(c,Environment.getExternalStorageDirectory(),"/shared-storage");
    c.add("-w");c.add("/root");
    c.add("/usr/bin/script");c.add("-qfec");c.add("/usr/bin/bash --noprofile --norc -i");c.add("/dev/null");
    ProcessBuilder b=new ProcessBuilder(c).redirectErrorStream(true);
    b.environment().put("HOME","/root");b.environment().put("SHELL","/usr/bin/bash");b.environment().put("TERM","xterm-256color");
    b.environment().put("PATH","/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");b.environment().put("LANG","C.UTF-8");
    b.environment().put("PROOT_TMP_DIR",activity.getCacheDir().getPath());
    File loader=new File(activity.getApplicationInfo().nativeLibraryDir,"libprootloader.so");
    if(!loader.canExecute())throw new IOException("Executable PRoot loader is missing from oTraiL");
    b.environment().put("PROOT_LOADER",loader.getPath());
    process=b.start();input=process.getOutputStream();
    append("[Debian ready · typing echoes here · exit leaves the shell]\n");
    java.lang.Process p=process;io.execute(()->read(p));io.execute(()->waitFor(p));
    post(this::focusInput);
  }

  private void bind(List<String> c,File f,String guest)throws IOException{if(f==null||!f.exists())return;c.add("-b");c.add(f.getCanonicalPath()+":"+guest);}

  private void read(java.lang.Process p){
    try(InputStream in=p.getInputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))>=0)append(new String(b,0,n,StandardCharsets.UTF_8));}
    catch(Exception e){append("\n[output error: "+e+"]\n");debug("FAIL · shell output · "+e);}
  }

  private void waitFor(java.lang.Process p){
    try{int code=p.waitFor();synchronized(this){if(process==p){process=null;input=null;}}append("\n[Shell exited "+code+(code==159?" · Android seccomp blocked PRoot; tap ↻ REINSTALL":"")+"]\n");if(code!=0)debug("shell exit code "+code);}
    catch(InterruptedException e){Thread.currentThread().interrupt();debug("FAIL · shell wait interrupted");}
  }

  private void submit(){send("\n");suppressInputWatch=true;if(command!=null){command.setText("");inputPrevious="";}suppressInputWatch=false;focusInput();}

  private void send(String text){
    OutputStream out=input;
    if(out==null){show("Shell is not running · tap ▶ START");focusInput();return;}
    io.execute(()->{try{out.write(text.getBytes(StandardCharsets.UTF_8));out.flush();}catch(Exception e){show(e.getMessage());debug("FAIL · send · "+e);}});
  }

  private void focusInput(){if(command==null||closed)return;try{command.requestFocus();command.setSelection(command.getText().length());InputMethodManager imm=(InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);if(imm!=null)imm.showSoftInput(command,InputMethodManager.SHOW_FORCED);}catch(Exception ignored){}}

  private void append(String chunk){
    String clean=clean(chunk);
    final String display;
    synchronized(transcript){
      int before=transcript.length();
      transcript.append(clean);
      if(transcript.length()>UiConfig.TERMINAL_SCROLLBACK_CHARS)transcript.delete(0,transcript.length()-UiConfig.TERMINAL_SCROLLBACK_CHARS);
      if(transcript.length()==before)return;
      display=transcript.toString();
    }
    post(()->{if(terminal==null)return;terminal.setText(display);scrollToEnd();});
  }

  /** Removes colour/OSC escapes and normalises carriage returns from the pty. */
  private String clean(String value){
    String text=value.replaceAll("\u001B\\][^\u0007]*(?:\u0007|\u001B\\\\)","").replaceAll("\u001B\\[[0-?]*[ -/]*[@-~]","").replaceAll("\u001B[@-_]","");
    return text.replace("\r\n","\n").replace('\r','\n');
  }

  private void scrollToEnd(){ScrollView scroll=terminalScroll;if(scroll==null)return;scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));}

  private void exit(){if(input!=null)send("exit\n");else if(activity!=null)activity.finish();}
  private void show(String s){post(()->{if(status!=null)status.setText(s);});}
  private void post(Runnable action){Activity a=activity;if(a!=null&&!closed)a.runOnUiThread(()->{if(!closed&&!a.isDestroyed())action.run();});}

  private void storage(){
    if(Build.VERSION.SDK_INT<30)return;
    try{activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+activity.getPackageName())));}
    catch(Exception e){try{activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));}catch(Exception ignored){}}
  }

  private void scpSync(){
    try{File config=new File(activity.getFilesDir(),"modules/data/mod1/scp.json");JSONObject s=StudioFiles.json(config);
      String remote=s.getString("user")+"@"+s.getString("host")+":"+s.getString("path"),destination="/app-data/files/modules/data/mod1/projects/scp-workspace";
      send("mkdir -p "+quote(destination)+" && scp -r "+quote(remote)+"/. "+quote(destination)+"/ && printf '%s' '{\"path\":\"/data/data/"+activity.getPackageName()+"/files/modules/data/mod1/projects/scp-workspace\"}' > /app-data/files/modules/data/mod1/project.json\n");
    }catch(Exception e){show("Configure SCP in GOD first: "+e.getMessage());debug("FAIL · SCP · "+e);}
  }
  private String quote(String s){return "'"+s.replace("'","'\\''")+"'";}

  public void task(File pkg,File data,String request,Progress progress)throws Exception{StudioTasks.run(pkg,data,request,progress);}
  public void close(){closed=true;handler.removeCallbacks(poll);java.lang.Process p=process;if(p!=null)p.destroy();io.shutdownNow();activity=null;host=null;}
}
