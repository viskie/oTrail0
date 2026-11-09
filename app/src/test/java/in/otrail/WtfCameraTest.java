package in.otrail;
import org.junit.Test;
import static org.junit.Assert.*;
public class WtfCameraTest {
 @Test public void exactRequestedSlope(){double angle=Math.toRadians(WtfCamera.diagonalBearing());assertEquals(-1/.8,Math.cos(angle)/Math.sin(angle),.000001);}
 @Test public void allCoursesKeepTravelOnSameDiagonal(){for(float course:new float[]{0,45,90,180,270,359})assertEquals(WtfCamera.diagonalBearing(),WtfCamera.normalize(course+WtfCamera.rotation(course)),.0001);}
 @Test public void turnCrossesNorthByShortestArc(){assertEquals(2,WtfCamera.shortestTurn(359,1),.001);assertEquals(-2,WtfCamera.shortestTurn(1,359),.001);}
 @Test public void stopWeakAndStaleSignalsHoldHeading(){assertFalse(WtfCamera.reliable(50000,50000,5,10,true));assertFalse(WtfCamera.reliable(50000,50000,80,10,false));assertFalse(WtfCamera.reliable(50000,10000,5,10,false));assertFalse(WtfCamera.reliable(50000,50000,5,0,false));assertTrue(WtfCamera.reliable(50000,49000,5,5,false));}
 @Test public void noisyDisplacementCannotSpinMap(){assertNull(WtfCamera.displacementCourse(10000,5000,29.00001,78,29,78,15,15));}
 @Test public void genuineNorthwardDisplacementHasTrueNorthCourse(){assertEquals(0,WtfCamera.displacementCourse(10000,5000,29.001,78,29,78,5,5),.001);}
 @Test public void invalidAccuracyCannotInventTravelHeading(){assertNull(WtfCamera.displacementCourse(10000,5000,29.001,78,29,78,5,Float.NaN));}
 @Test public void gapCannotInventTravelHeading(){assertNull(WtfCamera.displacementCourse(100000,5000,29.001,78,29,78,5,5));}
}
