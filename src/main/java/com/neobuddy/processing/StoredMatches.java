package com.neobuddy.processing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read access to the stored matches for other modules. The {@code match_hit} table belongs to
 * processing. Inside a transaction it also sees the matches that transaction has just written.
 */
public interface StoredMatches {

  /** Every stored match of one email, at most one per user. */
  List<StoredMatch> ofEmail(String rfcMessageId);

  Optional<StoredMatch> find(UUID matchId);
}
