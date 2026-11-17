package in.otrail;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class TrailLanesTest {
 private TrailSegments.Sample p(long time,double lon){return new TrailSegments.Sample(time,1,time,20,lon);}
 @Test public void latestPassCentredOlderPassesHaveDistinctSides(){
  var a=p(1,77);var b=p(2,77.001);var c=p(100000,77);var d=p(100001,77.001);var e=p(200000,77);var f=p(200001,77.001);
  var s1=new TrailPalette.Stroke(1,1,List.of(a,b));var s2=new TrailPalette.Stroke(2,2,List.of(c,d));var s3=new TrailPalette.Stroke(3,3,List.of(e,f));
  var lanes=new TrailLanes(List.of(s1,s2,s3));assertEquals(0,lanes.lane(e,e,f,3));assertEquals(1,lanes.lane(c,c,d,2));assertEquals(-1,lanes.lane(a,a,b,1));assertEquals(77,a.lon(),0);
 }
 @Test public void paletteCapacityHasUniqueLanePositions(){Set<Integer> positions=new HashSet<>();for(int age=0;age<31;age++)positions.add(TrailLanes.position(age));assertEquals(31,positions.size());}
 @Test public void continuityIsNotOffset(){var a=p(1,77);var b=p(2,77.001);assertEquals(0,new TrailLanes(List.of()).lane(a,a,b,0));}
}
