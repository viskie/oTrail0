package in.otrail.extensions.opay.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Extracts embedded text from digitally generated bank PDFs. This is deliberately a
 * small reader, not a PDF renderer: it walks uncompressed or Flate-compressed content
 * streams and reads Tj/TJ/' operators. Scanned, image-only and encrypted statements
 * are reported as unsupported instead of silently producing an empty import.
 */
public final class PdfText {
  private PdfText() {}

  public static String extract(byte[] data) throws IOException {
    if (data == null || data.length == 0) throw new IOException("Empty PDF");
    String raw = new String(data, StandardCharsets.ISO_8859_1);
    if (!raw.startsWith("%PDF") && raw.indexOf("%PDF") < 0) throw new IOException("Not a PDF file");
    if (raw.contains("/Encrypt")) throw new IOException("Password-protected PDFs cannot be read offline");
    StringBuilder text = new StringBuilder();
    int at = 0;
    int streams = 0;
    while (true) {
      int stream = raw.indexOf("stream", at);
      if (stream < 0) break;
      int dictionaryStart = Math.max(0, stream - 700);
      String dictionary = raw.substring(dictionaryStart, stream);
      int start = stream + 6;
      if (start < raw.length() && raw.charAt(start) == '\r') start++;
      if (start < raw.length() && raw.charAt(start) == '\n') start++;
      int end = raw.indexOf("endstream", start);
      if (end < 0) break;
      int dataEnd = end;
      while (dataEnd > start && (raw.charAt(dataEnd - 1) == '\n' || raw.charAt(dataEnd - 1) == '\r')) dataEnd--;
      byte[] content = new byte[dataEnd - start];
      System.arraycopy(data, start, content, 0, content.length);
      streams++;
      if (dictionary.contains("FlateDecode")) {
        byte[] decoded = inflate(content);
        if (decoded != null) readContent(new String(decoded, StandardCharsets.ISO_8859_1), text);
      } else if (!dictionary.contains("/Filter")) {
        readContent(new String(content, StandardCharsets.ISO_8859_1), text);
      }
      at = end + 9;
    }
    if (streams == 0) throw new IOException("PDF has no readable content streams");
    String cleaned = text.toString().replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    if (cleaned.isEmpty())
      throw new IOException("No embedded text found; this looks like a scanned statement");
    return cleaned;
  }

  private static byte[] inflate(byte[] data) {
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(data);
      ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, data.length * 3));
      byte[] buffer = new byte[8192];
      while (!inflater.finished()) {
        int count = inflater.inflate(buffer);
        if (count == 0) {
          if (inflater.needsInput() || inflater.needsDictionary()) break;
          continue;
        }
        out.write(buffer, 0, count);
        if (out.size() > 64 * 1024 * 1024) break;
      }
      return out.size() == 0 ? null : out.toByteArray();
    } catch (DataFormatException error) {
      return null;
    } finally {
      inflater.end();
    }
  }

  private static void readContent(String content, StringBuilder out) {
    int index = 0;
    while (index < content.length()) {
      char character = content.charAt(index);
      if (character == '(') {
        StringBuilder literal = new StringBuilder();
        index = literalString(content, index + 1, literal);
        out.append(literal);
        index = afterOperator(content, index);
        continue;
      }
      if (character == '<' && (index + 1 >= content.length() || content.charAt(index + 1) != '<')) {
        int close = content.indexOf('>', index);
        if (close < 0) break;
        out.append(hexString(content.substring(index + 1, close)));
        index = afterOperator(content, close + 1);
        continue;
      }
      if (content.startsWith("Tm", index) || content.startsWith("Td", index)
          || content.startsWith("TD", index) || content.startsWith("T*", index)
          || content.startsWith("ET", index)) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
        index += 2;
        continue;
      }
      index++;
    }
  }

  private static int afterOperator(String content, int index) {
    int at = index;
    while (at < content.length() && Character.isWhitespace(content.charAt(at))) at++;
    if (at + 1 < content.length()) {
      String operator = content.substring(at, Math.min(at + 2, content.length()));
      if (operator.equals("Tj") || operator.equals("'\"") || operator.equals("TJ")) return at + 2;
      if (operator.equals("' ")) return at + 1;
    }
    return at;
  }

  private static int literalString(String content, int index, StringBuilder out) {
    int depth = 1;
    while (index < content.length()) {
      char character = content.charAt(index++);
      if (character == '\\') {
        if (index >= content.length()) break;
        char escaped = content.charAt(index++);
        switch (escaped) {
          case 'n': out.append('\n'); break;
          case 'r': out.append('\r'); break;
          case 't': out.append('\t'); break;
          case 'b': out.append('\b'); break;
          case 'f': out.append('\f'); break;
          case '(': out.append('('); break;
          case ')': out.append(')'); break;
          case '\\': out.append('\\'); break;
          case '\r':
            if (index < content.length() && content.charAt(index) == '\n') index++;
            break;
          case '\n':
            break;
          default:
            if (escaped >= '0' && escaped <= '7') {
              int value = escaped - '0';
              for (int count = 1; count < 3 && index < content.length(); count++) {
                char digit = content.charAt(index);
                if (digit < '0' || digit > '7') break;
                value = value * 8 + (digit - '0');
                index++;
              }
              out.append((char) (value & 0xff));
            } else {
              out.append(escaped);
            }
        }
        continue;
      }
      if (character == '(') {
        depth++;
        out.append('(');
        continue;
      }
      if (character == ')') {
        depth--;
        if (depth == 0) return index;
        out.append(')');
        continue;
      }
      out.append(character);
    }
    return index;
  }

  private static String hexString(String hex) {
    String digits = hex.replaceAll("[^0-9A-Fa-f]", "");
    if (digits.length() % 2 == 1) digits = digits + "0";
    byte[] bytes = new byte[digits.length() / 2];
    for (int index = 0; index < bytes.length; index++)
      bytes[index] = (byte) Integer.parseInt(digits.substring(index * 2, index * 2 + 2), 16);
    if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff)
      return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
    return new String(bytes, StandardCharsets.ISO_8859_1);
  }
}
