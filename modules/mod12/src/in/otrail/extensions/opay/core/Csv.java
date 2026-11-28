package in.otrail.extensions.opay.core;

import java.util.ArrayList;
import java.util.List;

/** RFC 4180 style delimited-text reader with delimiter auto-detection. */
public final class Csv {
  private Csv() {}

  public static List<List<String>> read(String text) {
    if (text == null || text.trim().isEmpty()) return new ArrayList<>();
    char delimiter = delimiter(text);
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      if (quoted) {
        if (character == '"') {
          if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
            field.append('"');
            index++;
          } else {
            quoted = false;
          }
        } else {
          field.append(character);
        }
      } else if (character == '"') {
        quoted = true;
      } else if (character == delimiter) {
        row.add(field.toString().trim());
        field.setLength(0);
      } else if (character == '\r' || character == '\n') {
        if (character == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') index++;
        row.add(field.toString().trim());
        field.setLength(0);
        if (!isBlank(row)) rows.add(row);
        row = new ArrayList<>();
      } else {
        field.append(character);
      }
    }
    row.add(field.toString().trim());
    if (!isBlank(row)) rows.add(row);
    return rows;
  }

  /** Chooses the delimiter that yields the most columns on the first ten lines. */
  static char delimiter(String text) {
    char[] candidates = {',', '\t', ';', '|'};
    char best = ',';
    int bestColumns = 0;
    String head = text.length() > 8000 ? text.substring(0, 8000) : text;
    for (char candidate : candidates) {
      List<List<String>> sample = split(head, candidate, 10);
      int columns = 0;
      for (List<String> row : sample) columns = Math.max(columns, row.size());
      if (columns > bestColumns) {
        bestColumns = columns;
        best = candidate;
      }
    }
    return best;
  }

  private static List<List<String>> split(String text, char delimiter, int limit) {
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    for (int index = 0; index < text.length() && rows.size() < limit; index++) {
      char character = text.charAt(index);
      if (character == '"') {
        quoted = !quoted;
      } else if (!quoted && character == delimiter) {
        row.add(field.toString());
        field.setLength(0);
      } else if (!quoted && (character == '\n' || character == '\r')) {
        row.add(field.toString());
        field.setLength(0);
        if (!isBlank(row)) rows.add(row);
        row = new ArrayList<>();
      } else {
        field.append(character);
      }
    }
    if (!field.toString().trim().isEmpty()) row.add(field.toString());
    if (!isBlank(row)) rows.add(row);
    return rows;
  }

  private static boolean isBlank(List<String> row) {
    for (String cell : row) if (cell != null && !cell.trim().isEmpty()) return false;
    return true;
  }

  public static String escape(String value) {
    String text = value == null ? "" : value;
    boolean quote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0
        || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
    if (!quote) return text;
    return '"' + text.replace("\"", "\"\"") + '"';
  }
}
