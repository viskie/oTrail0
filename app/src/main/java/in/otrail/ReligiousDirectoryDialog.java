package in.otrail;
import android.app.*;
import android.view.*;
import android.widget.*;
import android.text.InputType;
import android.text.InputFilter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Search stays in the same app; only confirmed selections start navigation. */
final class ReligiousDirectoryDialog {
  static void show(Activity activity,Consumer<DestinationSearch.Result> navigate){
    ExecutorService worker=Executors.newSingleThreadExecutor();LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);int pad=Math.round(8*activity.getResources().getDisplayMetrics().density);root.setPadding(pad,pad,pad,pad);
    TextView note=new TextView(activity);note.setText("Loading bundled India directory…");root.addView(note);
    LinearLayout filters=new LinearLayout(activity);Spinner state=new Spinner(activity),religion=new Spinner(activity);filters.addView(state,new LinearLayout.LayoutParams(0,-2,1));filters.addView(religion,new LinearLayout.LayoutParams(0,-2,1));root.addView(filters);
    String[] religions={"All religions","Hindu","Muslim","Christian","Buddhist","Sikh","Jain","Other / unspecified"},ids={"","hindu","muslim","christian","buddhist","sikh","jain","other"};religion.setAdapter(AppStyle.choices(activity,Arrays.asList(religions)));
    LinearLayout fields=new LinearLayout(activity);EditText city=field(activity,"City / locality / subdistrict"),pin=field(activity,"PIN first 3 digits");pin.setInputType(InputType.TYPE_CLASS_NUMBER);pin.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});fields.addView(city,new LinearLayout.LayoutParams(0,-2,2));fields.addView(pin,new LinearLayout.LayoutParams(0,-2,1));root.addView(fields);
    EditText query=field(activity,"Place name");root.addView(query);LinearLayout controls=new LinearLayout(activity);Button search=button(activity,"Search"),previous=button(activity,"Previous"),next=button(activity,"Next");for(Button b:List.of(search,previous,next))controls.addView(b,new LinearLayout.LayoutParams(0,-2,1));root.addView(controls);
    ListView list=new ListView(activity);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
    AlertDialog dialog=new AppDialogs(activity).setTitle("Religious places · India").setView(root).setNegativeButton("Close",null).create();int[] offset={0},ticket={0};List<String> states=new ArrayList<>(List.of("All states"));java.util.concurrent.atomic.AtomicReference<List<ReligiousDirectory.Place>> entries=new java.util.concurrent.atomic.AtomicReference<>(List.of());
    Runnable run=()->{int request=++ticket[0],start=offset[0];String region=state.getSelectedItemPosition()>0?states.get(state.getSelectedItemPosition()):"",faith=ids[Math.max(0,religion.getSelectedItemPosition())],cityText=city.getText().toString().trim(),prefix=pin.getText().toString().trim(),name=query.getText().toString().trim();search.setEnabled(false);previous.setEnabled(false);next.setEnabled(false);worker.execute(()->{try{ReligiousDirectory.Page result=ReligiousDirectory.search(activity,region,faith,cityText,prefix,name,start);activity.runOnUiThread(()->{if(!dialog.isShowing()||request!=ticket[0])return;search.setEnabled(true);entries.set(result.places());list.setAdapter(rows(activity,result.places().stream().map(p->p.displayName()+"\n"+p.detail()+(p.date().isBlank()?"":"\nSource date: "+p.date())).collect(java.util.stream.Collectors.toList())));previous.setEnabled(start>0);next.setEnabled(start+50<result.count());note.setText(result.count()+" results · "+(result.count()==0?0:start+1)+"–"+Math.min(start+50,result.count())+"\nDates/prominence only where supplied by source. Missing PINs are not guessed. © OpenStreetMap contributors · ODbL");});}catch(Exception error){activity.runOnUiThread(()->{if(dialog.isShowing()){search.setEnabled(true);note.setText(error.getMessage());}});}});};
    search.setOnClickListener(v->{offset[0]=0;run.run();});previous.setOnClickListener(v->{offset[0]=Math.max(0,offset[0]-50);run.run();});next.setOnClickListener(v->{offset[0]+=50;run.run();});
    list.setOnItemClickListener((parent,view,index,id)->{ReligiousDirectory.Place p=entries.get().get(index);new AppDialogs(activity).setTitle(p.displayName()).setMessage(p.detail()+"\n"+String.format(Locale.US,"%.6f, %.6f",p.lat(),p.lon())+"\n"+(p.date().isBlank()?"Age not supplied by source":"Date in source: "+p.date())+"\n"+p.source()).setPositiveButton("Navigate",(d,w)->{dialog.dismiss();navigate.accept(new DestinationSearch.Result(p.displayName(),new RoutePlan.Point(p.lat(),p.lon())));}).setNegativeButton("Close",null).show();});
    dialog.setOnDismissListener(d->{ticket[0]++;worker.shutdownNow();});dialog.show();dialog.getWindow().setLayout(Math.round(activity.getResources().getDisplayMetrics().widthPixels*.92f),Math.round(activity.getResources().getDisplayMetrics().heightPixels*.88f));AppStyle.nativeTextTree(root);previous.setEnabled(false);next.setEnabled(false);search.setEnabled(false);
    worker.execute(()->{try{List<String> loaded=ReligiousDirectory.states(activity);activity.runOnUiThread(()->{if(!dialog.isShowing())return;states.addAll(loaded);state.setAdapter(AppStyle.choices(activity,states));search.setEnabled(true);run.run();});}catch(Exception error){activity.runOnUiThread(()->note.setText("Directory unavailable: "+error.getMessage()));}});
  }
  private static ArrayAdapter<String> rows(Activity a,List<String> values){return new ArrayAdapter<String>(a,android.R.layout.simple_list_item_1,values){@Override public View getView(int position,View recycled,android.view.ViewGroup parent){TextView text=(TextView)super.getView(position,recycled,parent);AppStyle.text(text);text.setTextSize(12);text.setSingleLine(false);text.setMaxLines(4);return text;}};}
  private static EditText field(Activity a,String hint){EditText value=new EditText(a);value.setHint(hint);value.setSingleLine(true);return value;}
  private static Button button(Activity a,String title){Button b=new Button(a);b.setText(title);AppStyle.button(b);return b;}
}
