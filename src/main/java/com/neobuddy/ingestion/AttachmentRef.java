package com.neobuddy.ingestion;

/**
 * What the sender says about an attachment. All three values are untrusted: the real type is
 * detected from the content and the real size is enforced while reading.
 *
 * @param filename name as sent, may be null or blank
 * @param declaredMimeType MIME type as sent, may be null or blank
 * @param sizeBytes size as reported by the mail source
 */
public record AttachmentRef(String filename, String declaredMimeType, long sizeBytes) {

  private static final int MAX_LOG_LENGTH = 120;

  /** The filename made safe for a log line: no control characters, bounded length. */
  public String filenameForLog() {
    if (filename == null || filename.isBlank()) {
      return "(unnamed)";
    }
    String clean = filename.replaceAll("\\p{Cntrl}", "?").strip();
    return clean.length() <= MAX_LOG_LENGTH ? clean : clean.substring(0, MAX_LOG_LENGTH) + "...";
  }
}
