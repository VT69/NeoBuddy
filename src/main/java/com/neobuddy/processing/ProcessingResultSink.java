package com.neobuddy.processing;

/**
 * Port for storing the outcome of processing an email (the {@code processed_email} and {@code
 * match_hit} rows). Implemented in T6; until then no bean exists and nothing is stored.
 */
public interface ProcessingResultSink {

  void save(ProcessingResult result);
}
