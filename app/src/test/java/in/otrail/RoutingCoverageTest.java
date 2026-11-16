package in.otrail;
import org.junit.Test;
import static org.junit.Assert.*;
public class RoutingCoverageTest {
 @Test public void coverageIncludesIslandsAndNorthEastWithoutDuplicates() {
   var tiles=RoutingPacks.indiaTiles();
   assertEquals(tiles.size(),new java.util.HashSet<>(tiles).size());
   assertTrue(tiles.contains("E90_N5"));
   assertTrue(tiles.contains("E90_N10"));
   assertTrue(tiles.contains("E70_N10"));
   assertTrue(tiles.contains("E95_N25"));
   assertTrue(tiles.contains("E75_N35"));
   assertEquals(49,tiles.size());
 }
}
