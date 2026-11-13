package com.neobuddy.processing.extract;

import com.neobuddy.ingestion.AttachmentRef;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

/**
 * Reads HTML email bodies and HTML attachments.
 *
 * <ul>
 *   <li>Each data row ({@code <tr>} with no table nested inside it) is one chunk: its cells joined
 *       with {@code " | "}, located as {@code "T2!R5"} (2nd data table, 5th row of it).
 *   <li>All other text is one chunk per block element or {@code <br>}-separated line, located as
 *       {@code "B7"} (7th text block).
 * </ul>
 *
 * <p>Rows that only hold a nested table are layout, not data, and are walked into instead of being
 * emitted. Otherwise an email laid out with tables would produce one chunk holding every student's
 * row. Scripts, styles and the document head are ignored. Nothing is fetched or run.
 */
@Component
public class HtmlBodyExtractor implements ContentExtractor {

  private static final Set<String> IGNORED = Set.of("script", "style", "noscript", "template");
  private static final Set<String> ROW_CELLS = Set.of("td", "th");
  private static final Set<String> STRUCTURAL =
      Set.of("table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption", "li", "dt", "dd");

  @Override
  public boolean supports(DetectedType type) {
    return type == DetectedType.HTML;
  }

  @Override
  public Stream<TextChunk> extract(InputStream in, AttachmentRef attachment)
      throws ExtractionException {
    try {
      // A null charset lets jsoup use the byte order mark or <meta charset>, else UTF-8.
      Document document = Jsoup.parse(in, null, "");
      Walk walk = new Walk(attachment.filename());
      walk.visit(document.body());
      return walk.chunks.stream();
    } catch (IOException | RuntimeException e) {
      throw new ExtractionException("Could not read the HTML", e);
    }
  }

  private static boolean breaksText(Element element) {
    return element.isBlock() || STRUCTURAL.contains(element.normalName());
  }

  private static final class Walk {

    private final String source;
    private final List<TextChunk> chunks = new ArrayList<>();
    private final Map<Element, Integer> tableNumbers = new IdentityHashMap<>();
    private final Map<Element, Integer> rowCounts = new IdentityHashMap<>();
    private int blocks;

    private Walk(String source) {
      this.source = source;
    }

    private void visit(Element element) {
      if (IGNORED.contains(element.normalName())) {
        return;
      }
      if (element.normalName().equals("tr") && element.selectFirst("table") == null) {
        row(element);
        return;
      }
      StringBuilder inline = new StringBuilder();
      for (Node child : element.childNodes()) {
        if (child instanceof TextNode text) {
          inline.append(text.getWholeText());
        } else if (child instanceof Element childElement) {
          if (childElement.normalName().equals("br")) {
            block(inline);
          } else if (isInline(childElement)) {
            inline.append(childElement.text());
          } else {
            block(inline);
            visit(childElement);
          }
        }
      }
      block(inline);
    }

    private static boolean isInline(Element element) {
      if (IGNORED.contains(element.normalName())) {
        return false;
      }
      return !breaksText(element)
          && element.getAllElements().stream().noneMatch(HtmlBodyExtractor::breaksText)
          && element.selectFirst("br") == null;
    }

    private void row(Element row) {
      Element table = row.closest("table");
      int rowNumber = rowCounts.merge(table, 1, Integer::sum);
      List<String> cells =
          row.children().stream()
              .filter(cell -> ROW_CELLS.contains(cell.normalName()))
              .map(Element::text)
              .toList();
      String text = TextSupport.joinCells(cells);
      if (!text.isEmpty()) {
        int tableNumber = tableNumbers.computeIfAbsent(table, t -> tableNumbers.size() + 1);
        chunks.add(new TextChunk(source, "T" + tableNumber + "!R" + rowNumber, text));
      }
    }

    private void block(StringBuilder inline) {
      String text = TextSupport.clean(inline.toString());
      inline.setLength(0);
      if (!text.isEmpty()) {
        blocks++;
        chunks.add(new TextChunk(source, "B" + blocks, text));
      }
    }
  }
}
