package in.otrail.extensions.opay;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import in.otrail.extensions.opay.core.LedgerTransaction;
import in.otrail.extensions.opay.core.Money;
import in.otrail.extensions.opay.core.Reports;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Local-only ledger. All amounts are 64-bit paise, all queries are parameterized and
 * every method is safe to call from the module's single background executor.
 */
public final class OpayStore {
  private static final int SCHEMA = 1;
  private static final String COLUMNS =
      "id,created_at,occurred_at,direction,amount_paise,currency,provider,account_id,"
      + "account_masked,counterparty,upi,transaction_id,utr,note,category,execution,reconciliation,"
      + "origin,import_batch,related_id,annotations,internal_transfer,latitude,longitude,"
      + "location_accuracy,location_at,location_provider,strong_key";

  private final SQLiteDatabase db;

  public static final class Account {
    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String bank = "";
    public String masked = "";
    public String upi = "";
    public long createdAt = System.currentTimeMillis();

    public String label() {
      String base = name.isEmpty() ? bank : name;
      if (base.isEmpty()) base = "Account";
      if (masked.isEmpty()) return base;
      return base + " \u00b7 " + masked;
    }
  }

  public static final class Beneficiary {
    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String upi = "";
    public String note = "";
    public long lastUsed;
    public int uses;

    public String label() {
      return name.isEmpty() ? upi : name + " \u00b7 " + upi;
    }
  }

  public OpayStore(File directory) {
    if (!directory.exists() && !directory.mkdirs())
      throw new IllegalStateException("Cannot create the oPay storage directory");
    File file = new File(directory, "opay.db");
    db = SQLiteDatabase.openOrCreateDatabase(file, null);
    // PRAGMA statements that return a row (journal_mode echoes the new mode) must run through
    // rawQuery; execSQL rejects them with "Queries can be performed using query or rawQuery".
    pragma("PRAGMA journal_mode=WAL");
    pragma("PRAGMA synchronous=NORMAL");
    migrate();
  }

  private void pragma(String sql) {
    try (Cursor cursor = db.rawQuery(sql, null)) {
      if (cursor != null) cursor.moveToFirst();
    } catch (RuntimeException ignored) {
      // A device without WAL still works with the default rollback journal.
    }
  }

  public void close() {
    try { db.close(); } catch (RuntimeException ignored) { }
  }

  // ------------------------------------------------------------------ schema

  private void migrate() {
    if (db.getVersion() >= SCHEMA) return;
    db.beginTransaction();
    try {
      db.execSQL("CREATE TABLE IF NOT EXISTS txn("
          + "id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, occurred_at INTEGER NOT NULL,"
          + " direction TEXT NOT NULL, amount_paise INTEGER NOT NULL, currency TEXT NOT NULL,"
          + " provider TEXT, account_id TEXT, account_masked TEXT, counterparty TEXT, upi TEXT,"
          + " transaction_id TEXT, utr TEXT, note TEXT, category TEXT, execution TEXT NOT NULL,"
          + " reconciliation TEXT NOT NULL, origin TEXT NOT NULL, import_batch TEXT,"
          + " related_id TEXT, annotations TEXT, internal_transfer INTEGER NOT NULL DEFAULT 0,"
          + " latitude REAL, longitude REAL, location_accuracy REAL, location_at INTEGER,"
          + " location_provider TEXT, strong_key TEXT)");
      db.execSQL("CREATE INDEX IF NOT EXISTS txn_time ON txn(occurred_at DESC)");
      db.execSQL("CREATE INDEX IF NOT EXISTS txn_amount ON txn(amount_paise)");
      db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS txn_strong ON txn(strong_key)"
          + " WHERE strong_key IS NOT NULL");
      db.execSQL("CREATE INDEX IF NOT EXISTS txn_utr ON txn(utr)");
      db.execSQL("CREATE INDEX IF NOT EXISTS txn_upi ON txn(upi)");
      db.execSQL("CREATE INDEX IF NOT EXISTS txn_recon ON txn(reconciliation)");
      db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS txn_search USING fts4("
          + "counterparty, upi, transaction_id, note, category, tokenize=unicode61)");
      db.execSQL("CREATE TRIGGER IF NOT EXISTS txn_search_insert AFTER INSERT ON txn BEGIN"
          + " INSERT INTO txn_search(docid,counterparty,upi,transaction_id,note,category)"
          + " VALUES(new.rowid,new.counterparty,new.upi,new.transaction_id,new.note,new.category); END");
      db.execSQL("CREATE TRIGGER IF NOT EXISTS txn_search_update AFTER UPDATE ON txn BEGIN"
          + " UPDATE txn_search SET counterparty=new.counterparty,upi=new.upi,"
          + " transaction_id=new.transaction_id,note=new.note,category=new.category"
          + " WHERE docid=new.rowid; END");
      db.execSQL("CREATE TRIGGER IF NOT EXISTS txn_search_delete AFTER DELETE ON txn BEGIN"
          + " DELETE FROM txn_search WHERE docid=old.rowid; END");
      db.execSQL("CREATE TABLE IF NOT EXISTS accounts(id TEXT PRIMARY KEY, name TEXT, bank TEXT,"
          + " masked TEXT, upi TEXT, created_at INTEGER NOT NULL)");
      db.execSQL("CREATE TABLE IF NOT EXISTS beneficiaries(id TEXT PRIMARY KEY, name TEXT,"
          + " upi TEXT, note TEXT, last_used INTEGER NOT NULL DEFAULT 0, uses INTEGER NOT NULL DEFAULT 0)");
      db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS beneficiary_upi ON beneficiaries(upi)");
      db.execSQL("CREATE TABLE IF NOT EXISTS batches(id TEXT PRIMARY KEY, started_at INTEGER NOT NULL,"
          + " source TEXT, file_name TEXT, imported INTEGER NOT NULL DEFAULT 0,"
          + " duplicates INTEGER NOT NULL DEFAULT 0)");
      db.setVersion(SCHEMA);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  // ------------------------------------------------------------------ transactions

  public void insert(LedgerTransaction transaction) {
    db.insertWithOnConflict("txn", null, values(transaction), SQLiteDatabase.CONFLICT_IGNORE);
  }

  /** Returns how many rows were new; repeated statements are idempotent. */
  public int insertBatch(List<LedgerTransaction> transactions, String batchId) {
    int inserted = 0;
    db.beginTransaction();
    try {
      for (LedgerTransaction transaction : transactions) {
        if (transaction.importBatch.isEmpty()) transaction.importBatch = batchId;
        long row = db.insertWithOnConflict("txn", null, values(transaction),
            SQLiteDatabase.CONFLICT_IGNORE);
        if (row >= 0) inserted++;
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    return inserted;
  }

  public void update(LedgerTransaction transaction) {
    db.update("txn", values(transaction), "id=?", new String[]{transaction.id});
  }

  public void delete(String id) {
    db.delete("txn", "id=?", new String[]{id});
  }

  public LedgerTransaction byId(String id) {
    try (Cursor cursor = db.query("txn", null, "id=?", new String[]{id}, null, null, null)) {
      return cursor.moveToFirst() ? read(cursor) : null;
    }
  }

  public LedgerTransaction byStrongKey(String strongKey) {
    if (strongKey == null || strongKey.isEmpty()) return null;
    try (Cursor cursor = db.query("txn", null, "strong_key=?", new String[]{strongKey},
        null, null, null, "1")) {
      return cursor.moveToFirst() ? read(cursor) : null;
    }
  }

  public void deleteAll() {
    db.beginTransaction();
    try {
      db.delete("txn", null, null);
      db.delete("accounts", null, null);
      db.delete("beneficiaries", null, null);
      db.delete("batches", null, null);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public boolean existsStrong(String strongKey) {
    if (strongKey == null || strongKey.isEmpty()) return false;
    try (Cursor cursor = db.rawQuery("SELECT 1 FROM txn WHERE strong_key=? LIMIT 1",
        new String[]{strongKey})) {
      return cursor.moveToFirst();
    }
  }

  public boolean existsSoft(LedgerTransaction transaction) {
    long from = transaction.occurredAt - 120_000L;
    long to = transaction.occurredAt + 120_000L;
    try (Cursor cursor = db.rawQuery("SELECT 1 FROM txn WHERE amount_paise=? AND direction=?"
            + " AND occurred_at BETWEEN ? AND ? AND counterparty=? COLLATE NOCASE LIMIT 1",
        new String[]{String.valueOf(transaction.amountPaise), transaction.direction.name(),
            String.valueOf(from), String.valueOf(to), transaction.counterparty})) {
      return cursor.moveToFirst();
    }
  }

  public List<LedgerTransaction> recent(int limit) {
    return query(new Filters().limit(Math.max(1, limit)));
  }

  public List<LedgerTransaction> unreconciled(int limit) {
    Filters filters = new Filters();
    filters.unreconciled = true;
    filters.limit(limit);
    return query(filters);
  }

  public List<LedgerTransaction> range(long from, long to) {
    Filters filters = new Filters();
    filters.from = from;
    filters.to = to;
    filters.limit(100000);
    return query(filters);
  }

  public List<LedgerTransaction> matchingCandidates(LedgerTransaction transaction) {
    long from = transaction.occurredAt - 5L * 86_400_000L;
    long to = transaction.occurredAt + 5L * 86_400_000L;
    List<LedgerTransaction> candidates = new ArrayList<>();
    String where = "occurred_at BETWEEN ? AND ? AND amount_paise=? AND reconciliation<>'REVERSED'";
    try (Cursor cursor = db.query("txn", null, where,
        new String[]{String.valueOf(from), String.valueOf(to),
            String.valueOf(transaction.amountPaise)}, null, null, "occurred_at DESC", "400")) {
      while (cursor.moveToNext()) {
        LedgerTransaction candidate = read(cursor);
        if (!candidate.id.equals(transaction.id)) candidates.add(candidate);
      }
    }
    return candidates;
  }

  public static final class Filters {
    public long from = Long.MIN_VALUE / 4;
    public long to = Long.MAX_VALUE / 4;
    public String query = "";
    public long minPaise = -1;
    public long maxPaise = -1;
    public String direction = "";
    public String reconciliation = "";
    public String account = "";
    public String provider = "";
    public String category = "";
    public boolean unreconciled;
    public String sort = "date_desc";
    public int limit = 100;
    public int offset;

    public Filters limit(int value) { limit = Math.max(1, Math.min(value, 100000)); return this; }
  }

  public List<LedgerTransaction> query(Filters filters) {
    List<String> arguments = new ArrayList<>();
    StringBuilder where = new StringBuilder("1=1");
    where.append(" AND occurred_at>=? AND occurred_at<?");
    arguments.add(String.valueOf(filters.from));
    arguments.add(String.valueOf(filters.to));
    if (filters.query != null && !filters.query.trim().isEmpty()) {
      StringBuilder match = new StringBuilder();
      for (String word : filters.query.trim().split("\\s+")) {
        if (word.isEmpty()) continue;
        if (match.length() > 0) match.append(' ');
        match.append('"').append(word.replace("\"", "\"\"")).append("\"*");
      }
      where.append(" AND id IN (SELECT t.id FROM txn_search s JOIN txn t ON t.rowid=s.docid"
          + " WHERE txn_search MATCH ?)");
      arguments.add(match.toString());
    }
    if (filters.minPaise >= 0) {
      where.append(" AND amount_paise>=?");
      arguments.add(String.valueOf(filters.minPaise));
    }
    if (filters.maxPaise >= 0) {
      where.append(" AND amount_paise<=?");
      arguments.add(String.valueOf(filters.maxPaise));
    }
    if (!filters.direction.isEmpty()) {
      where.append(" AND direction=?");
      arguments.add(filters.direction);
    }
    if (!filters.reconciliation.isEmpty()) {
      where.append(" AND reconciliation=?");
      arguments.add(filters.reconciliation);
    }
    if (filters.unreconciled) where.append(" AND reconciliation IN ('UNVERIFIED','CONFLICT')");
    if (!filters.account.isEmpty()) {
      where.append(" AND account_id=?");
      arguments.add(filters.account);
    }
    if (!filters.provider.isEmpty()) {
      where.append(" AND provider=?");
      arguments.add(filters.provider);
    }
    if (!filters.category.isEmpty()) {
      where.append(" AND category=?");
      arguments.add(filters.category);
    }
    String order;
    if (filters.sort.equals("date_asc")) order = "occurred_at ASC";
    else if (filters.sort.equals("amount_desc")) order = "amount_paise DESC, occurred_at DESC";
    else if (filters.sort.equals("amount_asc")) order = "amount_paise ASC, occurred_at DESC";
    else order = "occurred_at DESC";
    List<LedgerTransaction> rows = new ArrayList<>();
    try (Cursor cursor = db.query("txn", null, where.toString(), arguments.toArray(new String[0]),
        null, null, order, filters.offset + "," + Math.max(1, filters.limit))) {
      while (cursor.moveToNext()) rows.add(read(cursor));
    }
    return rows;
  }

  public int count() {
    try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM txn", null)) {
      return cursor.moveToFirst() ? cursor.getInt(0) : 0;
    }
  }

  // ------------------------------------------------------------------ reporting

  public Reports.Summary summary(Reports.Range range, boolean includeUnverified) {
    Reports.Summary summary = new Reports.Summary();
    summary.range = range;
    String base = "occurred_at>=? AND occurred_at<? AND execution NOT IN"
        + " ('CREATED','CANCELLED','PROVIDER_REPORTED_FAILURE')"
        + " AND internal_transfer=0"
        + (includeUnverified ? "" : " AND reconciliation<>'UNVERIFIED'");
    String[] arguments = {String.valueOf(range.from), String.valueOf(range.toExclusive)};
    try (Cursor cursor = db.rawQuery("SELECT direction,COUNT(*),SUM(amount_paise),MAX(amount_paise)"
        + " FROM txn WHERE " + base + " AND reconciliation NOT IN ('REVERSED','REFUNDED')"
        + " GROUP BY direction", arguments)) {
      while (cursor.moveToNext()) {
        long total = cursor.getLong(2);
        int count = cursor.getInt(1);
        summary.count += count;
        if ("CREDIT".equals(cursor.getString(0))) {
          summary.credits = total;
          summary.largestCredit = cursor.getLong(3);
        } else {
          summary.debits = total;
          summary.largestDebit = cursor.getLong(3);
        }
      }
    }
    summary.net = summary.credits - summary.debits;
    summary.averagePaise = summary.count == 0 ? 0
        : Money.divide(summary.credits + summary.debits, summary.count);
    try (Cursor cursor = db.rawQuery("SELECT category,SUM(amount_paise) FROM txn WHERE " + base
        + " AND direction='DEBIT' AND internal_transfer=0"
        + " GROUP BY category ORDER BY 2 DESC", arguments)) {
      while (cursor.moveToNext())
        summary.categoryDebits.put(cursor.getString(0) == null || cursor.getString(0).isEmpty()
            ? "Other" : cursor.getString(0), cursor.getLong(1));
    }
    try (Cursor cursor = db.rawQuery("SELECT provider,COUNT(*) FROM txn WHERE " + base
        + " GROUP BY provider", arguments)) {
      while (cursor.moveToNext())
        summary.providerCounts.put(cursor.getString(0) == null || cursor.getString(0).isEmpty()
            ? "Manual" : cursor.getString(0), cursor.getInt(1));
    }
    try (Cursor cursor = db.rawQuery("SELECT account_masked,SUM(amount_paise) FROM txn WHERE " + base
        + " GROUP BY account_masked ORDER BY 2 DESC", arguments)) {
      while (cursor.moveToNext())
        summary.accountTotals.put(cursor.getString(0) == null || cursor.getString(0).isEmpty()
            ? "Unlinked account" : cursor.getString(0), cursor.getLong(1));
    }
    try (Cursor cursor = db.rawQuery("SELECT CASE WHEN counterparty IS NULL OR counterparty=''"
        + " THEN upi ELSE counterparty END, SUM(amount_paise) FROM txn WHERE " + base
        + " AND direction='DEBIT' AND internal_transfer=0 GROUP BY 1 ORDER BY 2 DESC LIMIT 25",
        arguments)) {
      while (cursor.moveToNext())
        summary.beneficiaryDebits.put(cursor.getString(0) == null || cursor.getString(0).isEmpty()
            ? "Unknown" : cursor.getString(0), cursor.getLong(1));
    }
    try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM txn WHERE " + base
        + " AND reconciliation IN ('UNVERIFIED','CONFLICT')", arguments)) {
      if (cursor.moveToFirst()) summary.unreconciled = cursor.getInt(0);
    }
    try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM txn WHERE " + base
        + " AND execution IN ('INITIATED','PROVIDER_REPORTED_PENDING','UNKNOWN')", arguments)) {
      if (cursor.moveToFirst()) summary.pending = cursor.getInt(0);
    }
    try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM txn WHERE " + base
        + " AND reconciliation='REFUNDED'", arguments)) {
      if (cursor.moveToFirst()) summary.refunds = cursor.getInt(0);
    }
    try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM txn WHERE " + base
        + " AND reconciliation='REVERSED'", arguments)) {
      if (cursor.moveToFirst()) summary.reversals = cursor.getInt(0);
    }
    try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM txn WHERE occurred_at>=?"
        + " AND occurred_at<? AND execution NOT IN ('CREATED','CANCELLED',"
        + "'PROVIDER_REPORTED_FAILURE') AND internal_transfer=1",
        new String[]{String.valueOf(range.from), String.valueOf(range.toExclusive)})) {
      if (cursor.moveToFirst()) summary.internalTransfers = cursor.getInt(0);
    }
    try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM txn WHERE occurred_at>=?"
        + " AND occurred_at<? AND execution IN ('CREATED','CANCELLED','PROVIDER_REPORTED_FAILURE')",
        new String[]{String.valueOf(range.from), String.valueOf(range.toExclusive)})) {
      if (cursor.moveToFirst()) summary.failedOrCancelled = cursor.getInt(0);
    }
    if (includeUnverified) {
      try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM txn WHERE " + base
          + " AND reconciliation='UNVERIFIED'", arguments)) {
        if (cursor.moveToFirst()) summary.unverifiedIncluded = cursor.getInt(0);
      }
    }
    try (Cursor cursor = db.rawQuery("SELECT strftime('%Y-%m-%d', occurred_at/1000,"
        + " 'unixepoch','localtime') AS day, SUM(CASE WHEN direction='CREDIT' THEN"
        + " amount_paise ELSE 0 END), SUM(CASE WHEN direction='DEBIT' THEN amount_paise ELSE 0 END)"
        + " FROM txn WHERE " + base + " AND reconciliation NOT IN ('REVERSED','REFUNDED')"
        + " GROUP BY day ORDER BY day", arguments)) {
      while (cursor.moveToNext()) {
        long start = dayStart(cursor.getString(0));
        if (start <= 0) continue;
        Reports.Day day = new Reports.Day(start);
        day.credits = cursor.getLong(1);
        day.debits = cursor.getLong(2);
        summary.days.put(start, day);
      }
    }
    return summary;
  }

  private static long dayStart(String isoDay) {
    if (isoDay == null) return 0;
    try {
      return new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDay).getTime();
    } catch (Exception ignored) {
      return 0;
    }
  }

  /** Distinct values for the ledger filter chips. */
  public Map<String, String> facets(String column) {
    Map<String, String> values = new LinkedHashMap<>();
    if (!column.matches("provider|category|account_id|account_masked")) return values;
    try (Cursor cursor = db.rawQuery("SELECT " + column + ",COUNT(*) FROM txn WHERE " + column
        + " IS NOT NULL AND " + column + "<>'' GROUP BY " + column + " ORDER BY 2 DESC LIMIT 40",
        null)) {
      while (cursor.moveToNext()) values.put(cursor.getString(0), String.valueOf(cursor.getInt(1)));
    }
    return values;
  }

  // ------------------------------------------------------------------ accounts & beneficiaries

  public List<Account> accounts() {
    List<Account> accounts = new ArrayList<>();
    try (Cursor cursor = db.query("accounts", null, null, null, null, null, "created_at")) {
      while (cursor.moveToNext()) {
        Account account = new Account();
        account.id = cursor.getString(0);
        account.name = text(cursor, 1);
        account.bank = text(cursor, 2);
        account.masked = text(cursor, 3);
        account.upi = text(cursor, 4);
        account.createdAt = cursor.getLong(5);
        accounts.add(account);
      }
    }
    return accounts;
  }

  public void saveAccount(Account account) {
    ContentValues values = new ContentValues();
    values.put("id", account.id);
    values.put("name", account.name);
    values.put("bank", account.bank);
    values.put("masked", account.masked);
    values.put("upi", account.upi);
    values.put("created_at", account.createdAt);
    db.insertWithOnConflict("accounts", null, values, SQLiteDatabase.CONFLICT_REPLACE);
  }

  public void deleteAccount(String id) {
    db.delete("accounts", "id=?", new String[]{id});
  }

  public List<Beneficiary> beneficiaries(String query) {
    List<Beneficiary> list = new ArrayList<>();
    String where = query == null || query.trim().isEmpty() ? null
        : "name LIKE ? OR upi LIKE ?";
    String[] arguments = where == null ? null
        : new String[]{"%" + query.trim() + "%", "%" + query.trim() + "%"};
    try (Cursor cursor = db.query("beneficiaries", null, where, arguments, null, null,
        "last_used DESC, name ASC", "200")) {
      while (cursor.moveToNext()) {
        Beneficiary beneficiary = new Beneficiary();
        beneficiary.id = cursor.getString(0);
        beneficiary.name = text(cursor, 1);
        beneficiary.upi = text(cursor, 2);
        beneficiary.note = text(cursor, 3);
        beneficiary.lastUsed = cursor.getLong(4);
        beneficiary.uses = cursor.getInt(5);
        list.add(beneficiary);
      }
    }
    return list;
  }

  public void saveBeneficiary(Beneficiary beneficiary) {
    ContentValues values = new ContentValues();
    values.put("id", beneficiary.id);
    values.put("name", beneficiary.name);
    values.put("upi", beneficiary.upi);
    values.put("note", beneficiary.note);
    values.put("last_used", beneficiary.lastUsed);
    values.put("uses", beneficiary.uses);
    db.insertWithOnConflict("beneficiaries", null, values, SQLiteDatabase.CONFLICT_REPLACE);
  }

  public void touchBeneficiary(String upi, String name) {
    if (upi == null || upi.isEmpty()) return;
    long now = System.currentTimeMillis();
    db.execSQL("UPDATE beneficiaries SET uses=uses+1,last_used=?,"
        + " name=CASE WHEN name IS NULL OR name='' THEN ? ELSE name END WHERE upi=?",
        new Object[]{now, name == null ? "" : name, upi});
    try (Cursor cursor = db.rawQuery("SELECT 1 FROM beneficiaries WHERE upi=?",
        new String[]{upi})) {
      if (cursor.moveToFirst()) return;
    }
    Beneficiary beneficiary = new Beneficiary();
    beneficiary.upi = upi;
    beneficiary.name = name == null ? "" : name;
    beneficiary.lastUsed = now;
    beneficiary.uses = 1;
    saveBeneficiary(beneficiary);
  }

  public void deleteBeneficiary(String id) {
    db.delete("beneficiaries", "id=?", new String[]{id});
  }

  // ------------------------------------------------------------------ import batches

  public void saveBatch(String id, String source, String fileName, int imported, int duplicates) {
    ContentValues values = new ContentValues();
    values.put("id", id);
    values.put("started_at", System.currentTimeMillis());
    values.put("source", source);
    values.put("file_name", fileName);
    values.put("imported", imported);
    values.put("duplicates", duplicates);
    db.insertWithOnConflict("batches", null, values, SQLiteDatabase.CONFLICT_REPLACE);
  }

  public String batchJson() {
    StringBuilder json = new StringBuilder("[");
    try (Cursor cursor = db.query("batches", null, null, null, null, null, "started_at DESC", "20")) {
      boolean first = true;
      while (cursor.moveToNext()) {
        if (!first) json.append(',');
        first = false;
        json.append("{\"id\":\"").append(escape(cursor.getString(0)))
            .append("\",\"startedAt\":").append(cursor.getLong(1))
            .append(",\"source\":\"").append(escape(text(cursor, 2)))
            .append("\",\"file\":\"").append(escape(text(cursor, 3)))
            .append("\",\"imported\":").append(cursor.getInt(4))
            .append(",\"duplicates\":").append(cursor.getInt(5)).append('}');
      }
    }
    return json.append(']').toString();
  }

  /** Complete ledger as JSON for the encrypted backup and JSON export. */
  public String transactionsJson(long from, long to) {
    StringBuilder json = new StringBuilder("[");
    Filters filters = new Filters();
    filters.from = from;
    filters.to = to;
    filters.limit(100000);
    filters.sort = "date_asc";
    List<LedgerTransaction> rows = query(filters);
    for (int index = 0; index < rows.size(); index++) {
      if (index > 0) json.append(',');
      json.append(in.otrail.extensions.opay.core.Json.write(rows.get(index).toMap()));
    }
    return json.append(']').toString();
  }

  // ------------------------------------------------------------------ mapping

  public static String strongKey(LedgerTransaction transaction) {
    String utr = transaction.utr == null ? "" : transaction.utr.trim().toUpperCase(Locale.US);
    if (!utr.isEmpty()) return "utr:" + utr;
    String reference = transaction.transactionId == null ? ""
        : transaction.transactionId.trim().toUpperCase(Locale.US);
    if (!reference.isEmpty())
      return "ref:" + transaction.provider.toLowerCase(Locale.US) + ":" + reference;
    return null;
  }

  private ContentValues values(LedgerTransaction transaction) {
    ContentValues values = new ContentValues();
    values.put("id", transaction.id);
    values.put("created_at", transaction.createdAt);
    values.put("occurred_at", transaction.occurredAt);
    values.put("direction", transaction.direction.name());
    values.put("amount_paise", transaction.amountPaise);
    values.put("currency", transaction.currency);
    values.put("provider", transaction.provider);
    values.put("account_id", transaction.accountId);
    values.put("account_masked", transaction.accountMasked);
    values.put("counterparty", transaction.counterparty);
    values.put("upi", transaction.upiAddress);
    values.put("transaction_id", transaction.transactionId);
    values.put("utr", transaction.utr);
    values.put("note", transaction.note);
    values.put("category", transaction.category);
    values.put("execution", transaction.execution.name());
    values.put("reconciliation", transaction.reconciliation.name());
    values.put("origin", transaction.origin.name());
    values.put("import_batch", transaction.importBatch);
    values.put("related_id", transaction.relatedId);
    values.put("annotations", transaction.annotations);
    values.put("internal_transfer", transaction.internalTransfer ? 1 : 0);
    if (transaction.latitude != null) values.put("latitude", transaction.latitude);
    else values.putNull("latitude");
    if (transaction.longitude != null) values.put("longitude", transaction.longitude);
    else values.putNull("longitude");
    if (transaction.locationAccuracy != null) values.put("location_accuracy", transaction.locationAccuracy);
    else values.putNull("location_accuracy");
    values.put("location_at", transaction.locationAt);
    values.put("location_provider", transaction.locationProvider);
    String strong = strongKey(transaction);
    if (strong == null) values.putNull("strong_key");
    else values.put("strong_key", strong);
    return values;
  }

  private LedgerTransaction read(Cursor cursor) {
    LedgerTransaction transaction = new LedgerTransaction();
    transaction.id = text(cursor, 0);
    transaction.createdAt = cursor.getLong(1);
    transaction.occurredAt = cursor.getLong(2);
    transaction.direction = LedgerTransaction.direction(cursor.getString(3));
    transaction.amountPaise = cursor.getLong(4);
    transaction.currency = text(cursor, 5);
    transaction.provider = text(cursor, 6);
    transaction.accountId = text(cursor, 7);
    transaction.accountMasked = text(cursor, 8);
    transaction.counterparty = text(cursor, 9);
    transaction.upiAddress = text(cursor, 10);
    transaction.transactionId = text(cursor, 11);
    transaction.utr = text(cursor, 12);
    transaction.note = text(cursor, 13);
    transaction.category = text(cursor, 14);
    transaction.execution = LedgerTransaction.execution(cursor.getString(15));
    transaction.reconciliation = LedgerTransaction.reconciliation(cursor.getString(16));
    transaction.origin = LedgerTransaction.origin(cursor.getString(17));
    transaction.importBatch = text(cursor, 18);
    transaction.relatedId = text(cursor, 19);
    transaction.annotations = text(cursor, 20);
    transaction.internalTransfer = cursor.getInt(21) != 0;
    if (!cursor.isNull(22)) transaction.latitude = cursor.getDouble(22);
    if (!cursor.isNull(23)) transaction.longitude = cursor.getDouble(23);
    if (!cursor.isNull(24)) transaction.locationAccuracy = cursor.getFloat(24);
    transaction.locationAt = cursor.getLong(25);
    transaction.locationProvider = text(cursor, 26);
    return transaction;
  }

  private static String text(Cursor cursor, int index) {
    String value = cursor.getString(index);
    return value == null ? "" : value;
  }

  private static String escape(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /** Newest-first date label used by ledger rows, kept here so the UI stays thin. */
  public static String dateLabel(long time) {
    return new SimpleDateFormat("dd MMM yyyy", Locale.US).format(new Date(time));
  }

  public static List<LedgerTransaction> empty() {
    return Collections.emptyList();
  }
}
