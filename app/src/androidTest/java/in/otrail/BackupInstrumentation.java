package in.otrail;

import android.app.*;
import android.content.Context;
import android.location.Location;
import android.os.Bundle;
import java.io.*;

/** Runs against isolated device-protected storage, never the user's trail database. */
public final class BackupInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    @Override public void onStart(){Bundle result=new Bundle();Context c=new android.content.ContextWrapper(getTargetContext().createDeviceProtectedStorageContext()){@Override public Context getApplicationContext(){return this;}};int checks=0;
        try {
            c.deleteDatabase("trails.db");String snapshot;
            try(TrailDb db=new TrailDb(c)){
                long trip=db.begin();StopDetector detector=new StopDetector();
                for(int i=1;i<=26;i++){Location l=new Location("instrumentation");l.setTime(1000000+i*5000);l.setLatitude(20);l.setLongitude(77);l.setAccuracy(8);l.setSpeed(0);db.record(trip,l,detector);}
                db.log("test","SUCCESS","round trip");StringWriter w=new StringWriter();db.writeBackup(w);snapshot=w.toString();require(snapshot.contains("otrail_backup_version"),"missing version");checks++;
                db.restoreBackup(new StringReader(snapshot));require(db.fixes(trip).size()==26,"merge duplicated existing data");checks++;
            }
            c.deleteDatabase("trails.db");
            try(TrailDb db=new TrailDb(c)){
                try{db.restoreBackup(new StringReader(snapshot.substring(0,snapshot.length()/2)));throw new AssertionError("Truncated backup accepted");}catch(Exception expected){}require(db.trips().isEmpty(),"failed restore did not roll back");checks++;
                db.restoreBackup(new StringReader(snapshot));require(db.trips().size()==1,"trip IDs not restored");long trip=db.trips().get(0);require(db.fixes(trip).size()==26,"fixes missing");require(db.stops(trip).size()==1,"stop missing");require(db.stops(trip).get(0).observed()-db.stops(trip).get(0).arrival()==125000,"duration changed");checks++;
                require(db.logs().contains("round trip"),"update logs missing");checks++;
            }
            result.putString("stream","PASS: "+checks+" backup checks (isolated test database)\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("stream","FAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}finally{c.deleteDatabase("trails.db");}
    }
}
