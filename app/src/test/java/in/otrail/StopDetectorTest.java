package in.otrail;
import org.junit.Test;
import static org.junit.Assert.*;
public class StopDetectorTest {
    @Test public void stopMustExceedTwoMinutes(){StopDetector d=new StopDetector();for(int i=1;i<=25;i++)assertNull(d.accept(i*5000,20,77,8,0));assertFalse(d.isStopped());StopDetector.Event e=d.accept(130000,20,77,8,0);assertTrue(e.opened());assertTrue(d.isStopped());assertEquals(5000,e.arrival());assertNull(d.accept(135000,20.01,77,8,5));assertTrue(d.isStopped());}
    @Test public void signalGapPreservesConfirmedStop(){StopDetector d=new StopDetector();for(int i=1;i<=26;i++)d.accept(i*5000,20,77,8,0);assertNull(d.accept(200000,20,77,8,0));assertTrue(d.isStopped());assertEquals(5000,d.arrival());}
    @Test public void restoredStopRetainsArrivalAndClosesOnMovement(){StopDetector d=new StopDetector();d.restore(5000,130000,20,77,true);assertNull(d.accept(900000,20,77,8,0));assertEquals(5000,d.arrival());assertNull(d.accept(905000,20.001,77,8,5));StopDetector.Event e=null;for(int i=1;i<=4;i++)e=d.accept(905000+i*5000,20.001+i*.0002,77,8,5);assertNotNull(e);assertTrue(e.closed());assertEquals(900000,e.departure());}
    @Test public void confirmedStopSurvivesBoundaryJitter(){StopDetector d=new StopDetector();for(int i=1;i<=26;i++)d.accept(i*5000,20,77,8,0);assertNull(d.accept(135000,20.00034,77,7,1.18f));assertNull(d.accept(140000,20.00033,77,12,.94f));assertTrue(d.isStopped());assertEquals(5000,d.arrival());}
    @Test public void poorAccuracyNeverCreatesStop(){StopDetector d=new StopDetector();for(int i=1;i<100;i++)d.accept(i*5000,20,77,80,0);assertFalse(d.isStopped());}
    @Test public void movingFixesNeverCreateStop(){StopDetector d=new StopDetector();for(int i=1;i<100;i++)d.accept(i*5000,20+i*.0002,77,8,4);assertFalse(d.isStopped());}
    @Test public void duplicateTimestampsIgnored(){StopDetector d=new StopDetector();d.accept(5000,20,77,8,0);assertNull(d.accept(5000,21,77,8,5));assertEquals(5000,d.arrival());}
    @Test public void anchoredRadiusPreventsSlowDriftStops(){StopDetector d=new StopDetector();for(int i=1;i<100;i++)d.accept(i*5000,20+i*.00005,77,8,0);assertFalse(d.isStopped());}
    @Test public void poorReceptionCannotCloseConfirmedStop(){StopDetector d=new StopDetector();d.restore(5000,130000,20,77,true);assertNull(d.accept(135000,21,78,80,30));assertTrue(d.isStopped());assertTrue(d.isUncertain());assertEquals(5000,d.arrival());}
    @Test public void driftingExcursionRecoversOnThreeStableFixes(){
      StopDetector d=new StopDetector();d.restore(5000,130000,20,77,true);
      for(int i=1;i<=12;i++)assertNull(d.accept(130000+i*5000,20+i*.00009,77,15,2.3f));
      assertTrue(d.isStopped());assertTrue(d.isUncertain());
      assertNull(d.accept(200000,20,77,5,0));assertFalse(d.stableSignal());
      assertNull(d.accept(205000,20,77,5,0));assertFalse(d.stableSignal());
      assertNull(d.accept(210000,20,77,5,0));assertTrue(d.stableSignal());assertFalse(d.isUncertain());assertEquals(5000,d.arrival());
    }
    @Test public void falseSpeedAtSamePointDoesNotEndStop(){StopDetector d=new StopDetector();d.restore(5000,130000,20,77,true);assertNull(d.accept(135000,20,77,15,35));assertTrue(d.isStopped());assertTrue(d.isUncertain());}
    @Test public void realDriveInReducedAccuracyEventuallyConfirmsDeparture(){StopDetector d=new StopDetector();d.restore(5000,130000,20,77,true);StopDetector.Event event=null;for(int i=0;i<=18;i++)event=d.accept(135000+i*5000,20.001+i*.0003,77,15,6.5f);assertNotNull(event);assertTrue(event.closed());assertFalse(d.isStopped());}
    @Test public void thirteenSecondFixesPreserveStopArrivalAcrossPoorReception(){
      StopDetector d=new StopDetector();StopDetector.Event opened=null;
      for(int i=1;i<=11;i++){StopDetector.Event e=d.accept(i*13000L,20,77,6,0);if(e!=null)opened=e;}
      assertNotNull(opened);assertTrue(opened.opened());assertEquals(13000L,d.arrival());
      assertNull(d.accept(156000L,20.002,77,80,15));assertTrue(d.isStopped());
      for(int i=13;i<=15;i++)assertNull(d.accept(i*13000L,20,77,6,0));
      assertTrue(d.isStopped());assertFalse(d.isUncertain());assertEquals(13000L,d.arrival());
    }
}
