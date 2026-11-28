package in.otrail.extensions.opay.core;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Calendar ranges, Indian financial years and ledger aggregation. */
public final class Reports {
  private Reports() {}

  public static final class Range {
    public final long from;
    public final long toExclusive;
    public final String label;

    public Range(long from, long toExclusive, String label) {
      this.from = from;
      this.toExclusive = Math.max(toExclusive, from + 1);
      this.label = label;
    }

    public boolean contains(long time) {
      return time >= from && time < toExclusive;
    }
  }

  public static final class Day {
    public final long dayStart;
    public long credits;
    public long debits;

    public Day(long dayStart) { this.dayStart = dayStart; }
  }

  public static final class Summary {
    public Range range;
    public long credits;
    public long debits;
    public long net;
    public int count;
    public long averagePaise;
    public long largestCredit;
    public long largestDebit;
    public int pending;
    public int unreconciled;
    public int refunds;
    public int reversals;
    public int internalTransfers;
    public int failedOrCancelled;
    public int unverifiedIncluded;
    public final Map<String, Long> categoryDebits = new LinkedHashMap<>();
    public final Map<String, Integer> providerCounts = new LinkedHashMap<>();
    public final Map<String, Long> accountTotals = new LinkedHashMap<>();
    public final Map<String, Long> beneficiaryDebits = new LinkedHashMap<>();
    public final TreeMap<Long, Day> days = new TreeMap<>();

    public List<Day> daily() { return new ArrayList<>(days.values()); }

    public long categoryTotal(String category) {
      Long value = categoryDebits.get(category);
      return value == null ? 0 : value;
    }
  }

  public static Range day(long time) {
    Calendar calendar = start(time);
    Calendar end = (Calendar) calendar.clone();
    end.add(Calendar.DAY_OF_MONTH, 1);
    return new Range(calendar.getTimeInMillis(), end.getTimeInMillis(), format(calendar, "dd MMM yyyy"));
  }

  public static Range week(long time) {
    Calendar calendar = start(time);
    calendar.set(Calendar.DAY_OF_WEEK, calendar.getFirstDayOfWeek());
    Calendar end = (Calendar) calendar.clone();
    end.add(Calendar.DAY_OF_MONTH, 7);
    return new Range(calendar.getTimeInMillis(), end.getTimeInMillis(),
        format(calendar, "dd MMM") + " \u2013 " + format(end, "dd MMM yyyy"));
  }

  public static Range month(long time) {
    Calendar calendar = start(time);
    calendar.set(Calendar.DAY_OF_MONTH, 1);
    Calendar end = (Calendar) calendar.clone();
    end.add(Calendar.MONTH, 1);
    return new Range(calendar.getTimeInMillis(), end.getTimeInMillis(), format(calendar, "MMMM yyyy"));
  }

  public static Range quarter(long time) {
    Calendar calendar = start(time);
    calendar.set(Calendar.DAY_OF_MONTH, 1);
    int month = calendar.get(Calendar.MONTH);
    calendar.set(Calendar.MONTH, (month / 3) * 3);
    Calendar end = (Calendar) calendar.clone();
    end.add(Calendar.MONTH, 3);
    return new Range(calendar.getTimeInMillis(), end.getTimeInMillis(),
        "Q" + (month / 3 + 1) + " " + format(calendar, "yyyy"));
  }

  public static Range calendarYear(long time) {
    Calendar calendar = start(time);
    calendar.set(Calendar.DAY_OF_MONTH, 1);
    calendar.set(Calendar.MONTH, Calendar.JANUARY);
    Calendar end = (Calendar) calendar.clone();
    end.add(Calendar.YEAR, 1);
    return new Range(calendar.getTimeInMillis(), end.getTimeInMillis(), format(calendar, "yyyy"));
  }

  /** Indian financial year: 1 April to 31 March. */
  public static Range financialYear(long time) {
    Calendar calendar = start(time);
    int startYear = financialYearStartYear(calendar);
    calendar.set(Calendar.DAY_OF_MONTH, 1);
    calendar.set(Calendar.MONTH, Calendar.APRIL);
    calendar.set(Calendar.YEAR, startYear);
    Calendar end = (Calendar) calendar.clone();
    end.add(Calendar.YEAR, 1);
    return new Range(calendar.getTimeInMillis(), end.getTimeInMillis(),
        "FY " + startYear + "\u2013" + String.valueOf(startYear + 1).substring(2));
  }

  public static Range custom(long from, long to) {
    Calendar start = start(from);
    Calendar end = start(to);
    end.add(Calendar.DAY_OF_MONTH, 1);
    return new Range(start.getTimeInMillis(), end.getTimeInMillis(),
        format(start, "dd MMM yyyy") + " \u2013 " + format(start(to), "dd MMM yyyy"));
  }

  public static int financialYearStartYear(Calendar calendar) {
    int month = calendar.get(Calendar.MONTH);
    int year = calendar.get(Calendar.YEAR);
    return month >= Calendar.APRIL ? year : year - 1;
  }

  public static Summary summarize(Iterable<LedgerTransaction> transactions, Range range,
      boolean includeUnverified) {
    Summary summary = new Summary();
    summary.range = range;
    long total = 0;
    for (LedgerTransaction transaction : transactions) {
      if (transaction.amountPaise <= 0) continue;
      if (transaction.execution == LedgerTransaction.Execution.PROVIDER_REPORTED_FAILURE
          || transaction.execution == LedgerTransaction.Execution.CANCELLED
          || transaction.execution == LedgerTransaction.Execution.CREATED) {
        if (range.contains(transaction.occurredAt)) summary.failedOrCancelled++;
        continue;
      }
      if (!range.contains(transaction.occurredAt)) continue;
      if (transaction.isUnverified()) {
        if (!includeUnverified) continue;
        summary.unverifiedIncluded++;
      }
      if (transaction.reconciliation == LedgerTransaction.Reconciliation.REVERSED) {
        summary.reversals++;
        continue;
      }
      if (transaction.reconciliation == LedgerTransaction.Reconciliation.REFUNDED) {
        summary.refunds++;
        continue;
      }
      if (transaction.internalTransfer) {
        // Moving money between the user's own accounts is not income or expenditure.
        summary.internalTransfers++;
        continue;
      }
      boolean credit = transaction.direction == LedgerTransaction.Direction.CREDIT;
      if (credit) {
        summary.credits += transaction.amountPaise;
        summary.largestCredit = Math.max(summary.largestCredit, transaction.amountPaise);
      } else {
        summary.debits += transaction.amountPaise;
        summary.largestDebit = Math.max(summary.largestDebit, transaction.amountPaise);
      }
      summary.count++;
      total += transaction.amountPaise;
      if (!credit) {
        add(summary.categoryDebits, category(transaction), transaction.amountPaise);
        add(summary.beneficiaryDebits, beneficiary(transaction), transaction.amountPaise);
      }
      add(summary.providerCounts, transaction.provider.isEmpty() ? "Manual" : transaction.provider, 1);
      add(summary.accountTotals,
          transaction.accountMasked.isEmpty() ? "Unlinked account" : transaction.accountMasked,
          transaction.amountPaise);
      if (transaction.execution == LedgerTransaction.Execution.PROVIDER_REPORTED_PENDING
          || transaction.execution == LedgerTransaction.Execution.INITIATED
          || transaction.execution == LedgerTransaction.Execution.UNKNOWN) summary.pending++;
      if (transaction.isUnverified()) summary.unreconciled++;
      long dayStart = dayStart(transaction.occurredAt);
      Day day = summary.days.get(dayStart);
      if (day == null) {
        day = new Day(dayStart);
        summary.days.put(dayStart, day);
      }
      if (credit) day.credits += transaction.amountPaise;
      else day.debits += transaction.amountPaise;
    }
    summary.net = summary.credits - summary.debits;
    summary.averagePaise = summary.count == 0 ? 0 : Money.divide(total, summary.count);
    return summary;
  }

  public static long dayStart(long time) {
    return start(time).getTimeInMillis();
  }

  public static SimpleDateFormat monthLabel() {
    return new SimpleDateFormat("MMM", Locale.US);
  }

  private static Calendar start(long time) {
    Calendar calendar = Calendar.getInstance();
    calendar.setTimeInMillis(time);
    calendar.set(Calendar.HOUR_OF_DAY, 0);
    calendar.set(Calendar.MINUTE, 0);
    calendar.set(Calendar.SECOND, 0);
    calendar.set(Calendar.MILLISECOND, 0);
    return calendar;
  }

  private static String format(Calendar calendar, String pattern) {
    return new SimpleDateFormat(pattern, Locale.US).format(calendar.getTime());
  }

  private static String category(LedgerTransaction transaction) {
    return transaction.category == null || transaction.category.isEmpty() ? "Other" : transaction.category;
  }

  private static String beneficiary(LedgerTransaction transaction) {
    if (!transaction.counterparty.isEmpty()) return transaction.counterparty;
    if (!transaction.upiAddress.isEmpty()) return transaction.upiAddress;
    return "Unknown";
  }

  private static void add(Map<String, Long> map, String key, long amount) {
    Long current = map.get(key);
    map.put(key, (current == null ? 0 : current) + amount);
  }

  private static void add(Map<String, Integer> map, String key, int amount) {
    Integer current = map.get(key);
    map.put(key, (current == null ? 0 : current) + amount);
  }
}
