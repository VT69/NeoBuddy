package com.neobuddy.processing.classify;

import com.neobuddy.ingestion.RawEmail;
import com.neobuddy.processing.Classification;
import com.neobuddy.shared.college.College;

/** Decides what kind of placement email this is. A strategy: rules today, maybe a model later. */
public interface EmailClassifier {

  /**
   * @param collegeSlug the college the email belongs to, so per-college rules can apply
   * @param email the email; only its subject and bodies are read
   */
  Classification classify(String collegeSlug, RawEmail email);

  /**
   * Classifies with the college's own keyword overrides from the admin console applied on top.
   * Implementations without per-college storage ignore them.
   */
  default Classification classify(College college, RawEmail email) {
    return classify(college.getSlug(), email);
  }
}
