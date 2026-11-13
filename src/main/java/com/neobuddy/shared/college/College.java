package com.neobuddy.shared.college;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A tenant. Every tenant-owned row carries the id of its college as {@code college_id}. */
@Entity
@Table(name = "college")
public class College {

  public static final String DEFAULT_NEO_ID_PATTERN = "^[A-Z0-9]{8}$";

  @Id private UUID id;

  @Column(nullable = false, unique = true)
  private String slug;

  @Column(nullable = false)
  private String name;

  @Column(name = "allowed_email_domains", nullable = false, columnDefinition = "text[]")
  private List<String> allowedEmailDomains;

  @Column(name = "sender_allowlist", nullable = false, columnDefinition = "text[]")
  private List<String> senderAllowlist;

  @Column(name = "neo_id_pattern", nullable = false)
  private String neoIdPattern;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private CollegeStatus status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected College() {}

  public College(
      UUID id,
      String slug,
      String name,
      List<String> allowedEmailDomains,
      List<String> senderAllowlist,
      String neoIdPattern,
      CollegeStatus status,
      Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.slug = Objects.requireNonNull(slug, "slug");
    this.name = Objects.requireNonNull(name, "name");
    this.allowedEmailDomains = List.copyOf(allowedEmailDomains);
    this.senderAllowlist = List.copyOf(senderAllowlist);
    this.neoIdPattern = Objects.requireNonNull(neoIdPattern, "neoIdPattern");
    this.status = Objects.requireNonNull(status, "status");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  /**
   * Replaces the settings an admin can edit. Identity (id, slug), status and creation time stay.
   */
  public void updateSettings(
      String name,
      List<String> allowedEmailDomains,
      List<String> senderAllowlist,
      String neoIdPattern) {
    this.name = Objects.requireNonNull(name, "name");
    this.allowedEmailDomains = List.copyOf(allowedEmailDomains);
    this.senderAllowlist = List.copyOf(senderAllowlist);
    this.neoIdPattern = Objects.requireNonNull(neoIdPattern, "neoIdPattern");
  }

  /** True while students of this college may sign up and receive alerts. */
  public boolean acceptsSubscribers() {
    return status == CollegeStatus.ACTIVE || status == CollegeStatus.PILOT;
  }

  public UUID getId() {
    return id;
  }

  public String getSlug() {
    return slug;
  }

  public String getName() {
    return name;
  }

  public List<String> getAllowedEmailDomains() {
    return List.copyOf(allowedEmailDomains);
  }

  public List<String> getSenderAllowlist() {
    return List.copyOf(senderAllowlist);
  }

  public String getNeoIdPattern() {
    return neoIdPattern;
  }

  public CollegeStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public boolean equals(Object o) {
    return this == o || (o instanceof College other && id.equals(other.id));
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }
}
