package in.otrail;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class TrailPaletteTest {
  private TrailSegments.Sample p(long t,double lon){return new TrailSegments.Sample(t,1,t,20,lon);}
  @Test public void repeatedReverseTraversalGetsAnotherColor(){var strokes=TrailPalette.build(List.of(new TrailSegments.Segment(false,List.of(p(1000,77),p(11000,77.002))),new TrailSegments.Segment(false,List.of(p(200000,77.002),p(210000,77)))));assertEquals(2,strokes.size());assertNotEquals(strokes.get(0).color(),strokes.get(1).color());assertEquals(2,strokes.get(1).pass());}
  @Test public void paletteHas31UniqueNonContinuityColors(){Set<Integer> colors=new HashSet<>();for(int c:TrailPalette.COLORS){assertNotEquals(TrailPalette.CONTINUITY,c);colors.add(c);}assertEquals(31,colors.size());}
  @Test public void inferredEdgesStayGreenAndDoNotCountAsPasses(){var result=TrailPalette.build(List.of(new TrailSegments.Segment(true,List.of(p(1000,77),p(11000,77.002)))));assertEquals(TrailPalette.CONTINUITY,result.get(0).color());assertEquals(0,result.get(0).pass());}
}
