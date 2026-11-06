package in.otrail;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Engine choice is independent of the visual map. Both offline choices always stay on device. */
public final class RoutingControls {
  private final Activity a;
  private final RoutingPacks packs;
  private final ExecutorService files=Executors.newSingleThreadExecutor();
  private String importKey;
  RoutingControls(Activity a){this.a=a;packs=new RoutingPacks(a);}
  void addTo(LinearLayout parent,List<Runnable> ticks) {
    LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(12,12,12,12);parent.addView(box);
    label(box,"Routing · choose your engine",21);
    label(box,"Your map and recorded trail stay the same. Download routing graphs here; no graphs are bundled in the app. Offline engines calculate new routes and reroute without internet. No voice guidance.",13);
    String[] names={"OSRM · online driving","BRouter · offline driving","Valhalla · offline driving"};
    String[] keys={"online","brouter","valhalla"};
    Spinner engines=spinner(box,List.of(names));
    String current=OfflineRouting.engine(a);for(int i=0;i<keys.length;i++)if(keys[i].equals(current))engines.setSelection(i);
    engines.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
      public void onNothingSelected(AdapterView<?> v){}
      public void onItemSelected(AdapterView<?> v,View row,int index,long id){a.getSharedPreferences("navigation",0).edit().putString("engine",keys[index]).apply();}
    });
    label(box,"BRouter regional tiles · India",18);
    label(box,"Choose every 5° tile along your journey, including detours. Tile names show their southwest corner. Border tiles include adjacent roads. Size appears when the download server responds.",13);
    List<String> tiles=RoutingPacks.indiaTiles();Spinner tile=spinner(box,tiles);
    String selected=a.getSharedPreferences("routing-packs",0).getString("selected","E75_N25");tile.setSelection(Math.max(0,tiles.indexOf(selected)));
    LinearLayout bstatus=new LinearLayout(a);bstatus.setOrientation(LinearLayout.VERTICAL);box.addView(bstatus);
    TextView bs=label(bstatus,"",13);ProgressBar bp=progress(bstatus);
    button(box,"Download / update selected BRouter tile",()->{
      String key=tile.getSelectedItem().toString();a.getSharedPreferences("routing-packs",0).edit().putString("selected",key).apply();
      try{packs.download(key,"https://brouter.de/brouter/segments4/"+key+".rd5","");}catch(Exception e){message(e.getMessage());}
    });
    button(box,"Cancel selected tile download",()->packs.cancel(tile.getSelectedItem().toString()));
    button(box,"Import retained BRouter tile",()->pick(tile.getSelectedItem().toString()));
    label(box,"Valhalla · India routing graph",18);
    label(box,"Needs a prepared India graph pack: uncompressed .tar containing .gph tiles, compatible with Valhalla 3.9.0. Your Mapsforge .map and raw OSM .pbf are not routing graphs. Configure a graph-pack URL or import a retained pack. No India download source is preconfigured.",13);
    TextView vs=label(box,"",13);ProgressBar vp=progress(box);
    button(box,"Configure Valhalla India pack source",this::configure);
    button(box,"Download / update Valhalla India pack",()->{
      android.content.SharedPreferences p=a.getSharedPreferences("routing-packs",0);
      String url=p.getString("valhalla.url","");
      if(url.isBlank()){configure();return;}
      try{packs.download("valhalla",url,p.getString("valhalla.checksum",""));}catch(Exception e){message(e.getMessage());}
    });
    button(box,"Cancel Valhalla download",()->packs.cancel("valhalla"));
    button(box,"Import retained Valhalla India .tar",()->pick("valhalla"));
    ticks.add(()->{render(packs.state(tile.getSelectedItem().toString()),bs,bp);render(packs.state("valhalla"),vs,vp);});
  }
  private void render(RoutingPacks.State s,TextView text,ProgressBar bar) {
    text.setText(s.note()+"\n"+(s.total()>0?MapPacks.size(s.bytes())+" / "+MapPacks.size(s.total()):s.bytes()>0?MapPacks.size(s.bytes())+" · size pending":"Size pending download")+(s.ready()?" · installed graph available":""));
    bar.setIndeterminate(s.busy()&&s.total()<=0);if(s.total()>0)bar.setProgress((int)Math.min(100,s.bytes()*100/s.total()));
  }
  private void configure() {
    LinearLayout fields=new LinearLayout(a);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(24,12,24,12);
    android.content.SharedPreferences p=a.getSharedPreferences("routing-packs",0);
    EditText url=new EditText(a);url.setHint("HTTPS URL to India graph .tar");url.setText(p.getString("valhalla.url",""));fields.addView(url);
    EditText checksum=new EditText(a);checksum.setHint("SHA-256 from pack publisher (optional)");checksum.setText(p.getString("valhalla.checksum",""));fields.addView(checksum);
    new AlertDialog.Builder(a).setTitle("Valhalla graph download").setView(fields).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{
      p.edit().putString("valhalla.url",url.getText().toString().trim()).putString("valhalla.checksum",checksum.getText().toString().trim()).apply();
    }).show();
  }
  private void pick(String key){importKey=key;a.getPreferences(0).edit().putString("routingImportKey",key).apply();Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);a.startActivityForResult(i,31);}
  void imported(Uri uri) {
    String key=importKey!=null?importKey:a.getPreferences(0).getString("routingImportKey","");
    message("Validating routing graph…");files.execute(()->{try{packs.importFile(key,uri);a.runOnUiThread(()->message("Routing graph ready offline"));}catch(Exception e){a.runOnUiThread(()->message("Graph import failed: "+e.getMessage()));}});
  }
  private TextView label(LinearLayout box,String text,int size){TextView t=new TextView(a);t.setText(text);AppStyle.text(t);t.setTextSize(size);t.setTextColor(AppStyle.textColor(size,size>=18));t.setPadding(0,8,0,8);box.addView(t);return t;}
  private Spinner spinner(LinearLayout box,List<String> names){Spinner s=new Spinner(a);s.setAdapter(new ArrayAdapter<>(a,android.R.layout.simple_spinner_dropdown_item,names));box.addView(s);return s;}
  private ProgressBar progress(LinearLayout box){ProgressBar p=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);p.setMax(100);box.addView(p,new LinearLayout.LayoutParams(-1,16));return p;}
  private void button(LinearLayout box,String text,Runnable run){Button b=new Button(a);b.setText(text);AppStyle.button(b);b.setOnClickListener(v->run.run());box.addView(b);}
  private void message(String text){Toast.makeText(a,text,Toast.LENGTH_LONG).show();}
  void close(){files.shutdown();}
}
