package in.otrail;

import android.app.Activity;
import android.widget.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;

/** Review route preferences before calculation; choose each intermediate city explicitly. */
final class RouteOptionsDialog {
 static void show(Activity a,DestinationSearch.Result target,ExecutorService worker,BiConsumer<List<RoutePlan.Point>,String> ready) {
  LinearLayout content=new LinearLayout(a);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(20,12,20,12);
  TextView note=new TextView(a);note.setText("To: "+target.name()+"\nRoad choices are preferences: other legal roads fill gaps. EX = expressway; NH = national highway; SH = state highway. Online ranks available alternatives; offline uses road-class equivalents, not exact NH/SH designations.");content.addView(note);
  Spinner roads=new Spinner(a);roads.setAdapter(AppStyle.choices(a,Arrays.asList(RoutePreferences.LABELS)));content.addView(roads);
  String previous=a.getSharedPreferences("navigation",0).getString("roadPreference","auto");roads.setSelection(Math.max(0,Arrays.asList(RoutePreferences.KEYS).indexOf(previous)));
  EditText via=new EditText(a);via.setHint("Via cities, in travel order · one per line\nHyderabad\nNagpur\nOr latitude, longitude when offline");via.setMinLines(3);via.setMaxLines(5);content.addView(via);
  ScrollView scroll=new ScrollView(a);scroll.addView(content);
  android.app.AlertDialog dialog=new AppDialogs(a).setTitle("Route options").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Review & calculate",null).create();
  dialog.show();
  dialog.getButton(-1).setOnClickListener(v->{
   List<String> queries=new ArrayList<>();for(String line:via.getText().toString().split("\\n")){if(!line.isBlank())queries.add(line.trim());}
   if(queries.size()>8){via.setError("Up to 8 intermediate cities");return;}
   String key=RoutePreferences.KEYS[roads.getSelectedItemPosition()];
   dialog.dismiss();resolve(a,worker,queries,0,new ArrayList<>(),points->{
    a.getSharedPreferences("navigation",0).edit().putString("roadPreference",key).apply();ready.accept(points,key);
   });
  });
 }
 private static void resolve(Activity a,ExecutorService worker,List<String> queries,int index,List<RoutePlan.Point> points,java.util.function.Consumer<List<RoutePlan.Point>> done) {
  if(a.isFinishing()||a.isDestroyed())return;
  if(index==queries.size()){done.accept(List.copyOf(points));return;}
  String query=queries.get(index);Toast.makeText(a,"Finding via: "+query,Toast.LENGTH_SHORT).show();
  worker.execute(()->{
   try {
    List<DestinationSearch.Result> results=DestinationSearch.search(a.getApplicationContext(),query);
    a.runOnUiThread(()->{
     if(a.isFinishing()||a.isDestroyed())return;
     if(results.isEmpty()){new AppDialogs(a).setTitle("Via city not found").setMessage(query+" · try a more specific name or coordinates. Route has not been changed.").setPositiveButton("OK",null).show();return;}
     new AppDialogs(a).setTitle("Via "+(index+1)+": "+query).setItems(results.stream().map(DestinationSearch.Result::name).toArray(String[]::new),(d,n)->{
      points.add(results.get(n).point());resolve(a,worker,queries,index+1,points,done);
     }).setNegativeButton("Cancel route",null).show();
    });
   } catch(Exception e){a.runOnUiThread(()->{if(!a.isFinishing())new AppDialogs(a).setTitle("Via lookup unavailable").setMessage(query+": "+e.getMessage()+"\nOffline: use latitude, longitude. Existing route retained.").setPositiveButton("OK",null).show();});}
  });
 }
}
