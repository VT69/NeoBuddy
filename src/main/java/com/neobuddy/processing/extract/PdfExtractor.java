package com.neobuddy.processing.extract;

import com.neobuddy.ingestion.AttachmentRef;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Reads text PDFs: one chunk per non-blank line, located as {@code "p3:L17"} (page 3, line 17 of
 * that page's text). Text is sorted by position so a table row stays on one line. Scanned PDFs have
 * no text layer and yield no chunks; password-protected PDFs fail.
 */
@Component
public class PdfExtractor implements ContentExtractor {

  @Override
  public boolean supports(DetectedType type) {
    return type == DetectedType.PDF;
  }

  @Override
  public Stream<TextChunk> extract(InputStream in, AttachmentRef attachment)
      throws ExtractionException {
    try (PDDocument document = Loader.loadPDF(in.readAllBytes())) {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      List<TextChunk> chunks = new ArrayList<>();
      for (int page = 1; page <= document.getNumberOfPages(); page++) {
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        String[] lines = stripper.getText(document).split("\\R");
        for (int i = 0; i < lines.length; i++) {
          String text = TextSupport.clean(lines[i]);
          if (!text.isEmpty()) {
            chunks.add(new TextChunk(attachment.filename(), "p" + page + ":L" + (i + 1), text));
          }
        }
      }
      return chunks.stream();
    } catch (IOException | RuntimeException e) {
      throw new ExtractionException("Could not read the PDF", e);
    }
  }
}
