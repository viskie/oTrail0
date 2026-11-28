package in.otrail.opay;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import in.otrail.extensions.opay.core.BackupCrypto;
import in.otrail.extensions.opay.core.Json;
import in.otrail.extensions.opay.core.LedgerTransaction;
import in.otrail.extensions.opay.core.Money;
import in.otrail.extensions.opay.core.Reconciliation;
import in.otrail.extensions.opay.core.Reports;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class LedgerReportsBackupTest {
  @Test public void transactionSurvivesJsonRoundTripIncludingGpsFix() {
    LedgerTransaction original = debit(25_000L, day(2026, 4, 1));
    original.utr = "612345678901";
    original.upiAddress = "swiggy@ybl";
    original.counterparty = "Swiggy";
    original.latitude = 12.9721;
    original.longitude = 77.5946;
    original.locationAccuracy = 8.5f;
    original.locationAt = day(2026, 4, 1) + 5_000L;
    original.locationProvider = "gps";
    original.annotations = "Provider says OK";

    String json = Json.write(original.toMap());
    LedgerTransaction restored = LedgerTransaction.fromMap(Json.object(Json.parse(json)));
    assertEquals(original.id, restored.id);
    assertEquals(original.amountPaise, restored.amountPaise);
    assertEquals(original.utr, restored.utr);
    assertEquals(original.upiAddress, restored.upiAddress);
    assertEquals(original.latitude, restored.latitude, 0.0000001);
    assertEquals(original.locationAccuracy, restored.locationAccuracy, 0.01f);
    assertEquals(original.locationAt, restored.locationAt);
    assertEquals("gps", restored.locationProvider);
    assertTrue(restored.locationText().contains("12.972100, 77.594600"));
    assertTrue(restored.locationText().contains("\u00b1"));
  }

  @Test public void onlySettledAttemptsCountAsRealized() {
    LedgerTransaction created = debit(100L, day(2026, 4, 1));
    created.execution = LedgerTransaction.Execution.CREATED;
    LedgerTransaction failed = debit(100L, day(2026, 4, 1));
    failed.execution = LedgerTransaction.Execution.PROVIDER_REPORTED_FAILURE;
    LedgerTransaction cancelled = debit(100L, day(2026, 4, 1));
    cancelled.execution = LedgerTransaction.Execution.CANCELLED;
    LedgerTransaction ok = debit(100L, day(2026, 4, 1));
    ok.execution = LedgerTransaction.Execution.PROVIDER_REPORTED_SUCCESS;
    assertFalse(created.realized());
    assertFalse(failed.realized());
    assertFalse(cancelled.realized());
    assertTrue(ok.realized());
    assertEquals(ok.dedupeKey(), ok.copy().dedupeKey());
  }

  @Test public void strongUtrMatchAutoLinksButAmbiguousAmountsDoNot() {
    LedgerTransaction initiated = debit(25_000L, day(2026, 4, 1));
    initiated.reconciliation = LedgerTransaction.Reconciliation.UNVERIFIED;
    initiated.utr = "612345678901";
    LedgerTransaction statement = debit(25_000L, day(2026, 4, 2));
    statement.utr = "612345678901";
    statement.execution = LedgerTransaction.Execution.PROVIDER_REPORTED_SUCCESS;
    statement.reconciliation = LedgerTransaction.Reconciliation.STATEMENT_MATCHED;

    Reconciliation.Match match = Reconciliation.best(statement,
        new ArrayList<>(java.util.Arrays.asList(initiated)));
    assertNotNull(match);
    assertTrue(match.strong);
    assertTrue(match.canAutoLink());

    Reconciliation.link(initiated, statement);
    assertEquals(statement.id, initiated.relatedId);
    assertEquals(initiated.id, statement.relatedId);
    assertEquals(LedgerTransaction.Reconciliation.USER_CONFIRMED, initiated.reconciliation);
    assertTrue(initiated.annotations.contains("statement_csv"));

    LedgerTransaction first = debit(25_000L, day(2026, 4, 1));
    first.counterparty = "Swiggy";
    LedgerTransaction second = debit(25_000L, day(2026, 4, 1));
    second.counterparty = "Swiggy";
    LedgerTransaction incoming = debit(25_000L, day(2026, 4, 1));
    incoming.counterparty = "Swiggy";
    List<LedgerTransaction> candidates = new ArrayList<>();
    candidates.add(first);
    candidates.add(second);
    Reconciliation.Match ambiguous = Reconciliation.best(incoming, candidates);
    assertNotNull(ambiguous);
    assertTrue(ambiguous.ambiguous);
    assertFalse(ambiguous.canAutoLink());
  }

  @Test public void financialYearRunsAprilToMarch() {
    Reports.Range fy = Reports.financialYear(day(2026, 3, 31));
    assertEquals("FY 2025\u201326", fy.label);
    assertTrue(fy.contains(day(2026, 3, 31)));
    assertFalse(fy.contains(day(2026, 4, 1)));
    Reports.Range next = Reports.financialYear(day(2026, 4, 1));
    assertEquals("FY 2026\u201327", next.label);
    assertTrue(next.contains(day(2026, 4, 1)));
    assertFalse(next.contains(day(2027, 4, 1)));
    assertTrue(next.contains(day(2027, 3, 31)));
  }

  @Test public void monthAndDayRangesAreInclusiveOfTheirStart() {
    long noon = day(2026, 5, 10) + 12 * 3_600_000L;
    Reports.Range month = Reports.month(noon);
    assertTrue(month.contains(day(2026, 5, 1)));
    assertTrue(month.contains(day(2026, 5, 31) + 23 * 3_600_000L));
    assertFalse(month.contains(day(2026, 6, 1)));
    Reports.Range day = Reports.day(noon);
    assertTrue(day.contains(noon));
    assertFalse(day.contains(noon + 86_400_000L));
  }

  @Test public void reportSeparatesVerifiedPendingFailedAndTransfers() {
    long time = day(2026, 5, 10) + 10 * 3_600_000L;
    LedgerTransaction spend = debit(25_000L, time);
    spend.category = "Food";
    spend.provider = "PhonePe";
    spend.counterparty = "Swiggy";

    LedgerTransaction salary = credit(8_500_000L, time);
    salary.category = "Income";
    salary.provider = "Bank";

    LedgerTransaction failed = debit(99_000L, time);
    failed.execution = LedgerTransaction.Execution.PROVIDER_REPORTED_FAILURE;

    LedgerTransaction unverified = debit(500_00L, time);
    unverified.reconciliation = LedgerTransaction.Reconciliation.UNVERIFIED;
    unverified.category = "Shopping";

    LedgerTransaction refund = credit(25_000L, time);
    refund.reconciliation = LedgerTransaction.Reconciliation.REFUNDED;

    LedgerTransaction transfer = debit(500_000L, time);
    transfer.internalTransfer = true;
    transfer.category = "Transfer";

    LedgerTransaction verified = debit(700_00L, time);
    verified.category = "Bills";

    List<LedgerTransaction> rows = new ArrayList<>();
    rows.add(spend);
    rows.add(salary);
    rows.add(failed);
    rows.add(unverified);
    rows.add(refund);
    rows.add(transfer);
    rows.add(verified);

    Reports.Range month = Reports.month(time);
    Reports.Summary strict = Reports.summarize(rows, month, false);
    assertEquals(3, strict.count);
    assertEquals(25_000L + 700_00L, strict.debits);
    assertEquals(8_500_000L, strict.credits);
    assertEquals(8_500_000L - 95_000L, strict.net);
    assertEquals(1, strict.failedOrCancelled);
    assertEquals(0, strict.unverifiedIncluded);
    assertEquals(1, strict.refunds);
    assertEquals(1, strict.internalTransfers);
    assertEquals(2, strict.categoryDebits.size());
    assertEquals(Long.valueOf(700_00L), strict.categoryDebits.get("Bills"));
    assertEquals(Long.valueOf(25_000L), strict.categoryDebits.get("Food"));
    assertEquals(3, strict.providerCounts.size());
    assertEquals(1, strict.days.size());

    Reports.Summary provisional = Reports.summarize(rows, month, true);
    assertEquals(4, provisional.count);
    assertEquals(1, provisional.unverifiedIncluded);
    assertEquals(Long.valueOf(500_00L), provisional.categoryDebits.get("Shopping"));
    assertEquals(Long.valueOf(700_00L), provisional.categoryDebits.get("Bills"));
    assertEquals(1, provisional.unreconciled);
    assertEquals(700_00L, provisional.largestDebit);
    assertEquals(8_500_000L, provisional.largestCredit);
    assertTrue(Money.format(provisional.debits).startsWith("\u20b9"));
  }

  @Test public void encryptedBackupRoundTripsAndRejectsWrongPassphrase() throws Exception {
    byte[] plain = "{\"transactions\":[]}".getBytes(StandardCharsets.UTF_8);
    byte[] encrypted = BackupCrypto.encrypt(plain, "correct horse battery".toCharArray());
    assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1).contains("transactions"));
    assertArrayEquals(plain, BackupCrypto.decrypt(encrypted, "correct horse battery".toCharArray()));
    try {
      BackupCrypto.decrypt(encrypted, "wrong passphrase".toCharArray());
      fail("Expected authentication failure");
    } catch (java.security.GeneralSecurityException expected) {
      assertTrue(expected.getMessage() != null);
    }
    try {
      BackupCrypto.encrypt(plain, "short".toCharArray());
      fail("Expected passphrase length check");
    } catch (java.security.GeneralSecurityException expected) {
      assertTrue(expected.getMessage().contains("8"));
    }
  }

  @Test public void reportSummaryAcceptsMapValuesFromJson() {
    Map<String, Object> map = debit(1_00L, day(2026, 1, 1)).toMap();
    LedgerTransaction parsed = LedgerTransaction.fromMap(map);
    assertEquals(LedgerTransaction.Execution.PROVIDER_REPORTED_SUCCESS, parsed.execution);
    assertEquals(LedgerTransaction.Reconciliation.STATEMENT_MATCHED, parsed.reconciliation);
    assertEquals("INR", parsed.currency);
  }

  private static LedgerTransaction debit(long paise, long time) {
    LedgerTransaction transaction = new LedgerTransaction();
    transaction.amountPaise = paise;
    transaction.occurredAt = time;
    transaction.createdAt = time;
    transaction.direction = LedgerTransaction.Direction.DEBIT;
    transaction.execution = LedgerTransaction.Execution.PROVIDER_REPORTED_SUCCESS;
    transaction.reconciliation = LedgerTransaction.Reconciliation.STATEMENT_MATCHED;
    transaction.origin = LedgerTransaction.Origin.STATEMENT_CSV;
    return transaction;
  }

  private static LedgerTransaction credit(long paise, long time) {
    LedgerTransaction transaction = debit(paise, time);
    transaction.direction = LedgerTransaction.Direction.CREDIT;
    return transaction;
  }

  private static long day(int year, int month, int date) {
    Calendar calendar = Calendar.getInstance();
    calendar.clear();
    calendar.set(year, month - 1, date, 0, 0, 0);
    return calendar.getTimeInMillis();
  }
}
