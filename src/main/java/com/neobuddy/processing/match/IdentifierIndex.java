package com.neobuddy.processing.match;

import java.util.Set;
import java.util.UUID;

/** Looks up which subscribers own a token, always within one college. */
public interface IdentifierIndex {

  /**
   * @param collegeId the college of the email being matched; other colleges are never consulted
   * @param token a token exactly as {@link Tokenizer} emits it
   * @return the identifiers equal to the token in that college, or an empty set
   */
  Set<IdentifierRef> lookup(UUID collegeId, String token);

  /** Reloads every identifier and swaps the new index in atomically. */
  void rebuild();
}
