package com.neobuddy.processing.classify;

import com.neobuddy.ingestion.RawEmail;
import com.neobuddy.processing.Classification;

/** Decides what kind of placement email this is. A strategy: rules today, maybe a model later. */
public interface EmailClassifier {

  /**
   * @param collegeSlug the college the email belongs to, so per-college rules can apply
   * @param email the email; only its subject and bodies are read
   */
  Classification classify(String collegeSlug, RawEmail email);
}
