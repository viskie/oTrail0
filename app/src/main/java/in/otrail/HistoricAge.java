package in.otrail;

import java.util.regex.*;

/** Keep source dates and explicit uncertainty; never infer a monument's age from its name. */
public record HistoricAge(int earliest, int latest, boolean approximate) {
  public static HistoricAge parse(String text) {
    if (text == null) return null;
    String value = text.trim().toLowerCase(java.util.Locale.ROOT);
    Matcher year =
        Pattern.compile("^(~|c\\.?\\s*|circa\\s*)?(\\d{4})(?:-\\d{2}(?:-\\d{2})?)?$")
            .matcher(value);
    if (year.matches()) {
      int y = Integer.parseInt(year.group(2));
      return y > 0 ? new HistoricAge(y, y, year.group(1) != null) : null;
    }
    Matcher century =
        Pattern.compile("^(?:c(\\d{1,2})|(\\d{1,2})(?:st|nd|rd|th) century)$").matcher(value);
    if (century.matches()) {
      int c = Integer.parseInt(century.group(1) != null ? century.group(1) : century.group(2));
      return c > 0 ? new HistoricAge((c - 1) * 100, c * 100 - 1, true) : null;
    }
    Matcher range = Pattern.compile("^(\\d{4})\\.\\.(\\d{4})$").matcher(value);
    if (range.matches()) {
      int a = Integer.parseInt(range.group(1)), b = Integer.parseInt(range.group(2));
      return a > 0 && b >= a ? new HistoricAge(a, b, true) : null;
    }
    return null;
  }

  public boolean inWindow(int currentYear) {
    return currentYear - latest >= 200 && currentYear - earliest <= 600;
  }
}
