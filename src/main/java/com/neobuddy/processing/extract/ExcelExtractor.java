package com.neobuddy.processing.extract;

import com.github.pjfanning.xlsx.StreamingReader;
import com.neobuddy.ingestion.AttachmentRef;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/**
 * Reads .xlsx and .xls workbooks: every sheet, one chunk per non-empty row, located as {@code
 * "Sheet1!R42"} (the row number as Excel shows it).
 *
 * <p>Cells are rendered as Excel displays them, so a numeric registration number stays {@code
 * 2021000123} and not {@code 2.021000123E9}; numbers Excel itself would show in scientific notation
 * are written out in full. Formulas are never evaluated; their last saved value is used. Large
 * .xlsx files go through the streaming reader to keep memory flat. POI's zip-bomb guard ({@code
 * ZipSecureFile}) is left at its defaults.
 */
@Component
public class ExcelExtractor implements ContentExtractor {

  private static final Pattern SCIENTIFIC = Pattern.compile("-?\\d(\\.\\d+)?E[+-]?\\d+");

  private final long streamingThresholdBytes;

  public ExcelExtractor(ExtractionProperties properties) {
    this.streamingThresholdBytes = properties.excelStreamingThreshold().toBytes();
  }

  @Override
  public boolean supports(DetectedType type) {
    return type == DetectedType.XLSX || type == DetectedType.XLS;
  }

  @Override
  public Stream<TextChunk> extract(InputStream in, AttachmentRef attachment)
      throws ExtractionException {
    try {
      InputStream content = FileMagic.prepareToCheckMagic(in);
      boolean ooxml = FileMagic.valueOf(content) == FileMagic.OOXML;
      boolean streaming = ooxml && attachment.sizeBytes() > streamingThresholdBytes;
      try (Workbook workbook =
          streaming ? openStreaming(content) : WorkbookFactory.create(content)) {
        return rows(workbook, attachment.filename()).stream();
      }
    } catch (IOException | RuntimeException e) {
      throw new ExtractionException("Could not read the spreadsheet", e);
    }
  }

  /**
   * The cell as Excel displays it, with one exception: Excel shows long numbers in a General cell
   * in scientific notation (212345678901 becomes 2.12346E+11), which would destroy a registration
   * number. Those are written out in full instead.
   */
  private static String display(Cell cell, DataFormatter formatter) {
    String shown = formatter.formatCellValue(cell);
    if (!SCIENTIFIC.matcher(shown).matches()) {
      return shown;
    }
    CellType type =
        cell.getCellType() == CellType.FORMULA
            ? cell.getCachedFormulaResultType()
            : cell.getCellType();
    if (type != CellType.NUMERIC) {
      return shown;
    }
    return BigDecimal.valueOf(cell.getNumericCellValue()).toPlainString();
  }

  private static Workbook openStreaming(InputStream content) {
    return StreamingReader.builder().rowCacheSize(100).bufferSize(4096).open(content);
  }

  private static List<TextChunk> rows(Workbook workbook, String source) {
    DataFormatter formatter = new DataFormatter(Locale.ENGLISH);
    formatter.setUseCachedValuesForFormulaCells(true);
    List<TextChunk> chunks = new ArrayList<>();
    for (Sheet sheet : workbook) {
      for (Row row : sheet) {
        List<String> cells = new ArrayList<>();
        for (Cell cell : row) {
          cells.add(display(cell, formatter));
        }
        String text = TextSupport.joinCells(cells);
        if (!text.isEmpty()) {
          String location = sheet.getSheetName() + "!R" + (row.getRowNum() + 1);
          chunks.add(new TextChunk(source, location, text));
        }
      }
    }
    return chunks;
  }
}
