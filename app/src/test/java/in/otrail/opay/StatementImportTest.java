package in.otrail.opay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import in.otrail.extensions.opay.core.LedgerTransaction;
import in.otrail.extensions.opay.core.StatementImport;
import in.otrail.extensions.opay.core.Xlsx;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public class StatementImportTest {
  @Test public void readsBankCsvWithDebitCreditColumns() throws Exception {
    StatementImport.Result result = StatementImport.parse("bank-statement.csv",
        Fixtures.bytes("bank-statement.csv"), "acc-1", "XX1234");
    assertEquals(StatementImport.Format.CSV, result.format);
    assertEquals(6, result.rows.size());
    LedgerTransaction first = result.rows.get(0);
    assertEquals(25_000L, first.amountPaise);
    assertEquals(LedgerTransaction.Direction.DEBIT, first.direction);
    assertEquals("612345678901", first.utr);
    assertEquals("XX1234", first.accountMasked);
    assertEquals(LedgerTransaction.Reconciliation.STATEMENT_MATCHED, first.reconciliation);
    LedgerTransaction salary = result.rows.get(1);
    assertEquals(8_500_000L, salary.amountPaise);
    assertEquals(LedgerTransaction.Direction.CREDIT, salary.direction);
  }

  @Test public void detectsProviderFromFileNameAndUsesAmountTypeColumns() throws Exception {
    StatementImport.Result result = StatementImport.parse("phonepe-style.csv",
        Fixtures.bytes("phonepe-style.csv"), "", "");
    assertEquals("PhonePe", result.provider);
    assertEquals(3, result.rows.size());
    assertEquals(LedgerTransaction.Direction.DEBIT, result.rows.get(0).direction);
    assertEquals(25_000L, result.rows.get(0).amountPaise);
    assertEquals("swiggy@ybl", result.rows.get(0).upiAddress);
    assertEquals(LedgerTransaction.Direction.CREDIT, result.rows.get(1).direction);
  }

  @Test public void readsJsonArrayExport() throws Exception {
    StatementImport.Result result = StatementImport.parse("generic-json.json",
        Fixtures.bytes("generic-json.json"), "acc-2", "XX9999");
    assertEquals(StatementImport.Format.JSON, result.format);
    assertEquals(3, result.rows.size());
    assertEquals(120_050L, result.rows.get(0).amountPaise);
    assertEquals("XX9999", result.rows.get(2).accountMasked);
  }

  @Test public void readsPdfWithEmbeddedText() throws Exception {
    StatementImport.Result result = StatementImport.parse("bank-statement.pdf",
        Fixtures.bytes("bank-statement.pdf"), "", "");
    assertEquals(StatementImport.Format.PDF, result.format);
    assertEquals(3, result.rows.size());
    assertEquals(25_000L, result.rows.get(0).amountPaise);
    assertEquals(LedgerTransaction.Direction.CREDIT, result.rows.get(1).direction);
    assertEquals(8_500_000L, result.rows.get(1).amountPaise);
    assertFalse(result.warnings.isEmpty());
  }

  @Test public void rejectsBrokenPdfExplicitly() {
    try {
      StatementImport.parse("bad.pdf", "%PDF-1.4\nnot really a pdf".getBytes(StandardCharsets.UTF_8),
          "", "");
      fail("Expected PDF failure");
    } catch (IOException expected) {
      assertTrue(expected.getMessage() != null);
    }
  }

  @Test public void parsesSmsAndIgnoresOtp() throws Exception {
    StatementImport.Result result = StatementImport.parseSmsText(Fixtures.text("bank-sms.txt"),
        "acc-1", "XX1234");
    assertEquals(3, result.rows.size());
    assertEquals(25_000L, result.rows.get(0).amountPaise);
    assertEquals(LedgerTransaction.Direction.DEBIT, result.rows.get(0).direction);
    assertEquals(8_500_000L, result.rows.get(1).amountPaise);
    assertEquals(LedgerTransaction.Direction.CREDIT, result.rows.get(1).direction);
    assertTrue(result.rows.get(0).utr.startsWith("612345678901"));
  }

  @Test public void removesDuplicateRowsInsideOneFile() throws Exception {
    String csv = "Date,Description,Debit,Credit,Reference\n"
        + "01/04/2026,UPI/SWIGGY,250.00,,612345678901\n"
        + "01/04/2026,UPI/SWIGGY,250.00,,612345678901\n";
    StatementImport.Result result = StatementImport.parse("dup.csv",
        csv.getBytes(StandardCharsets.UTF_8), "", "");
    assertEquals(1, result.rows.size());
    assertEquals(1, result.duplicates);
    assertTrue(result.duplicates > 0);
  }

  @Test public void repeatedParseProducesTheSameStrongKeys() throws Exception {
    byte[] bytes = Fixtures.bytes("bank-statement.csv");
    StatementImport.Result first = StatementImport.parse("bank-statement.csv", bytes, "", "");
    StatementImport.Result second = StatementImport.parse("bank-statement.csv", bytes, "", "");
    Set<String> keys = new HashSet<>();
    for (LedgerTransaction row : first.rows) keys.add(row.dedupeKey());
    Set<String> again = new HashSet<>();
    for (LedgerTransaction row : second.rows) again.add(row.dedupeKey());
    assertEquals(keys, again);
  }

  @Test public void manualMappingRescuesUnknownHeaders() throws Exception {
    String csv = "When,What,Money Out,Money In,Ref\n"
        + "01/04/2026,Coffee,120.00,,REF123\n";
    StatementImport.Result result = StatementImport.parse("odd.csv",
        csv.getBytes(StandardCharsets.UTF_8), "", "");
    assertTrue(result.needsMapping());
    int[] mapping = new int[StatementImport.ROLE_COUNT];
    for (int index = 0; index < mapping.length; index++) mapping[index] = -1;
    mapping[StatementImport.DATE] = 0;
    mapping[StatementImport.DESCRIPTION] = 1;
    mapping[StatementImport.DEBIT] = 2;
    mapping[StatementImport.CREDIT] = 3;
    mapping[StatementImport.REFERENCE] = 4;
    StatementImport.Result remapped = StatementImport.applyMapping(result, mapping, "", "");
    assertEquals(1, remapped.rows.size());
    assertEquals(12_000L, remapped.rows.get(0).amountPaise);
    assertEquals(LedgerTransaction.Direction.DEBIT, remapped.rows.get(0).direction);
  }

  @Test public void readsMinimalXlsxWorkbook() throws Exception {
    byte[] bytes = workbook();
    List<List<String>> table = Xlsx.read(bytes);
    assertEquals(3, table.size());
    assertEquals("Date", table.get(0).get(0));
    StatementImport.Result result = StatementImport.parse("statement.xlsx", bytes, "", "");
    assertEquals(StatementImport.Format.XLSX, result.format);
    assertEquals(2, result.rows.size());
    assertEquals(25_000L, result.rows.get(0).amountPaise);
    assertEquals(LedgerTransaction.Direction.DEBIT, result.rows.get(0).direction);
    assertEquals(1_00_000L, result.rows.get(1).amountPaise);
    assertEquals(LedgerTransaction.Direction.CREDIT, result.rows.get(1).direction);
  }

  @Test public void rejectsUnknownBinary() {
    try {
      StatementImport.parse("photo.jpg", new byte[]{(byte) 0xff, (byte) 0xd8, 1, 2, 3}, "", "");
      fail("Expected unsupported format");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("Unsupported"));
    }
  }

  private static byte[] workbook() throws IOException {
    String[] shared = {"Date", "Description", "Debit", "Credit", "Reference",
        "01/04/2026", "UPI/SWIGGY", "REF001", "02/04/2026", "SALARY", "REF002"};
    StringBuilder strings = new StringBuilder("<sst xmlns=\"http://x\" count=\"%d\" uniqueCount=\"%d\">");
    for (String value : shared) strings.append("<si><t>").append(value).append("</t></si>");
    strings.append("</sst>");
    String sheet = "<worksheet xmlns=\"http://x\"><sheetData>"
        + row(1, new String[]{"s0", "s1", "s2", "s3", "s4"})
        + row(2, new String[]{"s5", "s6", "250.00", "", "s7"})
        + row(3, new String[]{"s8", "s9", "", "1000.00", "s10"})
        + "</sheetData></worksheet>";
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
      zip.write(String.format(strings.toString(), shared.length, shared.length)
          .getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
      zip.write(sheet.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return out.toByteArray();
  }

  private static String row(int number, String[] cells) {
    StringBuilder xml = new StringBuilder("<row r=\"" + number + "\">");
    char column = 'A';
    for (String cell : cells) {
      String reference = String.valueOf(column++) + number;
      if (cell.startsWith("s")) {
        xml.append("<c r=\"").append(reference).append("\" t=\"s\"><v>")
            .append(cell.substring(1)).append("</v></c>");
      } else if (!cell.isEmpty()) {
        xml.append("<c r=\"").append(reference).append("\"><v>").append(cell).append("</v></c>");
      }
    }
    return xml.append("</row>").toString();
  }

  @Test public void roleListStaysStable() {
    List<String> names = new ArrayList<>(StatementImport.roles().keySet());
    assertEquals(StatementImport.ROLE_COUNT, names.size());
    assertEquals("date", names.get(0));
  }
}
