package in.otrail;
import org.junit.Test;
import static org.junit.Assert.*;
public class LivePopupTextTest {
 @Test public void onlyAvailableDistancesStayInPopup(){var text=LivePopupText.worship("Temple: 100 m · Mosque: not mapped ≤25 km\nStraight-line · cached/online · OSM");assertEquals("Temple: 100 m",text.values());assertTrue(text.notes().contains("Mosque"));assertFalse(text.values().contains("not mapped"));}
 @Test public void failedLookupOnlyAppearsInSidePanel(){var text=LivePopupText.worship("Worship lookup unavailable · retrying; cached results retained");assertEquals("",text.values());assertTrue(text.notes().contains("unavailable"));}
 @Test public void pendingAddressIsDiagnostic(){assertTrue(LivePopupText.addressPending("Locality / road lookup pending"));assertFalse(LivePopupText.addressPending("Karanpur\nNational Highway 9"));}
}
