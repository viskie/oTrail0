package in.otrail;

import static org.junit.Assert.*;

import org.junit.Test;

public class HistoricAgeTest {
  @Test
  public void datesAndApproximation() {
    assertEquals(1650, HistoricAge.parse("1650-04-12").earliest());
    assertTrue(HistoricAge.parse("c. 1650").approximate());
    assertFalse(HistoricAge.parse("1650").approximate());
  }

  @Test
  public void centuryAndRange() {
    assertEquals(1500, HistoricAge.parse("16th century").earliest());
    assertEquals(1599, HistoricAge.parse("C16").latest());
    assertTrue(HistoricAge.parse("1500..1600").approximate());
  }

  @Test
  public void ageBoundaries() {
    assertTrue(HistoricAge.parse("1826").inWindow(2026));
    assertTrue(HistoricAge.parse("1426").inWindow(2026));
    assertFalse(HistoricAge.parse("1827").inWindow(2026));
    assertFalse(HistoricAge.parse("1425").inWindow(2026));
  }

  @Test
  public void unknownAgeNotInvented() {
    assertNull(HistoricAge.parse("old temple"));
    assertNull(HistoricAge.parse("before 1700"));
    assertNull(HistoricAge.parse("200 BCE"));
  }
}
