package com.neobuddy.processing;

import java.util.List;
import java.util.UUID;

/**
 * Port through which processing loads every identifier it should match against. The subscriber
 * module implements it in T5; until then no bean exists and the index stays empty.
 */
public interface IdentifierSource {

  /** Every identifier of every subscriber that should currently receive alerts. */
  List<IndexedIdentifier> loadAll();

  /**
   * One identifier to index.
   *
   * @param collegeId the college it was registered in; matching never crosses colleges
   * @param userId its owner
   * @param identifierId its id
   * @param normalizedValue the value as stored: trimmed, spaces and hyphens stripped
   */
  record IndexedIdentifier(UUID collegeId, UUID userId, UUID identifierId, String normalizedValue) {

    /** Omits the value: identifiers must never reach a log. */
    @Override
    public String toString() {
      return "IndexedIdentifier[collegeId=" + collegeId + ", identifierId=" + identifierId + "]";
    }
  }
}
