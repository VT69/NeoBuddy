package com.neobuddy.processing.extract;

import com.neobuddy.ingestion.AttachmentRef;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

/**
 * Reads .docx documents in body order: one chunk per non-blank paragraph, located as {@code "P4"}
 * (4th paragraph), and one chunk per table row, located as {@code "T2!R5"} (table 2, row 5).
 * Headers, footers and comments are ignored.
 */
@Component
public class DocxExtractor implements ContentExtractor {

  @Override
  public boolean supports(DetectedType type) {
    return type == DetectedType.DOCX;
  }

  @Override
  public Stream<TextChunk> extract(InputStream in, AttachmentRef attachment)
      throws ExtractionException {
    try (XWPFDocument document = new XWPFDocument(in)) {
      String source = attachment.filename();
      List<TextChunk> chunks = new ArrayList<>();
      int paragraphs = 0;
      int tables = 0;
      for (IBodyElement element : document.getBodyElements()) {
        if (element instanceof XWPFParagraph paragraph) {
          paragraphs++;
          add(chunks, source, "P" + paragraphs, TextSupport.clean(paragraph.getText()));
        } else if (element instanceof XWPFTable table) {
          tables++;
          int rows = 0;
          for (XWPFTableRow row : table.getRows()) {
            rows++;
            List<String> cells = row.getTableCells().stream().map(XWPFTableCell::getText).toList();
            add(chunks, source, "T" + tables + "!R" + rows, TextSupport.joinCells(cells));
          }
        }
      }
      return chunks.stream();
    } catch (IOException | RuntimeException e) {
      throw new ExtractionException("Could not read the Word document", e);
    }
  }

  private static void add(List<TextChunk> chunks, String source, String location, String text) {
    if (!text.isEmpty()) {
      chunks.add(new TextChunk(source, location, text));
    }
  }
}
