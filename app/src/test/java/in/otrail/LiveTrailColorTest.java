package in.otrail;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;
public class LiveTrailColorTest {
  private TrailSegments.Sample p(long time){return new TrailSegments.Sample(time,1,time,28,78);}
  @Test public void currentHighlightPreservesOlderEdgesAndGreenGaps(){
    List<TrailSegments.Sample> points=List.of(p(100),p(200),p(300),p(400));
    TrailPalette.Stroke gap=new TrailPalette.Stroke(TrailPalette.CONTINUITY,0,List.of(p(400),p(500)));
    List<TrailPalette.Stroke> result=TrailPalette.highlightCurrent(List.of(new TrailPalette.Stroke(123,3,points),gap),300);
    assertEquals(3,result.size());assertEquals(123,result.get(0).color());
    assertEquals(200,result.get(0).points().get(1).time());
    assertEquals(UiConfig.LIVE_TRAIL_COLOR,result.get(1).color());assertEquals(3,result.get(1).pass());
    assertSame(gap,result.get(2));assertEquals(200,result.get(1).points().get(0).time());
  }
  @Test public void futureStartLeavesHistoryUntouched(){
    TrailPalette.Stroke old=new TrailPalette.Stroke(123,1,List.of(p(100),p(200)));
    assertSame(old,TrailPalette.highlightCurrent(List.of(old),300).get(0));
  }
}
