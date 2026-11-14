package in.otrail;
import android.app.Instrumentation;
import android.os.Bundle;
import java.util.*;
/** Exercises the bundled directory and real Android SQLite without touching the trail database. */
public final class DirectoryInstrumentation extends Instrumentation {
  public void onCreate(Bundle args){super.onCreate(args);start();}
  private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
  public void onStart(){Bundle result=new Bundle();try{
    var context=getTargetContext();var info=ReligiousDirectory.metadata(context);
    require(info.getJSONObject("counts").optInt("hindu")>0,"Hindu directory missing");
    var states=ReligiousDirectory.states(context);require(states.size()>20,"State coverage missing");
    var all=ReligiousDirectory.search(context,"","","","","",false,0);require(all.count()>10000,"Directory too small");
    var named=ReligiousDirectory.search(context,"","","","","",true,0);require(named.count()>1000,"Named places missing");
    require(named.places().stream().noneMatch(p->p.name().isBlank()),"Named-only returned blank names");
    var example=named.places().get(0);var state=ReligiousDirectory.search(context,example.state(),"","","","",true,0);
    require(state.places().stream().allMatch(p->p.state().equals(example.state())),"State filter broken");
    for(String faith:List.of("hindu","muslim","christian","buddhist")){var page=ReligiousDirectory.search(context,"",faith,"","","",false,0);require(page.count()==info.getJSONObject("counts").optInt(faith),"Count mismatch: "+faith);require(page.places().stream().allMatch(p->p.religion().equals(faith)),"Faith filter broken");}
    require(escapedSearch(context),"Search escaping broken");
    try{ReligiousDirectory.search(context,"","","","12","",false,0);throw new AssertionError("Invalid PIN accepted");}catch(IllegalArgumentException expected){}
    var nearby=NearbySites.parse(ReligiousDirectory.nearby(context,example.lat(),example.lon()),example.lat(),example.lon());require(!nearby.isEmpty(),"Offline nearby returned nothing at sourced place");
    result.putString("stream","PASS: metadata/counts, named-only, state/religion filters, escaped search, PIN validation and offline nearby. Trail database untouched.\n");finish(-1,result);
  }catch(Throwable e){result.putString("stream",android.util.Log.getStackTraceString(e));finish(1,result);}}
  private boolean escapedSearch(android.content.Context c)throws Exception{return ReligiousDirectory.search(c,"","","","","%' OR 1=1 --",false,0).count()==0;}
}
