package in.otrail;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class StopDotLayoutTest {
  @Test public void distantZoomMakesDotsSmallerAndMoreSeparated(){assertTrue(StopDotLayout.diameter(8,false)<StopDotLayout.diameter(16,false));assertTrue(StopDotLayout.spacing(8)>StopDotLayout.spacing(16));assertTrue(StopDotLayout.diameter(8,true)>StopDotLayout.diameter(8,false));}
  @Test public void zoomOutThinsDenseStopsWithoutChangingInput(){List<StopDotLayout.Point> points=new ArrayList<>();for(int i=0;i<20;i++)points.add(new StopDotLayout.Point(i,i*10,20,false));int far=StopDotLayout.select(points,StopDotLayout.spacing(7),Double.NaN,Double.NaN).size(),near=StopDotLayout.select(points,StopDotLayout.spacing(16),Double.NaN,Double.NaN).size();assertTrue(far<near);assertEquals(20,points.size());}
  @Test public void longStopsWinOverNewerCrowdedShortStops(){assertEquals(Set.of(1L,3L),StopDotLayout.select(List.of(new StopDotLayout.Point(1,0,0,true),new StopDotLayout.Point(2,5,0,false),new StopDotLayout.Point(3,100,0,false)),30,Double.NaN,Double.NaN));}
  @Test public void gpsDotKeepsClearSpaceAndNegativeCoordinatesAreSafe(){assertEquals(Set.of(2L),StopDotLayout.select(List.of(new StopDotLayout.Point(1,2,2,true),new StopDotLayout.Point(2,-100,-100,false)),30,0,0));}
  @Test public void equalPriorityUsesNewestAndResultIsStable(){List<StopDotLayout.Point> p=List.of(new StopDotLayout.Point(1,10,10,false),new StopDotLayout.Point(2,10,10,false));assertEquals(Set.of(2L),StopDotLayout.select(p,30,Double.NaN,Double.NaN));assertEquals(StopDotLayout.select(p,30,Double.NaN,Double.NaN),StopDotLayout.select(p,30,Double.NaN,Double.NaN));}
  @Test public void largerGpsArtReservesMoreSpaceWithoutReducingOtherStopDensity(){
    assertEquals(Set.of(2L,3L),StopDotLayout.select(List.of(new StopDotLayout.Point(1,25,0,false),new StopDotLayout.Point(2,60,0,false),new StopDotLayout.Point(3,100,0,false)),18,0,0,35));
  }
}
