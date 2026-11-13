package com.neobuddy.subscriber;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A subscriber as other modules may see them: profile, identifiers and channels. Channel addresses
 * are deliberately absent.
 */
public record SubscriberView(
    UUID id,
    UUID collegeId,
    String email,
    String displayName,
    UserRole role,
    UserStatus status,
    Instant consentedAt,
    List<IdentifierView> identifiers,
    List<ChannelView> channels) {

  public SubscriberView {
    identifiers = List.copyOf(identifiers);
    channels = List.copyOf(channels);
  }

  /** Ids and state only: no email, name or identifiers. */
  @Override
  public String toString() {
    return "SubscriberView[id=" + id + ", role=" + role + ", status=" + status + "]";
  }

  /**
   * @param value the normalised value, shown only to its owner
   */
  public record IdentifierView(UUID id, IdentifierType type, String value) {

    @Override
    public String toString() {
      return "IdentifierView[id=" + id + ", type=" + type + "]";
    }
  }

  /**
   * @param verified true once the student proved the channel is theirs; only then are alerts sent
   * @param enabled false while the student has switched this channel off
   */
  public record ChannelView(UUID id, ChannelType type, boolean verified, boolean enabled) {}
}
