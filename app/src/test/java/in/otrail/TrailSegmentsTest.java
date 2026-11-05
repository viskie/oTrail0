package in.otrail;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class TrailSegmentsTest {
  private TrailSegments.Sample p(long id, long trip, long time, double lat) {
    return new TrailSegments.Sample(id, trip, time, lat, 77);
  }

  @Test
  public void entireTrailRetainsFirstAndLast() {
    List<TrailSegments.Sample> in = new ArrayList<>();
    for (int i = 0; i < 100; i++) in.add(p(i, 1, 1000 + i * 5000, 20 + i * .00001));
    List<TrailSegments.Segment> out = TrailSegments.build(in, 10);
    assertEquals(in.get(0), out.get(0).points().get(0));
    List<TrailSegments.Sample> points = out.get(0).points();
    assertEquals(in.get(99), points.get(points.size() - 1));
  }

  @Test
  public void shortGapGetsSeparateEdge() {
    var out = TrailSegments.build(List.of(p(1, 1, 1000, 20), p(2, 1, 121000, 20.001)), 100);
    assertTrue(out.get(1).inferred());
    assertEquals(2, out.get(1).points().size());
  }

  @Test
  public void longGapsConnectButImpossibleSpeedsDoNot() {
    assertTrue(
        TrailSegments.build(List.of(p(1, 1, 1000, 20), p(2, 1, 3601000, 20.001)), 100).stream()
            .anyMatch(TrailSegments.Segment::inferred));
    assertTrue(
        TrailSegments.build(List.of(p(1, 1, 1000, 20), p(2, 1, 121000, 25)), 100).stream()
            .noneMatch(TrailSegments.Segment::inferred));
  }

  @Test
  public void differentJourneysNeverBecomeRecordedEdges() {
    var out = TrailSegments.build(List.of(p(1, 1, 1000, 20), p(2, 2, 6000, 20.00001)), 100);
    assertTrue(out.stream().anyMatch(TrailSegments.Segment::inferred));
    assertTrue(out.stream().filter(s -> !s.inferred()).allMatch(s -> s.points().size() == 1));
  }
}
