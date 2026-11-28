package in.otrail.extensions.opay.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a user-selected statement into normalized ledger rows.
 *
 * <p>No provider-specific private API or sandbox is touched. CSV/XLSX files are read
 * with automatic column detection (and a manual mapping override); PDFs need embedded
 * text; JSON and pasted transaction text are also accepted. Duplicate rows inside a
 * batch are removed before the preview reaches the ledger.
 */
public final class StatementImport {
  public enum Format { CSV, XLSX, PDF, JSON, SMS, TEXT, UNKNOWN }

  public static final int DATE = 0, DESCRIPTION = 1, DEBIT = 2, CREDIT = 3, AMOUNT = 4,
      TYPE = 5, REFERENCE = 6, UPI = 7, CATEGORY = 8, ROLE_COUNT = 9;
  public static final String[] ROLE_NAMES = {
      "date", "description", "debit", "credit", "amount", "type", "reference", "upi", "category"
  };

  private static final Set<String> DATE_KEYS = words("date", "txndate", "transactiondate",
      "valuedate", "postingdate", "timestamp", "datetime", "day", "txn_date");
  private static final Set<String> DESCRIPTION_KEYS = words("description", "narration",
      "particulars", "details", "remarks", "transactiondetails", "name", "payee", "merchant",
      "sender", "beneficiary", "transactiondescription");
  private static final Set<String> DEBIT_KEYS = words("debit", "debitamount", "withdrawal",
      "withdrawalamt", "withdrawalamount", "dr", "paidout", "moneyout", "debitamt");
  private static final Set<String> CREDIT_KEYS = words("credit", "creditamount", "deposit",
      "depositamt", "depositamount", "cr", "paidin", "moneyin", "creditamt");
  private static final Set<String> AMOUNT_KEYS = words("amount", "txnamount", "transactionamount",
      "value", "amt", "paymentamount");
  private static final Set<String> TYPE_KEYS = words("type", "txntype", "transactiontype",
      "drcr", "debitcredit", "cr_dr", "mode");
  private static final Set<String> REFERENCE_KEYS = words("utr", "rrn", "reference", "refno",
      "referenceno", "transactionid", "txnid", "chequeno", "ref", "bankreference", "approvalref");
  private static final Set<String> UPI_KEYS = words("upiid", "vpa", "upiaddress", "upi", "payeevpa");
  private static final Set<String> CATEGORY_KEYS = words("category", "tag", "label");

  private static final Pattern VPA_TEXT = Pattern.compile("([A-Za-z0-9.\\-_]{2,256}@[A-Za-z][A-Za-z0-9.\\-]{1,64})");
  private static final Pattern UTR_TEXT = Pattern.compile("(?i)(?:utr|rrn|ref(?:erence)?(?:\\s*(?:no|id))?)\\s*[:#-]?\\s*([A-Za-z0-9]{8,24})");
  private static final Pattern MASKED = Pattern.compile("(?i)(?:a/c|account|acct)\\D{0,10}(?:x+\\s*)?(\\d{3,6})");
  private static final String[] DATE_PATTERNS = {
      "dd/MM/yyyy", "dd-MM-yyyy", "d/M/yyyy", "d-M-yyyy", "dd/MM/yy", "dd-MM-yy",
      "yyyy-MM-dd", "yyyy/MM/dd", "dd MMM yyyy", "d MMM yyyy", "dd-MMM-yyyy", "dd MMM yy",
      "dd-MMM-yy", "MM/dd/yyyy", "MMM d, yyyy", "dd.MM.yyyy", "yyyy-MM-dd HH:mm:ss",
      "dd/MM/yyyy HH:mm", "dd-MM-yyyy HH:mm"
  };

  private StatementImport() {}

  public static final class Result {
    public Format format = Format.UNKNOWN;
    public String provider = "";
    public final List<LedgerTransaction> rows = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public final List<String> headers = new ArrayList<>();
    public final List<List<String>> table = new ArrayList<>();
    public int[] mapping = autoMapping(new ArrayList<>());
    public int skipped;
    public int duplicates;
    public int headerRow = -1;

    public boolean needsMapping() {
      return (format == Format.CSV || format == Format.XLSX) && !table.isEmpty()
          && (mapping[DATE] < 0 || (mapping[AMOUNT] < 0 && mapping[DEBIT] < 0 && mapping[CREDIT] < 0));
    }

    public String summary() {
      StringBuilder text = new StringBuilder(format.name());
      if (!provider.isEmpty()) text.append(" \u00b7 ").append(provider);
      text.append(" \u00b7 ").append(rows.size()).append(" rows");
      if (duplicates > 0) text.append(" \u00b7 ").append(duplicates).append(" duplicates skipped");
      if (skipped > 0) text.append(" \u00b7 ").append(skipped).append(" unreadable lines");
      return text.toString();
    }
  }

  // ------------------------------------------------------------------ detection

  public static Format detect(String name, byte[] data) {
    String lower = name == null ? "" : name.toLowerCase(Locale.US);
    if (data != null && data.length > 4) {
      String magic = new String(data, 0, Math.min(5, data.length), StandardCharsets.ISO_8859_1);
      if (magic.startsWith("%PDF")) return Format.PDF;
      if ((data[0] & 0xff) == 'P' && (data[1] & 0xff) == 'K') return Format.XLSX;
    }
    String head = data == null ? "" : new String(data, 0, Math.min(4096, data.length), StandardCharsets.UTF_8).trim();
    if (head.startsWith("{") || head.startsWith("[")) return Format.JSON;
    if (lower.endsWith(".pdf")) return Format.PDF;
    if (lower.endsWith(".xlsx")) return Format.XLSX;
    if (lower.endsWith(".json")) return Format.JSON;
    if (lower.endsWith(".csv") || lower.endsWith(".tsv")) return Format.CSV;
    if (lower.endsWith(".txt") || lower.endsWith(".eml")) return Format.SMS;
    if (head.indexOf(',') >= 0 && head.indexOf('\n') >= 0) return Format.CSV;
    if (head.toLowerCase(Locale.US).matches("(?s).*(rs\\.?|inr|debited|credited|upi).*")) return Format.SMS;
    return Format.UNKNOWN;
  }

  public static String detectProvider(String text, String name) {
    String value = ((name == null ? "" : name) + "\n" + (text == null ? "" : text)).toLowerCase(Locale.US);
    if (value.contains("phonepe")) return "PhonePe";
    if (value.contains("jio finance") || value.contains("jiofinance") || value.contains("jio payments bank"))
      return "JioFinance";
    if (value.contains("paytm")) return "Paytm";
    return "";
  }

  // ------------------------------------------------------------------ entry points

  public static Result parse(String name, byte[] data, String accountId, String accountMasked)
      throws IOException {
    if (data == null || data.length == 0) throw new IOException("The selected file is empty");
    if (data.length > 32 * 1024 * 1024) throw new IOException("Statement is larger than 32 MB");
    Format format = detect(name, data);
    Result result;
    switch (format) {
      case CSV:
        result = parseCsv(new String(data, StandardCharsets.UTF_8), accountId, accountMasked);
        break;
      case XLSX:
        result = parseTable(Xlsx.read(data), accountId, accountMasked, Format.XLSX);
        break;
      case PDF:
        result = parseText(PdfText.extract(data), accountId, accountMasked, Format.PDF);
        result.warnings.add("PDF text extraction is best-effort; verify the preview against the statement.");
        break;
      case JSON:
        result = parseJson(new String(data, StandardCharsets.UTF_8), accountId, accountMasked);
        break;
      case SMS:
        result = parseSmsText(new String(data, StandardCharsets.UTF_8), accountId, accountMasked);
        break;
      default:
        throw new IOException("Unsupported statement format. Use CSV, XLSX, PDF, JSON or transaction SMS text.");
    }
    String guess = detectProvider(new String(data, 0, Math.min(data.length, 65536), StandardCharsets.UTF_8), name);
    if (!guess.isEmpty()) result.provider = guess;
    for (LedgerTransaction row : result.rows) {
      if (row.provider.isEmpty()) row.provider = result.provider;
      if (row.accountId.isEmpty()) row.accountId = accountId;
      if (row.accountMasked.isEmpty()) row.accountMasked = accountMasked;
    }
    return result;
  }

  public static Result parseSmsText(String text, String accountId, String accountMasked) {
    Result result = new Result();
    result.format = Format.SMS;
    Set<String> seen = new HashSet<>();
    for (String raw : text.split("\\r?\\n")) {
      String line = raw.trim();
      if (line.length() < 12) continue;
      LedgerTransaction row = parseSms(line, System.currentTimeMillis(), accountId, accountMasked);
      if (row == null) {
        if (line.length() > 25) result.skipped++;
        continue;
      }
      if (!seen.add(row.dedupeKey())) {
        result.duplicates++;
        continue;
      }
      result.rows.add(row);
    }
    if (result.rows.isEmpty())
      result.warnings.add("No transaction text found. OTP and promotional messages are ignored.");
    return result;
  }

  /** Parses one bank/payment SMS. Returns null for OTPs, promotions and non-transactions. */
  public static LedgerTransaction parseSms(String message, long now, String accountId, String masked) {
    if (message == null) return null;
    String lower = message.toLowerCase(Locale.US);
    if (lower.contains("otp") || lower.contains("one time password") || lower.contains("do not share"))
      return null;
    if (!lower.matches("(?s).*(debited|credited|debit|credit|paid|sent|received|withdrawn|deposited|spent|refund|reversed).*"))
      return null;
    Long amount = Money.firstAmount(message);
    if (amount == null || amount <= 0) return null;
    boolean credit = lower.matches("(?s).*(credited|credit|received|deposited|refund|reversed).*")
        && !lower.matches("(?s).*(debited|debit|spent|withdrawn|paid|sent).*");
    LedgerTransaction row = new LedgerTransaction();
    row.direction = credit ? LedgerTransaction.Direction.CREDIT : LedgerTransaction.Direction.DEBIT;
    row.amountPaise = amount;
    row.occurredAt = dateInText(message, now);
    row.origin = LedgerTransaction.Origin.SMS;
    row.execution = LedgerTransaction.Execution.PROVIDER_REPORTED_SUCCESS;
    row.reconciliation = LedgerTransaction.Reconciliation.UNVERIFIED;
    row.accountId = accountId == null ? "" : accountId;
    Matcher maskedMatcher = MASKED.matcher(message);
    row.accountMasked = maskedMatcher.find() ? "XX" + maskedMatcher.group(1)
        : (masked == null ? "" : masked);
    Matcher utr = UTR_TEXT.matcher(message);
    if (utr.find()) row.utr = utr.group(1);
    Matcher vpa = VPA_TEXT.matcher(message);
    if (vpa.find()) row.upiAddress = vpa.group(1);
    row.counterparty = counterparty(message);
    row.note = message.length() > 160 ? message.substring(0, 160) : message;
    String provider = detectProvider(message, "");
    row.provider = provider.isEmpty() ? "Bank SMS" : provider;
    row.category = guessCategory(message);
    row.transactionId = "";
    return row;
  }

  // ------------------------------------------------------------------ tables

  public static Result parseCsv(String text, String accountId, String accountMasked) {
    return parseTable(Csv.read(text), accountId, accountMasked, Format.CSV);
  }

  public static Result parseTable(List<List<String>> table, String accountId, String accountMasked,
      Format format) {
    Result result = new Result();
    result.format = format;
    result.table.addAll(table);
    int headerRow = findHeaderRow(table);
    result.headerRow = headerRow;
    if (headerRow < 0) {
      result.warnings.add("No date/amount columns were recognised. Map the columns manually.");
      result.mapping = autoMapping(new ArrayList<>());
      return result;
    }
    result.headers.addAll(table.get(headerRow));
    result.mapping = autoMapping(table.get(headerRow));
    applyRows(result, table, headerRow, accountId, accountMasked);
    return result;
  }

  /** Re-applies a manual column mapping to an already loaded table. */
  public static Result applyMapping(Result source, int[] mapping, String accountId, String accountMasked) {
    Result result = new Result();
    result.format = source.format;
    result.provider = source.provider;
    result.table.addAll(source.table);
    result.headers.addAll(source.headers);
    result.headerRow = source.headerRow;
    result.mapping = mapping == null ? source.mapping : mapping;
    applyRows(result, source.table, Math.max(source.headerRow, 0), accountId, accountMasked);
    return result;
  }

  private static void applyRows(Result result, List<List<String>> table, int headerRow,
      String accountId, String masked) {
    Set<String> seen = new HashSet<>();
    for (int index = headerRow + 1; index < table.size(); index++) {
      List<String> cells = table.get(index);
      try {
        LedgerTransaction row = row(cells, result.mapping, result.format, accountId, masked);
        if (row == null) {
          result.skipped++;
          continue;
        }
        if (!seen.add(row.dedupeKey())) {
          result.duplicates++;
          continue;
        }
        result.rows.add(row);
      } catch (RuntimeException error) {
        result.skipped++;
      }
    }
    long duplicatesRemoved = result.duplicates;
    if (duplicatesRemoved > 0)
      result.warnings.add(duplicatesRemoved + " repeated row(s) inside this file were skipped.");
    if (result.rows.isEmpty())
      result.warnings.add("No dated transaction rows were found in this file.");
  }

  private static LedgerTransaction row(List<String> cells, int[] mapping, Format format,
      String accountId, String masked) {
    String dateText = cell(cells, mapping[DATE]);
    String description = cell(cells, mapping[DESCRIPTION]);
    String debitText = cell(cells, mapping[DEBIT]);
    String creditText = cell(cells, mapping[CREDIT]);
    String amountText = cell(cells, mapping[AMOUNT]);
    String typeText = cell(cells, mapping[TYPE]);
    String reference = cell(cells, mapping[REFERENCE]);
    String upi = cell(cells, mapping[UPI]);
    String category = cell(cells, mapping[CATEGORY]);
    if (dateText.isEmpty() && description.isEmpty()) return null;

    Long debit = Money.parse(stripCurrency(debitText));
    Long credit = Money.parse(stripCurrency(creditText));
    Long amount = Money.parse(stripCurrency(amountText));
    if (amount == null) amount = debit != null ? debit : credit;
    if (amount == null || amount == 0) return null;

    LedgerTransaction transaction = new LedgerTransaction();
    transaction.amountPaise = Math.abs(amount);
    boolean creditDirection;
    if (debit != null && debit != 0) creditDirection = false;
    else if (credit != null && credit != 0) creditDirection = true;
    else if (amount < 0) creditDirection = true;
    else creditDirection = matchesCredit(typeText);
    transaction.direction = creditDirection
        ? LedgerTransaction.Direction.CREDIT : LedgerTransaction.Direction.DEBIT;
    transaction.occurredAt = parseDate(dateText, System.currentTimeMillis());
    transaction.counterparty = description;
    transaction.note = description;
    transaction.category = category.isEmpty() ? guessCategory(description) : category;
    transaction.transactionId = reference;
    if (reference.matches("(?i).*(utr|rrn).*")) transaction.utr = reference;
    else if (reference.matches("[0-9]{10,22}")) transaction.utr = reference;
    Matcher vpa = VPA_TEXT.matcher(upi.isEmpty() ? description : upi);
    if (vpa.find()) transaction.upiAddress = vpa.group(1);
    transaction.accountId = accountId == null ? "" : accountId;
    transaction.accountMasked = masked == null ? "" : masked;
    transaction.execution = LedgerTransaction.Execution.PROVIDER_REPORTED_SUCCESS;
    transaction.reconciliation = LedgerTransaction.Reconciliation.STATEMENT_MATCHED;
    transaction.origin = format == Format.XLSX ? LedgerTransaction.Origin.STATEMENT_XLSX
        : LedgerTransaction.Origin.STATEMENT_CSV;
    return transaction;
  }

  public static int[] autoMapping(List<String> header) {
    int[] mapping = new int[ROLE_COUNT];
    Arrays.fill(mapping, -1);
    for (int index = 0; index < header.size(); index++) {
      String key = normalize(header.get(index));
      if (mapping[DATE] < 0 && DATE_KEYS.contains(key)) mapping[DATE] = index;
      else if (mapping[DEBIT] < 0 && DEBIT_KEYS.contains(key)) mapping[DEBIT] = index;
      else if (mapping[CREDIT] < 0 && CREDIT_KEYS.contains(key)) mapping[CREDIT] = index;
      else if (mapping[AMOUNT] < 0 && AMOUNT_KEYS.contains(key)) mapping[AMOUNT] = index;
      else if (mapping[TYPE] < 0 && TYPE_KEYS.contains(key)) mapping[TYPE] = index;
      else if (mapping[REFERENCE] < 0 && REFERENCE_KEYS.contains(key)) mapping[REFERENCE] = index;
      else if (mapping[UPI] < 0 && UPI_KEYS.contains(key)) mapping[UPI] = index;
      else if (mapping[CATEGORY] < 0 && CATEGORY_KEYS.contains(key)) mapping[CATEGORY] = index;
      else if (mapping[DESCRIPTION] < 0 && DESCRIPTION_KEYS.contains(key)) mapping[DESCRIPTION] = index;
    }
    return mapping;
  }

  private static int findHeaderRow(List<List<String>> table) {
    for (int index = 0; index < Math.min(table.size(), 25); index++) {
      List<String> row = table.get(index);
      int[] mapping = autoMapping(row);
      boolean hasDate = mapping[DATE] >= 0;
      boolean hasMoney = mapping[AMOUNT] >= 0 || mapping[DEBIT] >= 0 || mapping[CREDIT] >= 0;
      if (hasDate && hasMoney) return index;
    }
    return -1;
  }

  // ------------------------------------------------------------------ text / json

  public static Result parseText(String text, String accountId, String masked, Format format) {
    Result result = new Result();
    result.format = format;
    if (text == null) return result;
    Set<String> seen = new HashSet<>();
    Pattern tail = Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(Cr|Dr|CR|DR)?\\s*$");
    Pattern leadingDate = Pattern.compile(
        "^\\s*(\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}|\\d{1,2}\\s+[A-Za-z]{3}\\s+\\d{2,4}|\\d{4}-\\d{2}-\\d{2})\\b");
    for (String raw : text.split("\\r?\\n")) {
      String line = raw.trim();
      if (line.length() < 10) continue;
      Matcher date = leadingDate.matcher(line);
      Matcher amount = tail.matcher(line);
      if (!date.find() || !amount.find()) continue;
      Long paise = Money.parse(amount.group(1));
      if (paise == null || paise <= 0) continue;
      String middle = line.substring(date.end(), amount.start()).trim();
      String tailMarker = amount.group(2) == null ? "" : amount.group(2);
      boolean credit = tailMarker.equalsIgnoreCase("cr") || matchesCredit(middle);
      if (tailMarker.equalsIgnoreCase("dr")) credit = false;
      LedgerTransaction row = new LedgerTransaction();
      row.amountPaise = paise;
      row.direction = credit ? LedgerTransaction.Direction.CREDIT : LedgerTransaction.Direction.DEBIT;
      row.occurredAt = parseDate(date.group(1), System.currentTimeMillis());
      row.counterparty = middle;
      row.note = middle;
      row.category = guessCategory(middle);
      row.accountId = accountId == null ? "" : accountId;
      row.accountMasked = masked == null ? "" : masked;
      row.execution = LedgerTransaction.Execution.PROVIDER_REPORTED_SUCCESS;
      row.reconciliation = LedgerTransaction.Reconciliation.STATEMENT_MATCHED;
      row.origin = format == Format.PDF
          ? LedgerTransaction.Origin.STATEMENT_PDF : LedgerTransaction.Origin.RECEIPT;
      Matcher utr = UTR_TEXT.matcher(middle);
      if (utr.find()) row.utr = utr.group(1);
      Matcher vpa = VPA_TEXT.matcher(middle);
      if (vpa.find()) row.upiAddress = vpa.group(1);
      if (!seen.add(row.dedupeKey())) {
        result.duplicates++;
        continue;
      }
      result.rows.add(row);
    }
    if (result.rows.isEmpty())
      result.warnings.add("Statement lines could not be matched. Export CSV instead if available.");
    return result;
  }

  public static Result parseJson(String text, String accountId, String masked) {
    Result result = new Result();
    result.format = Format.JSON;
    Object parsed;
    try {
      parsed = Json.parse(text);
    } catch (RuntimeException error) {
      result.warnings.add("JSON could not be read: " + error.getMessage());
      return result;
    }
    List<Object> items;
    if (parsed instanceof List) items = Json.array(parsed);
    else {
      Map<String, Object> object = Json.object(parsed);
      Object transactions = object.get("transactions");
      if (transactions == null) transactions = object.get("rows");
      if (transactions == null) transactions = object.get("data");
      if (transactions instanceof List) items = Json.array(transactions);
      else {
        result.warnings.add("JSON must be an array or contain a transactions array.");
        return result;
      }
    }
    Set<String> seen = new HashSet<>();
    for (Object item : items) {
      if (!(item instanceof Map)) {
        result.skipped++;
        continue;
      }
      Map<String, Object> map = Json.object(item);
      Long paise = null;
      Object amount = first(map, "amountPaise", "amount_paise", "amount", "txnAmount");
      if (amount instanceof Number) {
        paise = ((Number) amount).longValue();
        if (map.get("amountPaise") == null && map.get("amount_paise") == null
            && !(map.get("amount") instanceof String)) {
          paise = Math.round(((Number) amount).doubleValue() * 100);
        }
      } else if (amount != null) {
        paise = Money.parse(String.valueOf(amount));
      }
      if (paise == null || paise <= 0) {
        result.skipped++;
        continue;
      }
      LedgerTransaction row = new LedgerTransaction();
      row.amountPaise = Math.abs(paise);
      Object direction = first(map, "direction", "type");
      row.direction = direction != null && direction.toString().toUpperCase(Locale.US).contains("CR")
          ? LedgerTransaction.Direction.CREDIT : LedgerTransaction.Direction.DEBIT;
      row.occurredAt = parseDate(String.valueOf(first(map, "occurredAt", "date", "timestamp")),
          System.currentTimeMillis());
      row.counterparty = string(first(map, "counterparty", "payee", "name", "description"));
      row.note = string(first(map, "note", "description"));
      row.category = string(first(map, "category"));
      row.upiAddress = string(first(map, "upi", "upiAddress", "vpa"));
      row.utr = string(first(map, "utr", "rrn"));
      row.transactionId = string(first(map, "transactionId", "txnId", "reference"));
      row.provider = string(first(map, "provider"));
      row.accountMasked = masked == null ? "" : masked;
      row.accountId = accountId == null ? "" : accountId;
      row.execution = LedgerTransaction.Execution.PROVIDER_REPORTED_SUCCESS;
      row.reconciliation = LedgerTransaction.Reconciliation.STATEMENT_MATCHED;
      row.origin = LedgerTransaction.Origin.STATEMENT_JSON;
      if (!seen.add(row.dedupeKey())) {
        result.duplicates++;
        continue;
      }
      result.rows.add(row);
    }
    return result;
  }

  // ------------------------------------------------------------------ helpers

  public static long parseDate(String value, long fallback) {
    if (value == null) return fallback;
    String text = value.trim();
    if (text.isEmpty()) return fallback;
    if (text.matches("\\d{4,6}(\\.0+)?")) {
      try {
        double serial = Double.parseDouble(text);
        if (serial > 20000 && serial < 80000) return Xlsx.serialDate(serial);
      } catch (NumberFormatException ignored) {
        // Not a serial date.
      }
    }
    if (text.matches("\\d{10,13}")) {
      long raw = Long.parseLong(text);
      return raw < 100_000_000_000L ? raw * 1000L : raw;
    }
    String cleaned = text.replace('T', ' ').replaceAll("\\s+", " ").trim();
    for (String pattern : DATE_PATTERNS) {
      SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
      format.setLenient(false);
      ParsePosition position = new ParsePosition(0);
      java.util.Date parsed = format.parse(cleaned, position);
      if (parsed != null && position.getIndex() >= Math.min(cleaned.length(), 8)) return parsed.getTime();
    }
    return fallback;
  }

  public static String guessCategory(String text) {
    String value = text == null ? "" : text.toLowerCase(Locale.US);
    if (value.matches("(?s).*(salary|payroll|credit interest|interest credit).*")) return "Income";
    if (value.matches("(?s).*(refund|reversal|reversed|chargeback).*")) return "Refund";
    if (value.matches("(?s).*(self transfer|own account|to self).*")) return "Transfer";
    if (value.matches("(?s).*(swiggy|zomato|restaurant|food|cafe|dominos).*")) return "Food";
    if (value.matches("(?s).*(uber|ola|rapido|fuel|petrol|diesel|irctc|metro|fastag|toll).*")) return "Travel";
    if (value.matches("(?s).*(amazon|flipkart|myntra|meesho|store|mart|shop|kirana).*")) return "Shopping";
    if (value.matches("(?s).*(electric|water bill|gas|broadband|recharge|jio|airtel|vi |bsnl|bill pay).*")) return "Bills";
    if (value.matches("(?s).*(rent|landlord).*")) return "Rent";
    if (value.matches("(?s).*(hospital|clinic|pharmacy|medical|apollo|practo).*")) return "Health";
    if (value.matches("(?s).*(school|college|tuition|fee|course).*")) return "Education";
    if (value.matches("(?s).*(mutual fund|sip|stock|zerodha|groww|insurance|lic ).*")) return "Investment";
    return "Other";
  }

  private static String counterparty(String message) {
    Matcher matcher = Pattern.compile("(?i)\\b(?:to|from|at|towards)\\s+([A-Za-z0-9@._ -]{2,40})")
        .matcher(message);
    if (matcher.find()) return matcher.group(1).trim();
    return "";
  }

  private static boolean matchesCredit(String text) {
    if (text == null) return false;
    String value = text.toLowerCase(Locale.US);
    if (value.matches("(?s).*\\b(cr|credit|received|deposit|refund|inward)\\b.*")) return true;
    if (value.matches("(?s).*\\b(dr|debit|paid|sent|withdrawal|outward)\\b.*")) return false;
    return false;
  }

  private static long dateInText(String message, long fallback) {
    Matcher matcher = Pattern.compile(
        "(\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}|\\d{1,2}[- ][A-Za-z]{3}[- ]?\\d{2,4})").matcher(message);
    if (matcher.find()) return parseDate(matcher.group(1), fallback);
    return fallback;
  }

  private static String stripCurrency(String value) {
    return value == null ? "" : value.replace("\u20b9", "").replace("INR", "").replace("Rs.", "");
  }

  private static String cell(List<String> row, int index) {
    if (index < 0 || index >= row.size()) return "";
    String value = row.get(index);
    return value == null ? "" : value.trim();
  }

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static Object first(Map<String, Object> map, String... keys) {
    for (String key : keys) if (map.containsKey(key) && map.get(key) != null) return map.get(key);
    return null;
  }

  static String normalize(String header) {
    return header == null ? "" : header.toLowerCase(Locale.US)
        .replaceAll("[\u20b9]", "").replaceAll("[^a-z0-9_]", "");
  }

  private static Set<String> words(String... values) {
    return new HashSet<>(Arrays.asList(values));
  }

  /** Convenience for the mapping UI: a stable, human-readable role list. */
  public static Map<String, Integer> roles() {
    Map<String, Integer> roles = new LinkedHashMap<>();
    for (int index = 0; index < ROLE_NAMES.length; index++) roles.put(ROLE_NAMES[index], index);
    return roles;
  }
}
