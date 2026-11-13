package com.neobuddy.processing.extract;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Reads plain text (bodies and .txt attachments): one chunk per non-blank line, located as {@code
 * "L12"} (the 12th line, counting blank ones). Text is read as UTF-8, or UTF-16 when it carries a
 * byte order mark; undecodable bytes are replaced, never fatal.
 */
@Component
public class PlainTextExtractor implements ContentExtractor {

  @Override
  public boolean supports(DetectedType type) {
    return type == DetectedType.PLAIN_TEXT;
  }

  @Override
  public Stream<TextChunk> extract(InputStream in, AttachmentRef attachment)
      throws ExtractionException {
    String[] lines;
    try {
      lines = TextSupport.decode(in.readAllBytes()).split("\\R");
    } catch (IOException e) {
      throw new ExtractionException("Could not read the text", e);
    }
    List<TextChunk> chunks = new ArrayList<>();
    for (int i = 0; i < lines.length; i++) {
      String text = TextSupport.clean(lines[i]);
      if (!text.isEmpty()) {
        chunks.add(new TextChunk(attachment.filename(), "L" + (i + 1), text));
      }
    }
    return chunks.stream();
  }
}
