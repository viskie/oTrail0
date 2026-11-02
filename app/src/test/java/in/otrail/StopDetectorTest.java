package in.otrail;
import org.junit.Test;
import static org.junit.Assert.*;
public class StopDetectorTest {
    @Test public void stopMustExceedTwoMinutes(){StopDetector d=new StopDetector();for(int i=1;i<=25;i++)assertNull(d.accept(i*5000,20,77,8,0));assertFalse(d.isStopped());StopDetector.Event e=d.accept(130000,20,77,8,0);assertTrue(e.opened());assertTrue(d.isStopped());assertEquals(5000,e.arrival());assertTrue(d.accept(135000,20.01,77,8,5).closed());}
    @Test public void signalGapClosesAtLastObservedFix(){StopDetector d=new StopDetector();for(int i=1;i<=26;i++)d.accept(i*5000,20,77,8,0);StopDetector.Event e=d.accept(200000,20,77,8,0);assertTrue(e.closed());assertEquals(130000,e.departure());assertFalse(d.isStopped());}
    @Test public void poorAccuracyNeverCreatesStop(){StopDetector d=new StopDetector();for(int i=1;i<100;i++)d.accept(i*5000,20,77,80,0);assertFalse(d.isStopped());}
    @Test public void movingFixesNeverCreateStop(){StopDetector d=new StopDetector();for(int i=1;i<100;i++)d.accept(i*5000,20,77,8,4);assertFalse(d.isStopped());}
    @Test public void duplicateTimestampsIgnored(){StopDetector d=new StopDetector();d.accept(5000,20,77,8,0);assertNull(d.accept(5000,21,77,8,5));assertEquals(5000,d.arrival());}
    @Test public void anchoredRadiusPreventsSlowDriftStops(){StopDetector d=new StopDetector();for(int i=1;i<100;i++)d.accept(i*5000,20+i*.00005,77,8,0);assertFalse(d.isStopped());}
}
