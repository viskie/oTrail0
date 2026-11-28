package in.otrail.opay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import in.otrail.extensions.opay.core.Money;
import in.otrail.extensions.opay.core.Upi;
import org.junit.Test;

public class MoneyUpiTest {
  @Test public void parsesIndianGroupingAndCurrency() {
    assertEquals(Long.valueOf(12_345_678L), Money.parse("1,23,456.78"));
    assertEquals(Long.valueOf(120_000L), Money.parse("\u20b91,200"));
    assertEquals(Long.valueOf(250_00L), Money.parse("Rs. 250"));
    assertEquals(Long.valueOf(-4550L), Money.parse("-45.50"));
    assertEquals(Long.valueOf(-4550L), Money.parse("(45.50)"));
  }

  @Test public void rejectsSubPaiseAndJunk() {
    assertNull(Money.parse("12.345"));
    assertNull(Money.parse("abc"));
    assertNull(Money.parse(""));
    assertNull(Money.parse(null));
    assertEquals(Long.valueOf(1230L), Money.parse("12.30"));
  }

  @Test public void formatsWithIndianGrouping() {
    assertEquals("\u20b91,23,456.78", Money.format(12_345_678L));
    assertEquals("\u20b90.05", Money.format(5L));
    assertEquals("-1,00,000.00", Money.plain(-10_000_000L));
    assertEquals("+₹1,234.50", Money.signed(123_450L));
  }

  @Test public void extractsAmountsFromSmsText() {
    assertEquals(Long.valueOf(25_000L), Money.firstAmount("Rs 250.00 debited from A/c XX1234"));
    assertEquals(Long.valueOf(8_500_000L), Money.firstAmount("INR 85,000.00 credited"));
  }

  @Test public void buildsAndParsesStandardUpiUri() {
    String uri = Upi.build("swiggy@ybl", "Swiggy Ltd", 25_000L, "Lunch & tea", "ABC123");
    assertTrue(uri.startsWith("upi://pay?pa=swiggy%40ybl"));
    assertTrue(uri.contains("pn=Swiggy%20Ltd"));
    assertTrue(uri.contains("am=250.00"));
    assertTrue(uri.contains("cu=INR"));
    assertTrue(uri.contains("tn=Lunch%20%26%20tea"));
    assertTrue(uri.contains("tr=ABC123"));

    Upi.Request parsed = Upi.parse(uri);
    assertEquals("swiggy@ybl", parsed.payeeAddress);
    assertEquals("Swiggy Ltd", parsed.payeeName);
    assertEquals(25_000L, parsed.amountPaise);
    assertEquals("INR", parsed.currency);
    assertEquals("Lunch & tea", parsed.note);
    assertEquals("ABC123", parsed.reference);
  }

  @Test public void parsesQrWithoutAmountAndKeepsPlusInName() {
    Upi.Request parsed = Upi.parse("upi://pay?pa=tea@bank&pn=Tea+Stall&cu=INR");
    assertEquals("Tea+Stall", parsed.payeeName);
    assertEquals(0L, parsed.amountPaise);
    assertFalse(parsed.hasAmount());
  }

  @Test public void rejectsNonPaymentAndInvalidPayees() {
    assertRejected("https://example.com/pay?pa=a@bank");
    assertRejected("upi://mandate?pa=a@bank");
    assertRejected("upi://pay?pa=not-a-vpa&am=10.00");
    assertRejected("upi://pay?pa=a@bank&am=12.345");
    assertRejected("upi://pay?pa=a@bank&cu=USD");
    assertRejected("hello");
    assertNotNull(Upi.displayName("swiggy@ybl"));
  }

  @Test public void receiveQrIsExplicitAboutAmount() {
    String uri = Upi.receive("shop@bank", "Corner Shop", 0L, "");
    assertTrue(uri.contains("pa=shop%40bank"));
    assertFalse(uri.contains("am="));
    String fixed = Upi.receive("shop@bank", "Corner Shop", 100_00L, "Coffee");
    assertTrue(fixed.contains("am=100.00"));
  }

  private static void assertRejected(String raw) {
    try {
      Upi.parse(raw);
      fail("Expected rejection of " + raw);
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage() != null);
    }
  }
}
