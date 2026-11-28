package in.otrail.extensions.pop3;

import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal MIME reader: headers, encodings and the text part of a message. */
final class Mime {
  private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
  private static final Pattern ENCODED_WORD =
      Pattern.compile("=\\?([^?\\s]+)\\?([BbQq])\\?([^?\\s]*)\\?=");
  private static final Pattern TAG = Pattern.compile("<[^>]+>");

  private Mime() {}

  static Pop3Message parse(byte[] raw) {
    Pop3Message message = new Pop3Message();
    String text = new String(raw, LATIN1);
    int split = splitHeader(text);
    String headerBlock = split < 0 ? text : text.substring(0, split);
    String body = split < 0 ? "" : text.substring(split + (text.startsWith("\r\n\r\n", split) ? 4 : 2));
    Map<String, String> headers = headers(headerBlock);

    message.subject = decodeHeader(headers.get("subject"));
    message.from = decodeHeader(headers.get("from"));
    message.date = decodeHeader(headers.get("date"));
    message.time = parseDate(message.date);

    StringBuilder plain = new StringBuilder();
    StringBuilder html = new StringBuilder();
    collect(headers, body, plain, html, 0);
    String chosen = plain.toString().trim();
    if (chosen.isEmpty()) chosen = htmlToText(html.toString());
    message.body = clean(chosen);
    return message;
  }

  private static int splitHeader(String text) {
    int a = text.indexOf("\r\n\r\n");
    int b = text.indexOf("\n\n");
    if (a < 0) return b;
    if (b < 0) return a;
    return Math.min(a, b);
  }

  private static void collect(Map<String, String> headers, String body,
      StringBuilder plain, StringBuilder html, int depth) {
    if (depth > 6) return;
    String contentType = headers.get("content-type");
    if (contentType == null) contentType = "text/plain";
    String lower = contentType.toLowerCase(Locale.US);
    String boundary = parameter(contentType, "boundary");
    if (lower.startsWith("multipart/") && boundary != null && !boundary.isEmpty()) {
      for (String part : splitParts(body, boundary)) {
        int split = splitHeader(part);
        String partHeaders = split < 0 ? part : part.substring(0, split);
        String partBody = split < 0 ? ""
            : part.substring(split + (part.startsWith("\r\n\r\n", split) ? 4 : 2));
        collect(headers(partHeaders), partBody, plain, html, depth + 1);
      }
      return;
    }
    String decoded = decodeBody(headers, body);
    if (lower.startsWith("text/html")) {
      if (html.length() > 0) html.append("\n");
      html.append(decoded);
    } else if (lower.startsWith("text/") || lower.isEmpty()) {
      if (plain.length() > 0) plain.append("\n");
      plain.append(decoded);
    }
  }

  private static List<String> splitParts(String body, String boundary) {
    List<String> parts = new ArrayList<>();
    String marker = "--" + boundary;
    int index = body.indexOf(marker);
    while (index >= 0) {
      int lineEnd = body.indexOf('\n', index);
      if (lineEnd < 0) break;
      boolean closing = body.startsWith("--", lineEnd + 1);
      if (closing) break;
      int next = body.indexOf("\n" + marker, lineEnd);
      int start = lineEnd + 1;
      int end = next < 0 ? body.length() : next;
      if (end > start) parts.add(body.substring(start, end));
      if (next < 0) break;
      index = next + 1;
    }
    return parts;
  }

  private static Map<String, String> headers(String block) {
    Map<String, String> map = new HashMap<>();
    StringBuilder current = new StringBuilder();
    String currentKey = null;
    for (String line : block.split("\r?\n")) {
      if ((line.startsWith(" ") || line.startsWith("\t")) && currentKey != null) {
        current.append(' ').append(line.trim());
        continue;
      }
      if (currentKey != null) map.put(currentKey, current.toString());
      int colon = line.indexOf(':');
      if (colon <= 0) {
        currentKey = null;
        current.setLength(0);
        continue;
      }
      currentKey = line.substring(0, colon).trim().toLowerCase(Locale.US);
      current.setLength(0);
      current.append(line.substring(colon + 1).trim());
    }
    if (currentKey != null) map.put(currentKey, current.toString());
    return map;
  }

  private static String decodeBody(Map<String, String> headers, String body) {
    String cte = headers.get("content-transfer-encoding");
    String encoding = cte == null ? "" : cte.trim().toLowerCase(Locale.US);
    byte[] bytes;
    if (encoding.startsWith("base64")) {
      try {
        bytes = Base64.decode(body.replaceAll("\\s+", ""), Base64.DEFAULT);
      } catch (IllegalArgumentException error) {
        bytes = body.getBytes(LATIN1);
      }
    } else if (encoding.startsWith("quoted-printable")) {
      bytes = quotedPrintable(body);
    } else {
      bytes = body.getBytes(LATIN1);
    }
    return new String(bytes, charset(headers.get("content-type")));
  }

  private static Charset charset(String contentType) {
    String value = parameter(contentType, "charset");
    if (value != null) {
      try {
        return Charset.forName(value.trim().replace("\"", ""));
      } catch (Exception ignored) {
        // Fall through to UTF-8.
      }
    }
    return Charset.forName("UTF-8");
  }

  private static String parameter(String header, String name) {
    if (header == null) return null;
    Matcher m = Pattern.compile("(?i)(?:^|;)\\s*" + Pattern.quote(name) + "\\s*=\\s*(\"[^\"]*\"|[^;\\s]+)")
        .matcher(header);
    if (!m.find()) return null;
    String value = m.group(1);
    return value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2
        ? value.substring(1, value.length() - 1) : value;
  }

  private static byte[] quotedPrintable(String value) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '=') {
        if (i + 1 < value.length() && (value.charAt(i + 1) == '\r' || value.charAt(i + 1) == '\n')) {
          i++;
          if (value.charAt(i) == '\r' && i + 1 < value.length() && value.charAt(i + 1) == '\n') i++;
          continue;
        }
        if (i + 2 < value.length()) {
          try {
            out.write(Integer.parseInt(value.substring(i + 1, i + 3), 16));
            i += 2;
            continue;
          } catch (NumberFormatException ignored) {
            // Emit the '=' literally.
          }
        }
      }
      out.write((byte) c);
    }
    return out.toByteArray();
  }

  private static String decodeHeader(String value) {
    if (value == null || value.isEmpty()) return "";
    Matcher m = ENCODED_WORD.matcher(value);
    StringBuffer out = new StringBuffer();
    while (m.find()) {
      String charsetName = m.group(1).split("\\*")[0];
      byte[] bytes;
      if (m.group(2).equalsIgnoreCase("B")) {
        try {
          bytes = Base64.decode(m.group(3), Base64.DEFAULT);
        } catch (IllegalArgumentException error) {
          bytes = m.group(3).getBytes(LATIN1);
        }
      } else {
        bytes = quotedPrintable(m.group(3).replace('_', ' '));
      }
      String decoded;
      try {
        decoded = new String(bytes, Charset.forName(charsetName));
      } catch (Exception error) {
        decoded = new String(bytes, Charset.forName("UTF-8"));
      }
      m.appendReplacement(out, Matcher.quoteReplacement(decoded));
    }
    m.appendTail(out);
    return out.toString().trim();
  }

  private static String clean(String value) {
    String text = value.replace("\r\n", "\n").replace('\r', '\n');
    text = text.replaceAll("\n{4,}", "\n\n\n");
    return text.trim();
  }

  private static String htmlToText(String html) {
    String text = html.replaceAll("(?i)<br\\s*/?>", "\n")
        .replaceAll("(?i)</p>", "\n\n")
        .replaceAll("(?i)</div>", "\n")
        .replaceAll("(?i)</li>", "\n")
        .replaceAll("(?i)<style.*?</style>", "")
        .replaceAll("(?i)<script.*?</script>", "");
    text = TAG.matcher(text).replaceAll("");
    text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
    return text;
  }

  private static long parseDate(String value) {
    if (value == null || value.isEmpty()) return 0;
    String[] patterns = {
        "EEE, d MMM yyyy HH:mm:ss Z",
        "d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm:ss zzz",
        "d MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy HH:mm Z"
    };
    for (String pattern : patterns) {
      try {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.parse(value).getTime();
      } catch (Exception ignored) {
        // Try the next pattern.
      }
    }
    return 0;
  }
}
