package com.neobuddy.subscriber.persistence;

import com.neobuddy.subscriber.IdentifierType;
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
 * Something a student is listed by in placement emails. Unique per college, type and value, so one
 * NeoID has one owner within a college while another college may reuse the same value.
 */
@Entity
@Table(name = "identifier")
public class Identifier {

  @Id private UUID id;

  @Column(name = "college_id", nullable = false, updatable = false)
  private UUID collegeId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private IdentifierType type;

  @Column(name = "normalized_value", nullable = false, updatable = false)
  private String normalizedValue;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Identifier() {}

  public Identifier(
      UUID collegeId, UUID userId, IdentifierType type, String normalizedValue, Instant now) {
    this.id = UUID.randomUUID();
    this.collegeId = Objects.requireNonNull(collegeId, "collegeId");
    this.userId = Objects.requireNonNull(userId, "userId");
    this.type = Objects.requireNonNull(type, "type");
    this.normalizedValue = Objects.requireNonNull(normalizedValue, "normalizedValue");
    this.createdAt = Objects.requireNonNull(now, "now");
  }

  public UUID getId() {
    return id;
  }

  public UUID getCollegeId() {
    return collegeId;
  }

  public UUID getUserId() {
    return userId;
  }

  public IdentifierType getType() {
    return type;
  }

  public String getNormalizedValue() {
    return normalizedValue;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
