package in.otrail;
import org.junit.Test;
import static org.junit.Assert.*;
public class DestinationLabelTest {
 @Test public void compactIndianDestination(){assertEquals("Bijnor: UP-246701",DestinationLabel.format("Bijnor, Uttar Pradesh 246701, India"));}
 @Test public void missingFieldsRemainReadable(){assertEquals("Bijnor: UP",DestinationLabel.format("Bijnor, Uttar Pradesh, India"));assertEquals("Bijnor",DestinationLabel.format("Bijnor, India"));assertEquals("",DestinationLabel.format(null));}
 @Test public void separatePinAndUnknownRegion(){assertEquals("Bijnor: UP-246701",DestinationLabel.format("Bijnor, Uttar Pradesh, 246701, India"));assertEquals("Place, District",DestinationLabel.format("Place, District, India"));}
 @Test public void coordinatesAreRetained(){assertEquals("28.3, 78.4",DestinationLabel.format("28.3, 78.4"));}
}
