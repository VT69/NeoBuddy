package com.neobuddy.processing.extract;

/** The real type of a file, as found by {@link TypeDetector} from its content. */
public enum DetectedType {
  XLSX,
  XLS,
  CSV,
  PDF,
  DOCX,
  HTML,
  PLAIN_TEXT,
  UNKNOWN
}
