package com.neobuddy.processing;

import java.util.UUID;

/**
 * Creates the synthetic match behind a test alert: a test email and a match flagged as a test, so
 * the alert goes through the same delivery pipeline as a real one.
 */
public interface TestMatches {

  String SUBJECT = "NeoBuddy test alert";
  String SNIPPET = "This is a test. Real alerts show your own row from the placement email here.";

  /**
   * Joins the caller's transaction.
   *
   * @param identifierId one of the user's identifiers, which the match is recorded against
   */
  StoredMatch createTestMatch(UUID collegeId, UUID userId, UUID identifierId);
}
