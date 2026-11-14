package com.neobuddy.ingestion.source;

import com.neobuddy.shared.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A connected inbox that placement mail is read from ({@code mail_source}). Belongs to one college.
 * The refresh token is encrypted at rest. {@code lastHistoryId} is the Gmail history cursor: null
 * until the first poll, which sets it to "now" so old mail is never ingested.
 */
@Entity
@Table(name = "mail_source")
public class SourceInbox {

  @Id private UUID id;

  @Column(name = "college_id", nullable = false, updatable = false)
  private UUID collegeId;

  /** Always stored lower-case. */
  @Column(nullable = false, unique = true, columnDefinition = "citext")
  private String email;

  @Convert(converter = EncryptedStringConverter.class)
  @Column(name = "refresh_token_enc", nullable = false)
  private String refreshToken;

  @Column(name = "last_history_id")
  private Long lastHistoryId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SourceStatus status;

  @Column(name = "last_synced_at")
  private Instant lastSyncedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "oauth_client", nullable = false)
  private OAuthClient oauthClient;

  protected SourceInbox() {}

  public SourceInbox(UUID collegeId, String email, String refreshToken) {
    this(collegeId, email, refreshToken, OAuthClient.DESKTOP);
  }

  public SourceInbox(UUID collegeId, String email, String refreshToken, OAuthClient oauthClient) {
    this.id = UUID.randomUUID();
    this.collegeId = Objects.requireNonNull(collegeId, "collegeId");
    this.email = email.strip().toLowerCase(Locale.ROOT);
    this.refreshToken = Objects.requireNonNull(refreshToken, "refreshToken");
    this.oauthClient = Objects.requireNonNull(oauthClient, "oauthClient");
    this.status = SourceStatus.ACTIVE;
  }

  /** A new token means the inbox was authorised again, so it is polled again. */
  public void reconnect(String newRefreshToken) {
    reconnect(newRefreshToken, oauthClient);
  }

  /** As {@link #reconnect(String)}, with a token issued by the given client. */
  public void reconnect(String newRefreshToken, OAuthClient client) {
    if (!refreshToken.equals(newRefreshToken) || status != SourceStatus.ACTIVE) {
      this.refreshToken = Objects.requireNonNull(newRefreshToken, "newRefreshToken");
      this.oauthClient = Objects.requireNonNull(client, "client");
      this.status = SourceStatus.ACTIVE;
    }
  }

  /**
   * Stops polling and forgets the refresh token. The row stays, because ingested emails point to
   * it; connecting the inbox again reactivates it.
   */
  public void disconnect() {
    this.refreshToken = "";
    this.status = SourceStatus.DISABLED;
  }

  /** Moves the cursor forward; it never goes back. */
  public void advanceTo(long historyId, Instant now) {
    if (lastHistoryId == null || historyId > lastHistoryId) {
      this.lastHistoryId = historyId;
    }
    this.lastSyncedAt = now;
  }

  public void markAuthExpired() {
    this.status = SourceStatus.AUTH_EXPIRED;
  }

  public UUID getId() {
    return id;
  }

  public UUID getCollegeId() {
    return collegeId;
  }

  public String getEmail() {
    return email;
  }

  public String getRefreshToken() {
    return refreshToken;
  }

  public Long getLastHistoryId() {
    return lastHistoryId;
  }

  public SourceStatus getStatus() {
    return status;
  }

  public Instant getLastSyncedAt() {
    return lastSyncedAt;
  }

  public OAuthClient getOauthClient() {
    return oauthClient;
  }
}
