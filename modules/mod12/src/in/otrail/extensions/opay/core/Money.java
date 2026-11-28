package in.otrail.extensions.opay.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Integer-paise arithmetic for money. No floating point is ever used for amounts. */
public final class Money {
  public static final long PAISE_PER_RUPEE = 100L;
  public static final String INR = "INR";

  private Money() {}

  /** Parses "1,23,456.78", "₹1200", "-45.5" into paise. Returns null when unusable. */
  public static Long parse(String text) {
    if (text == null) return null;
    String cleaned = text.trim().replaceAll("[\\s,\u00a0]", "")
        .replace("\u20b9", "").replace("Rs.", "").replace("Rs", "").replace("INR", "");
    if (cleaned.isEmpty()) return null;
    boolean negative = false;
    if (cleaned.startsWith("-")) {
      negative = true;
      cleaned = cleaned.substring(1);
    } else if (cleaned.startsWith("+")) {
      cleaned = cleaned.substring(1);
    }
    if (cleaned.startsWith("(") && cleaned.endsWith(")")) {
      negative = true;
      cleaned = cleaned.substring(1, cleaned.length() - 1);
    }
    if (cleaned.isEmpty()) return null;
    String[] parts = cleaned.split("\\.", -1);
    if (parts.length > 2) return null;
    String whole = parts[0].isEmpty() ? "0" : parts[0];
    String fraction = parts.length == 2 ? parts[1] : "";
    if (!whole.matches("[0-9]+")) return null;
    if (fraction.length() > 2) {
      if (!fraction.substring(2).matches("0*")) return null;
      fraction = fraction.substring(0, 2);
    }
    while (fraction.length() < 2) fraction = fraction + "0";
    try {
      long rupees = Long.parseLong(whole);
      long paise = rupees * PAISE_PER_RUPEE + Long.parseLong(fraction);
      return negative ? -paise : paise;
    } catch (NumberFormatException overflow) {
      return null;
    }
  }

  /** Extracts the first amount-looking token from free text, e.g. a bank SMS. */
  public static Long firstAmount(String text) {
    if (text == null) return null;
    java.util.regex.Matcher matcher = java.util.regex.Pattern
        .compile("(?:INR|Rs\\.?|\u20b9)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
        .matcher(text);
    if (matcher.find()) return parse(matcher.group(1));
    matcher = java.util.regex.Pattern.compile("([0-9][0-9,]*\\.[0-9]{2})\\s*(?:INR|Rs\\.?)?")
        .matcher(text);
    if (matcher.find()) return parse(matcher.group(1));
    return null;
  }

  /** Dividend-safe split of paise into major units, used by report buckets. */
  public static long divide(long paise, long divisor) {
    if (divisor == 0) return 0;
    return BigDecimal.valueOf(paise).divide(BigDecimal.valueOf(divisor), 0, RoundingMode.HALF_UP).longValue();
  }

  public static String format(long paise) {
    return "\u20b9" + plain(paise);
  }

  /** "1,23,456.78" using Indian digit grouping. */
  public static String plain(long paise) {
    boolean negative = paise < 0;
    long absolute = Math.abs(paise);
    long rupees = absolute / PAISE_PER_RUPEE;
    long fraction = absolute % PAISE_PER_RUPEE;
    StringBuilder grouped = new StringBuilder(Long.toString(rupees));
    if (grouped.length() > 3) {
      int end = grouped.length() - 3;
      grouped.insert(end, ',');
      for (int index = end - 2; index > 0; index -= 2) grouped.insert(index, ',');
    }
    return (negative ? "-" : "") + grouped + "." + (fraction < 10 ? "0" : "") + fraction;
  }

  /** CSV/statement input where the currency may be fractional but not sub-paise. */
  public static String rupees(long paise) {
    return format(paise).replace("\u20b9", "").replace(",", "");
  }

  public static String signed(long paise) {
    return (paise > 0 ? "+" : "") + format(paise);
  }

  public static String percent(long part, long total) {
    if (total == 0) return "0%";
    return String.format(Locale.US, "%.0f%%", part * 100.0 / total);
  }
}
