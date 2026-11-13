package com.neobuddy.processing.extract;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.util.unit.DataSize;

/**
 * Builds every test file in memory. Nothing here is real: the names, NeoIDs and registration
 * numbers are made up, and no fixture file is checked in.
 */
final class SyntheticFiles {

  record Student(String neoId, String name, long regNo, String slot) {}

  static final List<Student> SHORTLIST =
      List.of(
          new Student("Z9X8C7V6", "Asha Example", 2021000123L, "10:00"),
          new Student("Q1W2E3R4", "Ravi Sample", 2021000456L, "10:30"),
          new Student("M5N6B7V8", "Meera Placeholder", 212345678901L, "11:00"));

  static final Student WAITLISTED = new Student("K3J4H5G6", "Dev Fictional", 2021000789L, "TBD");

  static final List<String> HEADER = List.of("NeoID", "Name", "Reg No", "Slot");

  private SyntheticFiles() {}

  static ExtractionProperties properties() {
    return properties(DataSize.ofMegabytes(25), DataSize.ofMegabytes(5));
  }

  static ExtractionProperties properties(DataSize maxAttachment, DataSize streamingThreshold) {
    return new ExtractionProperties(maxAttachment, streamingThreshold, Duration.ofSeconds(30), 2);
  }

  static AttachmentRef ref(String filename, byte[] content) {
    return new AttachmentRef(filename, null, content.length);
  }

  /** The row text every tabular extractor should produce for a student. */
  static String rowText(Student student) {
    return String.join(
        TextChunk.CELL_SEPARATOR,
        student.neoId(),
        student.name(),
        Long.toString(student.regNo()),
        student.slot());
  }

  // ---- Excel -------------------------------------------------------------------------------

  /**
   * Two sheets. "Shortlist": header in row 1, students in rows 2-3, row 4 blank, the last student
   * in row 5, a formula in row 6. "Waitlist": header and one student. Reg No is a numeric cell.
   */
  static byte[] xlsx() {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      fillWorkbook(workbook);
      workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
      return write(workbook);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Same content as {@link #xlsx()} in the old binary format. */
  static byte[] xls() {
    try (HSSFWorkbook workbook = new HSSFWorkbook()) {
      fillWorkbook(workbook);
      workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
      return write(workbook);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static byte[] xlsxWithoutRows() {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      workbook.createSheet("Empty");
      return write(workbook);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static byte[] xlsWithoutRows() {
    try (HSSFWorkbook workbook = new HSSFWorkbook()) {
      workbook.createSheet("Empty");
      return write(workbook);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A sheet of {@code rows} generated students, for size and streaming tests. */
  static byte[] largeXlsx(int rows) {
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
      Sheet sheet = workbook.createSheet("Big");
      for (int i = 1; i <= rows; i++) {
        Row row = sheet.createRow(i - 1);
        row.createCell(0).setCellValue(String.format("T%07d", i));
        row.createCell(1).setCellValue("Student " + i);
        row.createCell(2).setCellValue(2021000000d + i);
      }
      return write(workbook);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A valid .xlsx with one extra, absurdly compressible entry: a zip bomb as far as POI cares. */
  static byte[] zipBombXlsx() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(xlsx()));
        ZipOutputStream zip = new ZipOutputStream(out)) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        zip.putNextEntry(new ZipEntry(entry.getName()));
        in.transferTo(zip);
        zip.closeEntry();
      }
      zip.putNextEntry(new ZipEntry("xl/media/padding.bin"));
      zip.write(new byte[20 * 1024 * 1024]);
      zip.closeEntry();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  private static void fillWorkbook(Workbook workbook) {
    Sheet shortlist = workbook.createSheet("Shortlist");
    header(shortlist.createRow(0));
    student(shortlist.createRow(1), SHORTLIST.get(0));
    student(shortlist.createRow(2), SHORTLIST.get(1));
    shortlist.createRow(3).createCell(0).setCellValue("   ");
    student(shortlist.createRow(4), SHORTLIST.get(2));
    Row totals = shortlist.createRow(5);
    totals.createCell(0).setCellValue("Total");
    totals.createCell(1).setCellFormula("COUNTA(A2:A5)-1");

    Sheet waitlist = workbook.createSheet("Waitlist");
    header(waitlist.createRow(0));
    student(waitlist.createRow(1), WAITLISTED);
  }

  private static void header(Row row) {
    for (int i = 0; i < HEADER.size(); i++) {
      row.createCell(i).setCellValue(HEADER.get(i));
    }
  }

  private static void student(Row row, Student student) {
    row.createCell(0).setCellValue(student.neoId());
    row.createCell(1).setCellValue(student.name());
    row.createCell(2).setCellValue((double) student.regNo());
    row.createCell(3).setCellValue(student.slot());
  }

  private static byte[] write(Workbook workbook) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    workbook.write(out);
    return out.toByteArray();
  }

  // ---- PDF ---------------------------------------------------------------------------------

  /** Page 1: a title and a four-column table of the shortlist. Page 2: the waitlisted student. */
  static byte[] pdfWithTable() {
    try (PDDocument document = new PDDocument()) {
      PDPage first = new PDPage();
      document.addPage(first);
      try (PDPageContentStream content = new PDPageContentStream(document, first)) {
        text(content, 50, 740, "Example Corp shortlist");
        tableRow(content, 700, HEADER);
        float y = 680;
        for (Student student : SHORTLIST) {
          tableRow(content, y, cells(student));
          y -= 20;
        }
      }
      PDPage second = new PDPage();
      document.addPage(second);
      try (PDPageContentStream content = new PDPageContentStream(document, second)) {
        tableRow(content, 740, cells(WAITLISTED));
      }
      return save(document);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static byte[] pdfWithBlankPage() {
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage());
      return save(document);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<String> cells(Student student) {
    return List.of(student.neoId(), student.name(), Long.toString(student.regNo()), student.slot());
  }

  private static void tableRow(PDPageContentStream content, float y, List<String> cells)
      throws IOException {
    float[] columns = {50, 150, 320, 450};
    for (int i = 0; i < cells.size(); i++) {
      text(content, columns[i], y, cells.get(i));
    }
  }

  private static void text(PDPageContentStream content, float x, float y, String value)
      throws IOException {
    content.beginText();
    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
    content.newLineAtOffset(x, y);
    content.showText(value);
    content.endText();
  }

  private static byte[] save(PDDocument document) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    document.save(out);
    return out.toByteArray();
  }

  // ---- Word --------------------------------------------------------------------------------

  /** A heading paragraph, a blank paragraph, the shortlist table, then a closing paragraph. */
  static byte[] docxWithTable() {
    try (XWPFDocument document = new XWPFDocument()) {
      document.createParagraph().createRun().setText("Example Corp shortlist");
      document.createParagraph();
      XWPFTable table = document.createTable(SHORTLIST.size() + 1, HEADER.size());
      fillRow(table.getRow(0), HEADER);
      for (int i = 0; i < SHORTLIST.size(); i++) {
        fillRow(table.getRow(i + 1), cells(SHORTLIST.get(i)));
      }
      document.createParagraph().createRun().setText("Report to the placement cell by 9:30.");
      return write(document);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static byte[] docxWithoutContent() {
    try (XWPFDocument document = new XWPFDocument()) {
      return write(document);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void fillRow(XWPFTableRow row, List<String> cells) {
    for (int i = 0; i < cells.size(); i++) {
      row.getCell(i).setText(cells.get(i));
    }
  }

  private static byte[] write(XWPFDocument document) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    document.write(out);
    return out.toByteArray();
  }

  // ---- HTML and text -----------------------------------------------------------------------

  /**
   * An email the way mail clients build them: a layout table wrapping everything, prose, a data
   * table of the shortlist, a {@code <br>}-separated list and a footer.
   */
  static String htmlEmail() {
    StringBuilder rows = new StringBuilder();
    rows.append("<tr><th>NeoID</th><th>Name</th><th>Reg No</th><th>Slot</th></tr>\n");
    for (Student student : SHORTLIST) {
      rows.append("<tr>");
      for (String cell : cells(student)) {
        rows.append("<td>").append(cell).append("</td>");
      }
      rows.append("</tr>\n");
    }
    return """
    <!DOCTYPE html>
    <html>
    <head>
      <meta charset="utf-8">
      <title>Shortlist title that is not body text</title>
      <style>.secret-style { color: red; }</style>
    </head>
    <body>
    <table width="100%%"><tr><td>
      <p>Dear students,</p>
      <p>The following are <b>shortlisted</b> for&nbsp;Example Corp.</p>
      <table border="1">
      %s</table>
      <div>Waitlist:<br>K3J4H5G6 - Dev Fictional<br><br>Slots may change.</div>
      <script>var hidden = "script-text";</script>
    </td></tr>
    <tr><td>Regards,<br/>Placement Cell</td></tr></table>
    </body>
    </html>
    """
        .formatted(rows);
  }

  static String plainTextEmail() {
    return """
    Dear students,

    The following are shortlisted for Example Corp:
    Z9X8C7V6 - Asha Example
      Q1W2E3R4 - Ravi Sample  \s

    Regards,
    Placement Cell
    """;
  }

  // ---- Damage ------------------------------------------------------------------------------

  /** The first half of a file: what a broken download looks like. */
  static byte[] truncated(byte[] content) {
    return Arrays.copyOf(content, content.length / 2);
  }

  /** Starts like a PDF and then is noise. */
  static byte[] corruptPdf() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes("%PDF-1.7\n".getBytes(UTF_8));
    out.writeBytes(noise(2048));
    return out.toByteArray();
  }

  /** Repeatable binary noise that is no known file type. */
  static byte[] noise(int size) {
    byte[] bytes = new byte[size];
    new Random(42).nextBytes(bytes);
    bytes[0] = 0x00;
    bytes[1] = 0x01;
    return bytes;
  }
}
