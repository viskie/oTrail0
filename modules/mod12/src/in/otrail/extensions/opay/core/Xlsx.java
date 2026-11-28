package in.otrail.extensions.opay.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads the first worksheet of an XLSX file using only java.util.zip and a tolerant
 * tag scan. Bank exports are simple shared-string/number sheets; charts, formulas
 * and cell styles are ignored. The file size is capped by the importer.
 */
public final class Xlsx {
  private static final Pattern ROW = Pattern.compile("<row\\b[^>]*>(.*?)</row>", Pattern.DOTALL);
  private static final Pattern CELL = Pattern.compile("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", Pattern.DOTALL);
  private static final Pattern VALUE = Pattern.compile("<v>(.*?)</v>", Pattern.DOTALL);
  private static final Pattern INLINE = Pattern.compile("<t[^>]*>(.*?)</t>", Pattern.DOTALL);
  private static final Pattern REFERENCE = Pattern.compile("r=\"([A-Z]+)[0-9]+\"");
  private static final Pattern STRING_ITEM = Pattern.compile("<si>(.*?)</si>", Pattern.DOTALL);

  private Xlsx() {}

  public static List<List<String>> read(byte[] data) throws IOException {
    Map<String, ByteArrayOutputStream> entries = new HashMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        if (!name.equals("xl/sharedStrings.xml") && !name.startsWith("xl/worksheets/sheet")) continue;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        long total = 0;
        while ((read = zip.read(buffer)) > 0) {
          total += read;
          if (total > 32L * 1024 * 1024) throw new IOException("XLSX sheet is too large");
          out.write(buffer, 0, read);
        }
        entries.put(name, out);
      }
    }
    if (entries.isEmpty()) throw new IOException("Not an XLSX workbook");
    List<String> shared = new ArrayList<>();
    ByteArrayOutputStream sharedEntry = entries.get("xl/sharedStrings.xml");
    if (sharedEntry != null) {
      String xml = sharedEntry.toString("UTF-8");
      Matcher item = STRING_ITEM.matcher(xml);
      while (item.find()) {
        StringBuilder text = new StringBuilder();
        Matcher part = INLINE.matcher(item.group(1));
        while (part.find()) text.append(unescape(part.group(1)));
        shared.add(text.toString());
      }
    }
    String sheetName = null;
    for (String name : entries.keySet())
      if (name.startsWith("xl/worksheets/sheet") && (sheetName == null || name.compareTo(sheetName) < 0))
        sheetName = name;
    if (sheetName == null) throw new IOException("XLSX workbook has no worksheet");
    String xml = entries.get(sheetName).toString("UTF-8");
    List<List<String>> rows = new ArrayList<>();
    Matcher row = ROW.matcher(xml);
    while (row.find()) {
      List<String> cells = new ArrayList<>();
      Matcher cell = CELL.matcher(row.group(1));
      int column = 0;
      while (cell.find()) {
        String attributes = cell.group(1) == null ? "" : cell.group(1);
        String body = cell.group(2) == null ? "" : cell.group(2);
        Matcher reference = REFERENCE.matcher(attributes);
        if (reference.find()) column = columnIndex(reference.group(1));
        while (cells.size() < column) cells.add("");
        String value = "";
        Matcher number = VALUE.matcher(body);
        Matcher inline = INLINE.matcher(body);
        if (attributes.contains("t=\"s\"") && number.find()) {
          try {
            int index = (int) Double.parseDouble(number.group(1));
            if (index >= 0 && index < shared.size()) value = shared.get(index);
          } catch (NumberFormatException ignored) {
            value = "";
          }
        } else if (attributes.contains("t=\"inlineStr\"") && inline.find()) {
          value = unescape(inline.group(1));
        } else if (number.find()) {
          value = unescape(number.group(1));
        } else if (inline.find()) {
          value = unescape(inline.group(1));
        }
        cells.add(value);
        column++;
      }
      if (!blank(cells)) rows.add(cells);
    }
    return rows;
  }

  static String unescape(String text) {
    return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&");
  }

  private static int columnIndex(String letters) {
    int value = 0;
    for (int index = 0; index < letters.length(); index++)
      value = value * 26 + (letters.charAt(index) - 'A' + 1);
    return Math.max(0, value - 1);
  }

  private static boolean blank(List<String> row) {
    for (String cell : row) if (cell != null && !cell.trim().isEmpty()) return false;
    return true;
  }

  /** Excel serial day number to epoch millis (1900 date system, as used by banks). */
  public static long serialDate(double serial) {
    long days = (long) Math.floor(serial);
    double fraction = serial - days;
    long epoch;

    if (days > 59) {
      epoch = (days - 25569L) * 86_400_000L;
    } else {
      epoch = (days - 25568L) * 86_400_000L;
    }
    return epoch + Math.round(fraction * 86_400_000d);
  }
}
