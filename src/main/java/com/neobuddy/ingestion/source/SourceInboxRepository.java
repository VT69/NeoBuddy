package com.neobuddy.ingestion.source;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourceInboxRepository extends JpaRepository<SourceInbox, UUID> {

  /**
   * Ids only, so that one row that cannot be read (its token does not decrypt) cannot stop the
   * others from being listed.
   */
  @Query("select s.id from SourceInbox s where s.status = :status")
  List<UUID> findIdsByStatus(@Param("status") SourceStatus status);

  /** A source without its token, so listing never decrypts anything. */
  interface Summary {
    UUID getId();

    UUID getCollegeId();

    String getEmail();

    SourceStatus getStatus();

    java.time.Instant getLastSyncedAt();

    Long getLastHistoryId();

    OAuthClient getOauthClient();
  }

  /** Every source, without tokens: a token that no longer decrypts cannot break the listing. */
  @Query(
      "select s.id as id, s.collegeId as collegeId, s.email as email, s.status as status,"
          + " s.lastSyncedAt as lastSyncedAt, s.lastHistoryId as lastHistoryId,"
          + " s.oauthClient as oauthClient from SourceInbox s")
  List<Summary> findSummaries();

  /**
   * @param email must already be lower-case; emails are stored that way
   */
  Optional<SourceInbox> findByEmail(String email);
}
