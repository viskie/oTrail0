package in.otrail;
import org.junit.Test;import static org.junit.Assert.*;
public class HistoricTrailInputTest {
 @Test public void orderedCities(){var s=HistoricTrailInput.parse("Delhi, Jaipur, Agra");assertEquals(3,s.size());assertEquals("Jaipur",s.get(1).name());}
 @Test public void explicitChronology(){var s=HistoricTrailInput.parse("order,name,lat,lon,time\n2,Agra,27.17,78.04,2020-02-01\n1,Delhi,28.6,77.2,2020-01-01");assertEquals("Delhi",s.get(0).name());assertNotNull(s.get(0).time());}
 @Test public void timestampsSort(){var s=HistoricTrailInput.parse("name,lat,lon,time\nAgra,27.17,78.04,2020-02-01\nDelhi,28.6,77.2,2020-01-01");assertEquals("Delhi",s.get(0).name());}
 @Test public void quotedCity(){var s=HistoricTrailInput.parse("\"Delhi, India\", Jaipur");assertEquals("Delhi, India",s.get(0).name());}
 @Test(expected=IllegalArgumentException.class) public void rejectsOutsideIndia(){HistoricTrailInput.parse("50,0\n28,77");}
 @Test(expected=IllegalArgumentException.class) public void rejectsUnclosedQuote(){HistoricTrailInput.parse("\"Delhi, Jaipur");}
 @Test public void adaptiveGps(){assertEquals(1000,GpsPolling.interval(5,10,false,0,true));assertEquals(13000,GpsPolling.interval(5,0,true,130000,true));assertEquals(30000,GpsPolling.interval(5,0,true,900000,true));assertEquals(13000,GpsPolling.interval(5,0,true,900000,false));assertEquals(1000,GpsPolling.interval(40,0,true,900000,true));}
}
