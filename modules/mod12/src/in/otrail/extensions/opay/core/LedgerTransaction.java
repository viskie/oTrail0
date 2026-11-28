package in.otrail.extensions.opay.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One ledger record. Amounts are always integer paise. */
public final class LedgerTransaction {
  public enum Direction { CREDIT, DEBIT }

  /** What happened when the payment attempt was launched. Never proof of settlement. */
  public enum Execution {
    CREATED, INITIATED, PROVIDER_REPORTED_SUCCESS, PROVIDER_REPORTED_FAILURE,
    PROVIDER_REPORTED_PENDING, CANCELLED, UNKNOWN
  }

  /** Independent reconciliation state, set by statements, SMS or the user. */
  public enum Reconciliation {
    UNVERIFIED, STATEMENT_MATCHED, USER_CONFIRMED, CONFLICT, REVERSED, REFUNDED
  }

  public enum Origin {
    UPI, MANUAL, STATEMENT_CSV, STATEMENT_XLSX, STATEMENT_PDF, STATEMENT_JSON, SMS, RECEIPT
  }

  public String id = UUID.randomUUID().toString();
  public long createdAt = System.currentTimeMillis();
  public long occurredAt = System.currentTimeMillis();
  public Direction direction = Direction.DEBIT;
  public long amountPaise;
  public String currency = Money.INR;
  public String provider = "";
  public String accountId = "";
  public String accountMasked = "";
  public String counterparty = "";
  public String upiAddress = "";
  public String transactionId = "";
  public String utr = "";
  public String note = "";
  public String category = "";
  public Execution execution = Execution.CREATED;
  public Reconciliation reconciliation = Reconciliation.UNVERIFIED;
  public Origin origin = Origin.MANUAL;
  public String importBatch = "";
  public String relatedId = "";
  public String annotations = "";
  public boolean internalTransfer;
  public Double latitude;
  public Double longitude;
  public Float locationAccuracy;
  public long locationAt;
  public String locationProvider = "";

  public LedgerTransaction() {}

  public LedgerTransaction copy() {
    LedgerTransaction copy = new LedgerTransaction();
    copy.id = id;
    copy.createdAt = createdAt;
    copy.occurredAt = occurredAt;
    copy.direction = direction;
    copy.amountPaise = amountPaise;
    copy.currency = currency;
    copy.provider = provider;
    copy.accountId = accountId;
    copy.accountMasked = accountMasked;
    copy.counterparty = counterparty;
    copy.upiAddress = upiAddress;
    copy.transactionId = transactionId;
    copy.utr = utr;
    copy.note = note;
    copy.category = category;
    copy.execution = execution;
    copy.reconciliation = reconciliation;
    copy.origin = origin;
    copy.importBatch = importBatch;
    copy.relatedId = relatedId;
    copy.annotations = annotations;
    copy.internalTransfer = internalTransfer;
    copy.latitude = latitude;
    copy.longitude = longitude;
    copy.locationAccuracy = locationAccuracy;
    copy.locationAt = locationAt;
    copy.locationProvider = locationProvider;
    return copy;
  }

  public long signedPaise() {
    return direction == Direction.CREDIT ? amountPaise : -amountPaise;
  }

  /** Failed, cancelled and merely created attempts never move money. */
  public boolean realized() {
    return execution != Execution.PROVIDER_REPORTED_FAILURE
        && execution != Execution.CANCELLED
        && execution != Execution.CREATED
        && amountPaise > 0;
  }

  public boolean isUnverified() {
    return reconciliation == Reconciliation.UNVERIFIED;
  }

  public boolean hasLocation() {
    return latitude != null && longitude != null;
  }

  /** Compact map-safe fix snapshot, for example "12.972100, 77.594600 · ±8 m · GPS". */
  public String locationText() {
    if (!hasLocation()) return "";
    String text = String.format(java.util.Locale.US, "%.6f, %.6f", latitude, longitude);
    if (locationAccuracy != null) text += " \u00b7 \u00b1" + Math.round(locationAccuracy) + " m";
    if (!locationProvider.isEmpty()) text += " \u00b7 " + locationProvider.toUpperCase(java.util.Locale.US);
    if (locationAt > 0) text += " \u00b7 " + new java.text.SimpleDateFormat("dd MMM HH:mm",
        java.util.Locale.US).format(new java.util.Date(locationAt));
    return text;
  }

  public String dedupeKey() {
    if (!utr.isEmpty()) return "utr:" + utr.trim().toUpperCase();
    if (!transactionId.isEmpty())
      return "ref:" + provider.toLowerCase() + ":" + transactionId.trim().toUpperCase();
    return "soft:" + accountId + "|" + occurredAt / 60_000L + "|" + amountPaise + "|"
        + direction + "|" + counterparty.toLowerCase();
  }

  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", id);
    map.put("createdAt", createdAt);
    map.put("occurredAt", occurredAt);
    map.put("direction", direction.name());
    map.put("amountPaise", amountPaise);
    map.put("currency", currency);
    map.put("provider", provider);
    map.put("accountId", accountId);
    map.put("accountMasked", accountMasked);
    map.put("counterparty", counterparty);
    map.put("upi", upiAddress);
    map.put("transactionId", transactionId);
    map.put("utr", utr);
    map.put("note", note);
    map.put("category", category);
    map.put("execution", execution.name());
    map.put("reconciliation", reconciliation.name());
    map.put("origin", origin.name());
    map.put("importBatch", importBatch);
    map.put("relatedId", relatedId);
    map.put("annotations", annotations);
    map.put("internalTransfer", internalTransfer);
    if (latitude != null) map.put("latitude", latitude);
    if (longitude != null) map.put("longitude", longitude);
    if (locationAccuracy != null) map.put("locationAccuracy", locationAccuracy);
    if (locationAt > 0) map.put("locationAt", locationAt);
    if (!locationProvider.isEmpty()) map.put("locationProvider", locationProvider);
    return map;
  }

  public static LedgerTransaction fromMap(Map<String, Object> map) {
    LedgerTransaction transaction = new LedgerTransaction();
    transaction.id = text(map, "id", transaction.id);
    transaction.createdAt = number(map, "createdAt", transaction.createdAt);
    transaction.occurredAt = number(map, "occurredAt", transaction.createdAt);
    transaction.direction = direction(text(map, "direction", "DEBIT"));
    transaction.amountPaise = number(map, "amountPaise", 0);
    transaction.currency = text(map, "currency", Money.INR);
    transaction.provider = text(map, "provider", "");
    transaction.accountId = text(map, "accountId", "");
    transaction.accountMasked = text(map, "accountMasked", "");
    transaction.counterparty = text(map, "counterparty", "");
    transaction.upiAddress = text(map, "upi", text(map, "upiAddress", ""));
    transaction.transactionId = text(map, "transactionId", "");
    transaction.utr = text(map, "utr", "");
    transaction.note = text(map, "note", "");
    transaction.category = text(map, "category", "");
    transaction.execution = execution(text(map, "execution", "CREATED"));
    transaction.reconciliation = reconciliation(text(map, "reconciliation", "UNVERIFIED"));
    transaction.origin = origin(text(map, "origin", "MANUAL"));
    transaction.importBatch = text(map, "importBatch", "");
    transaction.relatedId = text(map, "relatedId", "");
    transaction.annotations = text(map, "annotations", "");
    transaction.internalTransfer = Boolean.TRUE.equals(map.get("internalTransfer"));
    transaction.latitude = optionalDouble(map.get("latitude"));
    transaction.longitude = optionalDouble(map.get("longitude"));
    transaction.locationAccuracy = optionalFloat(map.get("locationAccuracy"));
    transaction.locationAt = number(map, "locationAt", 0);
    transaction.locationProvider = text(map, "locationProvider", "");
    return transaction;
  }

  public static Direction direction(String value) {
    try { return Direction.valueOf(value == null ? "DEBIT" : value.trim().toUpperCase()); }
    catch (IllegalArgumentException ignored) { return Direction.DEBIT; }
  }

  public static Execution execution(String value) {
    try { return Execution.valueOf(value == null ? "UNKNOWN" : value.trim().toUpperCase()); }
    catch (IllegalArgumentException ignored) { return Execution.UNKNOWN; }
  }

  public static Reconciliation reconciliation(String value) {
    try { return Reconciliation.valueOf(value == null ? "UNVERIFIED" : value.trim().toUpperCase()); }
    catch (IllegalArgumentException ignored) { return Reconciliation.UNVERIFIED; }
  }

  public static Origin origin(String value) {
    try { return Origin.valueOf(value == null ? "MANUAL" : value.trim().toUpperCase()); }
    catch (IllegalArgumentException ignored) { return Origin.MANUAL; }
  }

  private static String text(Map<String, Object> map, String key, String fallback) {
    Object value = map.get(key);
    return value == null ? fallback : String.valueOf(value);
  }

  private static long number(Map<String, Object> map, String key, long fallback) {
    Object value = map.get(key);
    if (value instanceof Number) return ((Number) value).longValue();
    if (value instanceof String) {
      try { return Long.parseLong((String) value); } catch (NumberFormatException ignored) { return fallback; }
    }
    return fallback;
  }

  private static Float optionalFloat(Object value) {
    if (value instanceof Number) return ((Number) value).floatValue();
    if (value instanceof String) {
      try { return Float.parseFloat((String) value); } catch (NumberFormatException ignored) { return null; }
    }
    return null;
  }

  private static Double optionalDouble(Object value) {
    if (value instanceof Number) return ((Number) value).doubleValue();
    if (value instanceof String) {
      try { return Double.parseDouble((String) value); } catch (NumberFormatException ignored) { return null; }
    }
    return null;
  }
}
