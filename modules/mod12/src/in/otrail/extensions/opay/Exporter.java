package in.otrail.extensions.opay;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import in.otrail.extensions.opay.core.Csv;
import in.otrail.extensions.opay.core.Json;
import in.otrail.extensions.opay.core.LedgerTransaction;
import in.otrail.extensions.opay.core.Money;
import in.otrail.extensions.opay.core.Reports;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Local exports: CSV, JSON and a paginated PDF statement/summary. */
final class Exporter {
  private Exporter() {}

  static String csv(List<LedgerTransaction> rows) {
    StringBuilder out = new StringBuilder(
        "Date,Direction,Amount,Currency,Provider,Account,Counterparty,UPI,Txn ID,UTR,"
        + "Category,Execution,Reconciliation,Origin,Note,Latitude,Longitude,Accuracy m,Fix time\n");
    SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    for (LedgerTransaction row : rows) {
      out.append(Csv.escape(date.format(new Date(row.occurredAt)))).append(',')
          .append(row.direction.name()).append(',')
          .append(Csv.escape(Money.rupees(row.amountPaise))).append(',')
          .append(row.currency).append(',')
          .append(Csv.escape(row.provider)).append(',')
          .append(Csv.escape(row.accountMasked)).append(',')
          .append(Csv.escape(row.counterparty)).append(',')
          .append(Csv.escape(row.upiAddress)).append(',')
          .append(Csv.escape(row.transactionId)).append(',')
          .append(Csv.escape(row.utr)).append(',')
          .append(Csv.escape(row.category)).append(',')
          .append(row.execution.name()).append(',')
          .append(row.reconciliation.name()).append(',')
          .append(row.origin.name()).append(',')
          .append(Csv.escape(row.note)).append(',')
          .append(row.latitude == null ? "" : String.valueOf(row.latitude)).append(',')
          .append(row.longitude == null ? "" : String.valueOf(row.longitude)).append(',')
          .append(row.locationAccuracy == null ? "" : String.valueOf(row.locationAccuracy)).append(',')
          .append(row.locationAt == 0 ? "" : date.format(new Date(row.locationAt))).append('\n');
    }
    return out.toString();
  }

  static String json(List<LedgerTransaction> rows) {
    List<Object> maps = new ArrayList<>();
    for (LedgerTransaction row : rows) maps.add(row.toMap());
    return Json.write(maps) + "\n";
  }

  static byte[] pdf(Reports.Summary summary, List<LedgerTransaction> rows, String title,
      String subtitle) {
    PdfDocument document = new PdfDocument();
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    titlePaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    titlePaint.setTextSize(16);
    titlePaint.setColor(Color.BLACK);
    Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    labelPaint.setTextSize(9);
    labelPaint.setColor(Color.DKGRAY);
    Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    bodyPaint.setTextSize(9);
    bodyPaint.setColor(Color.BLACK);
    Paint boldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    boldPaint.setTextSize(10);
    boldPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    boldPaint.setColor(Color.BLACK);
    SimpleDateFormat date = new SimpleDateFormat("dd MMM yyyy", Locale.US);

    int pageNumber = 1, y;
    PdfDocument.Page page = null;
    y = 52;
    page = startPage(document, pageNumber);
    Canvas canvas = page.getCanvas();
    canvas.drawText("oPay \u00b7 " + title, 36, 40, titlePaint);
    canvas.drawText(subtitle, 36, 56, labelPaint);
    canvas.drawText("Generated " + new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US)
        .format(new Date()) + " \u00b7 local-only", 36, 68, labelPaint);

    if (summary != null) {
      y = 92;
      canvas.drawText("Credits " + Money.format(summary.credits)
          + "    Debits " + Money.format(summary.debits)
          + "    Net " + Money.signed(summary.net), 36, y, boldPaint);
      y += 16;
      canvas.drawText(summary.count + " transactions \u00b7 average "
          + Money.format(summary.averagePaise) + " \u00b7 largest debit "
          + Money.format(summary.largestDebit) + " \u00b7 largest credit "
          + Money.format(summary.largestCredit), 36, y, bodyPaint);
      y += 14;
      canvas.drawText("Unreconciled " + summary.unreconciled + " \u00b7 pending "
          + summary.pending + " \u00b7 refunds " + summary.refunds + " \u00b7 reversals "
          + summary.reversals + " \u00b7 failed/cancelled " + summary.failedOrCancelled,
          36, y, bodyPaint);
      y += 18;
      canvas.drawText("Date", 36, y, labelPaint);
      canvas.drawText("Counterparty", 120, y, labelPaint);
      canvas.drawText("Status", 330, y, labelPaint);
      canvas.drawText("Amount", 470, y, labelPaint);
      y += 4;
      canvas.drawLine(36, y, 559, y, labelPaint);
      y += 14;
    }

    for (LedgerTransaction row : rows) {
      if (y > 790) {
        document.finishPage(page);
        pageNumber++;
        page = startPage(document, pageNumber);
        canvas = page.getCanvas();
        y = 48;
        canvas.drawText("oPay \u00b7 " + title + " \u00b7 continued", 36, 34, labelPaint);
      }
      canvas.drawText(date.format(new Date(row.occurredAt)), 36, y, bodyPaint);
      String counterparty = row.counterparty.isEmpty() ? row.upiAddress : row.counterparty;
      if (counterparty.isEmpty()) counterparty = row.category;
      canvas.drawText(clip(counterparty, 40), 120, y, bodyPaint);
      canvas.drawText(shortState(row), 330, y, bodyPaint);
      paint.setColor(row.direction == LedgerTransaction.Direction.CREDIT
          ? Color.rgb(0, 120, 60) : Color.rgb(160, 30, 30));
      canvas.drawText((row.direction == LedgerTransaction.Direction.CREDIT ? "+" : "\u2212")
          + Money.format(row.amountPaise), 470, y, paint);
      if (row.hasLocation()) {
        y += 11;
        canvas.drawText(clip("GPS " + row.locationText(), 98), 120, y, labelPaint);
      }
      y += 15;
    }
    document.finishPage(page);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      document.writeTo(out);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("PDF could not be written: " + error.getMessage(), error);
    } finally {
      document.close();
    }
    return out.toByteArray();
  }

  private static PdfDocument.Page startPage(PdfDocument document, int number) {
    PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(595, 842, number).create();
    PdfDocument.Page page = document.startPage(info);
    Paint footer = new Paint();
    footer.setTextSize(8);
    footer.setColor(Color.GRAY);
    page.getCanvas().drawText("Page " + number, 530, 822, footer);
    page.getCanvas().drawText("oPay is offline-first; verify settlements in your bank statement.",
        36, 822, footer);
    return page;
  }

  private static String shortState(LedgerTransaction row) {
    if (row.reconciliation == LedgerTransaction.Reconciliation.STATEMENT_MATCHED)
      return "Reconciled";
    if (row.reconciliation == LedgerTransaction.Reconciliation.USER_CONFIRMED)
      return "Confirmed";
    if (row.reconciliation == LedgerTransaction.Reconciliation.REFUNDED) return "Refunded";
    if (row.reconciliation == LedgerTransaction.Reconciliation.REVERSED) return "Reversed";
    if (row.reconciliation == LedgerTransaction.Reconciliation.CONFLICT) return "Conflict";
    if (row.execution == LedgerTransaction.Execution.PROVIDER_REPORTED_FAILURE) return "Failed";
    if (row.execution == LedgerTransaction.Execution.CANCELLED) return "Cancelled";
    if (row.execution == LedgerTransaction.Execution.PROVIDER_REPORTED_PENDING) return "Pending";
    return "Unverified";
  }

  private static String clip(String text, int limit) {
    if (text == null) return "";
    String value = text.replace('\n', ' ');
    return value.length() <= limit ? value : value.substring(0, Math.max(0, limit - 1)) + "\u2026";
  }

  static List<Map<String, Object>> maps(List<LedgerTransaction> rows) {
    List<Map<String, Object>> maps = new ArrayList<>();
    for (LedgerTransaction row : rows) maps.add(row.toMap());
    return maps;
  }
}
