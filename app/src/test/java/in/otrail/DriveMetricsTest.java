package in.otrail;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class DriveMetricsTest {
  private DriveMetrics.Point p(long t,double lon,float speed){return new DriveMetrics.Point(t,20,lon,4,speed);}
  @Test public void stopLegsAndCurrentDriveUseDepartureBoundaries(){
    var points=List.of(p(1000,77,5),p(11000,77.001,5),p(21000,77.002,5),p(31000,77.002,0),p(41000,77.002,0),p(51000,77.003,5),p(61000,77.004,5));
    var r=DriveMetrics.calculate(points,List.of(new DriveMetrics.Stop(1,21000,41000,41000)));
    assertEquals(208.98,r.stops().get(1L).meters(),2);assertEquals(208.98,r.current().meters(),2);assertEquals(417.96,r.totalMeters(),3);assertEquals(37.62,r.current().averageKmh(),1);
  }
  @Test public void gapsNeverInflateLoggedKm(){var r=DriveMetrics.calculate(List.of(p(1000,77,4),p(121000,78,4)),List.of());assertEquals(0,r.totalMeters(),0);assertEquals(120000,r.current().missingMs());}
  @Test public void overlappingLegacyStopsNeverProduceNegativeLegs(){var r=DriveMetrics.calculate(List.of(p(1000,77,5),p(6000,77.001,5)),List.of(new DriveMetrics.Stop(1,2000,5000,5000),new DriveMetrics.Stop(2,3000,0,6000)));for(var l:r.stops().values()){assertTrue(l.meters()>=0);assertTrue(l.end()>=l.start());}}
  @Test public void stationaryJitterIsExcluded(){var r=DriveMetrics.calculate(List.of(p(1000,77,0),p(6000,77.00001,0)),List.of());assertEquals(0,r.totalMeters(),0);}
}
