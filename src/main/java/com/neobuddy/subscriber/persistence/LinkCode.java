package com.neobuddy.subscriber.persistence;

import com.neobuddy.subscriber.ChannelType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A short-lived code that proves a channel belongs to a user: a Discord {@code /link} code or an
 * email one-time password. The primary key is the code as stored, which for one-time passwords is a
 * hash, never the code itself. Issuing and checking codes comes with the channel tasks.
 */
@Entity
@Table(name = "link_code")
public class LinkCode {

  @Id private String code;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "channel_type", nullable = false, updatable = false)
  private ChannelType channelType;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  protected LinkCode() {}

  public LinkCode(String code, UUID userId, ChannelType channelType, Instant expiresAt) {
    this.code = Objects.requireNonNull(code, "code");
    this.userId = Objects.requireNonNull(userId, "userId");
    this.channelType = Objects.requireNonNull(channelType, "channelType");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
  }

  public void recordAttempt() {
    attempts++;
  }

  public boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }

  public String getCode() {
    return code;
  }

  public UUID getUserId() {
    return userId;
  }

  public ChannelType getChannelType() {
    return channelType;
  }

  public int getAttempts() {
    return attempts;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }
}
