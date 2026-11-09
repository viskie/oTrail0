package in.otrail;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class ResonationsPlaylistTest {
  private ResonationsPlaylist.Track track(int n){return new ResonationsPlaylist.Track("content://recordings/"+n,"Recording "+n+".m4a");}
  @Test public void threeFilesUseThreeLinesWithoutOverflow(){assertEquals(List.of("Recording 1.m4a","Recording 2.m4a","Recording 3.m4a"),ResonationsPlaylist.visibleNames(List.of(track(1),track(2),track(3))));}
  @Test public void largerPlaylistUsesFourthLineEllipsis(){List<ResonationsPlaylist.Track> tracks=List.of(track(1),track(2),track(3),track(4),track(5));assertEquals(List.of("Recording 1.m4a","Recording 2.m4a","Recording 3.m4a","…"),ResonationsPlaylist.visibleNames(tracks));assertEquals(tracks,ResonationsPlaylist.decode(ResonationsPlaylist.encode(tracks)));}
  @Test public void loopWrapsAndHandlesEmptyOrSingleFile(){assertEquals(0,ResonationsPlaylist.next(2,3));assertEquals(0,ResonationsPlaylist.next(0,1));assertEquals(0,ResonationsPlaylist.next(0,0));}
  @Test public void malformedOrDuplicateAndNonDocumentEntriesAreSafe(){assertTrue(ResonationsPlaylist.decode("broken").isEmpty());assertEquals(List.of(track(1)),ResonationsPlaylist.decode("[{\"uri\":\"content://recordings/1\",\"name\":\"Recording 1.m4a\"},{\"uri\":\"content://recordings/1\"},{\"uri\":\"https://example.com/private.mp3\"}]"));}
}
