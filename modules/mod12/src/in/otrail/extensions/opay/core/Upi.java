package in.otrail.extensions.opay.core;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Standard UPI deep-link construction and validation. Only the documented
 * {@code upi://pay} scheme is produced; no provider-internal activity is called.
 */
public final class Upi {
  public static final String SCHEME = "upi";
  public static final String HOST_PAY = "pay";
  private static final Pattern VPA = Pattern.compile("[A-Za-z0-9.\\-_]{2,256}@[A-Za-z][A-Za-z0-9.\\-]{1,64}");
  private static final Pattern URL_SAFE = Pattern.compile("[A-Za-z0-9.\\-_~]");

  private Upi() {}

  /** A validated payment request parsed from a URI or assembled by the user. */
  public static final class Request {
    public String payeeAddress = "";
    public String payeeName = "";
    public long amountPaise;
    public String currency = Money.INR;
    public String note = "";
    public String reference = "";
    public final Map<String, String> extra = new LinkedHashMap<>();

    public boolean hasAmount() { return amountPaise > 0; }

    public String describe() {
      StringBuilder text = new StringBuilder(payeeName.isEmpty() ? payeeAddress : payeeName);
      if (!payeeAddress.isEmpty() && !payeeName.isEmpty()) text.append("\n").append(payeeAddress);
      if (hasAmount()) text.append("\n").append(Money.format(amountPaise));
      if (!note.isEmpty()) text.append("\n").append(note);
      return text.toString();
    }

    public String toUri() { return Upi.build(this); }
  }

  public static boolean validPayee(String address) {
    return address != null && VPA.matcher(address.trim()).matches();
  }

  public static String build(String payeeAddress, String payeeName, long amountPaise,
      String note, String reference) {
    Request request = new Request();
    request.payeeAddress = payeeAddress == null ? "" : payeeAddress.trim();
    request.payeeName = payeeName == null ? "" : payeeName.trim();
    request.amountPaise = amountPaise;
    request.note = note == null ? "" : note.trim();
    request.reference = reference == null ? "" : reference.trim();
    return build(request);
  }

  public static String build(Request request) {
    if (!validPayee(request.payeeAddress))
      throw new IllegalArgumentException("Enter a valid UPI ID such as name@bank");
    if (request.amountPaise < 0) throw new IllegalArgumentException("Amount cannot be negative");
    StringBuilder uri = new StringBuilder("upi://pay?pa=").append(encode(request.payeeAddress));
    append(uri, "pn", request.payeeName);
    if (request.amountPaise > 0) append(uri, "am", Money.rupees(request.amountPaise));
    append(uri, "cu", request.currency == null || request.currency.isEmpty() ? Money.INR : request.currency);
    append(uri, "tn", trim(request.note, 80));
    append(uri, "tr", trim(request.reference, 35));
    for (Map.Entry<String, String> entry : request.extra.entrySet()) {
      String key = entry.getKey();
      if (key == null || !key.matches("[A-Za-z0-9._-]{1,32}")) continue;
      append(uri, key, entry.getValue());
    }
    return uri.toString();
  }

  /** Parses any string that contains a upi://pay URI (raw QR text or clipboard text). */
  public static Request parse(String raw) {
    if (raw == null) throw new IllegalArgumentException("Empty UPI text");
    String text = raw.trim();
    int start = text.toLowerCase(Locale.US).indexOf("upi://");
    if (start < 0) throw new IllegalArgumentException("Not a UPI payment code");
    text = text.substring(start);
    int end = 0;
    while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
    text = text.substring(0, end);
    String lower = text.toLowerCase(Locale.US);
    if (!lower.startsWith("upi://")) throw new IllegalArgumentException("Not a UPI payment code");
    String afterScheme = text.substring(6);
    int queryAt = afterScheme.indexOf('?');
    String host = (queryAt < 0 ? afterScheme : afterScheme.substring(0, queryAt))
        .replaceAll("/+$", "").trim();
    if (!host.equalsIgnoreCase(HOST_PAY))
      throw new IllegalArgumentException("Only upi://pay links can be used for payment");
    Request request = new Request();
    if (queryAt >= 0) {
      for (String pair : afterScheme.substring(queryAt + 1).split("&")) {
        if (pair.isEmpty()) continue;
        int equals = pair.indexOf('=');
        String key = decode(equals < 0 ? pair : pair.substring(0, equals));
        String value = decode(equals < 0 ? "" : pair.substring(equals + 1));
        String name = key.toLowerCase(Locale.US);
        if (name.equals("pa")) request.payeeAddress = value.trim();
        else if (name.equals("pn")) request.payeeName = value.trim();
        else if (name.equals("am") || name.equals("amount")) {
          Long paise = Money.parse(value);
          if (paise == null || paise < 0) throw new IllegalArgumentException("Invalid amount in UPI code");
          request.amountPaise = paise;
        } else if (name.equals("cu")) request.currency = value.trim().isEmpty() ? Money.INR : value.trim();
        else if (name.equals("tn")) request.note = value.trim();
        else if (name.equals("tr")) request.reference = value.trim();
        else if (name.equals("mode") || name.equals("orgid") || name.equals("sign")
            || name.equals("mc") || name.equals("tid") || name.equals("url")) {
          request.extra.put(name, value);
        }
      }
    }
    if (!validPayee(request.payeeAddress))
      throw new IllegalArgumentException("The QR code has no valid UPI ID");
    if (!request.currency.equalsIgnoreCase(Money.INR))
      throw new IllegalArgumentException("Only INR UPI payments are supported");
    request.currency = Money.INR;
    if (request.amountPaise > 100_000_000_000L)
      throw new IllegalArgumentException("Amount is too large");
    return request;
  }

  /** Builds the receiving QR for this app's own UPI ID. */
  public static String receive(String payeeAddress, String payeeName, long amountPaise, String note) {
    Request request = new Request();
    request.payeeAddress = payeeAddress;
    request.payeeName = payeeName;
    request.amountPaise = amountPaise;
    request.note = note;
    return build(request);
  }

  private static void append(StringBuilder uri, String key, String value) {
    if (value == null || value.trim().isEmpty()) return;
    uri.append('&').append(key).append('=').append(encode(value.trim()));
  }

  private static String encode(String value) {
    StringBuilder out = new StringBuilder();
    for (byte raw : utf8(value)) {
      int unsigned = raw & 0xff;
      if (URL_SAFE.matcher(String.valueOf((char) unsigned)).matches()) {
        out.append((char) unsigned);
      } else {
        out.append('%').append(Character.toUpperCase(Character.forDigit(unsigned >> 4, 16)))
            .append(Character.toUpperCase(Character.forDigit(unsigned & 0xf, 16)));
      }
    }
    return out.toString();
  }

  private static String decode(String value) {
    try {
      return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8");
    } catch (UnsupportedEncodingException impossible) {
      return value;
    }
  }

  private static byte[] utf8(String value) {
    try {
      return value.getBytes("UTF-8");
    } catch (UnsupportedEncodingException impossible) {
      return value.getBytes();
    }
  }

  private static String trim(String value, int length) {
    if (value == null) return "";
    String cleaned = value.replaceAll("[\\r\\n]+", " ").trim();
    return cleaned.length() <= length ? cleaned : cleaned.substring(0, length);
  }

  /** Kotlin-free helper kept for callers that only have a display name. */
  public static String displayName(String payeeAddress) {
    if (payeeAddress == null) return "";
    int at = payeeAddress.indexOf('@');
    return at <= 0 ? payeeAddress : payeeAddress.substring(0, at);
  }

  /** Placeholder used by the encoder so a caller can show a reference without one. */
  public static String generatedReference() {
    return Long.toString(System.currentTimeMillis(), 36).toUpperCase(Locale.US);
  }
}
