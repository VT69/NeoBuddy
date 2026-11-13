package com.neobuddy.subscriber.persistence;

import com.neobuddy.shared.crypto.EncryptedStringConverter;
import com.neobuddy.subscriber.ChannelType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One way to reach a user. At most one per type per user. The address is encrypted at rest, and no
 * alert may be sent until {@link #isVerified()}.
 */
@Entity
@Table(name = "channel")
public class Channel {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private ChannelType type;

  @Convert(converter = EncryptedStringConverter.class)
  @Column(name = "address_enc", nullable = false)
  private String address;

  @Column(name = "verified_at")
  private Instant verifiedAt;

  @Column(nullable = false)
  private boolean enabled;

  protected Channel() {}

  public Channel(UUID userId, ChannelType type, String address) {
    this.id = UUID.randomUUID();
    this.userId = Objects.requireNonNull(userId, "userId");
    this.type = Objects.requireNonNull(type, "type");
    this.address = Objects.requireNonNull(address, "address");
    this.enabled = true;
  }

  /** Points the channel somewhere else. A new address has to be verified again. */
  public void changeAddress(String newAddress) {
    if (!address.equals(newAddress)) {
      this.address = Objects.requireNonNull(newAddress, "newAddress");
      this.verifiedAt = null;
    }
  }

  /** Marks the channel verified, keeping the original time if it already was. */
  public void markVerified(Instant now) {
    if (verifiedAt == null) {
      this.verifiedAt = Objects.requireNonNull(now, "now");
    }
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isVerified() {
    return verifiedAt != null;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public ChannelType getType() {
    return type;
  }

  public String getAddress() {
    return address;
  }

  public Instant getVerifiedAt() {
    return verifiedAt;
  }

  public boolean isEnabled() {
    return enabled;
  }
}
