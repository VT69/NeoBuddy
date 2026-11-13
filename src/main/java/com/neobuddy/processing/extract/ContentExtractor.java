package com.neobuddy.processing.extract;

import com.neobuddy.ingestion.AttachmentRef;
import java.io.InputStream;
import java.util.stream.Stream;

/** Extracts text chunks from one file format. Implementations are stateless Spring beans. */
public interface ContentExtractor {

  boolean supports(DetectedType type);

  /**
   * Reads the whole stream and returns its chunks in document order. An empty but valid file yields
   * an empty stream.
   *
   * @param in the file content; the caller closes it
   * @param attachment used for the chunk source (filename) and the reported size
   * @throws ExtractionException if the content is not a readable file of a supported type
   */
  Stream<TextChunk> extract(InputStream in, AttachmentRef attachment) throws ExtractionException;
}
