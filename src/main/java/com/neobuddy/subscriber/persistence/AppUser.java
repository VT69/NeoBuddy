package com.neobuddy.subscriber.persistence;

import com.neobuddy.subscriber.UserRole;
import com.neobuddy.subscriber.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** A student (or admin) account. Belongs to exactly one college. */
@Entity
@Table(name = "app_user")
public class AppUser {

  /**
   * Prefix of the placeholder stored in {@code google_sub} for an account created before its owner
   * ever signed in (the bootstrap admin). The first Google sign-in with the same email replaces it.
   */
  public static final String PENDING_SUB_PREFIX = "pending:";

  @Id private UUID id;

  @Column(name = "college_id", nullable = false, updatable = false)
  private UUID collegeId;

  @Column(name = "google_sub", nullable = false, unique = true)
  private String googleSub;

  /** Always stored lower-case, so lookups never depend on how citext compares to a parameter. */
  @Column(nullable = false, unique = true, columnDefinition = "citext")
  private String email;

  @Column(name = "display_name")
  private String displayName;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private UserRole role;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private UserStatus status;

  @Column(name = "consented_at", nullable = false)
  private Instant consentedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AppUser() {}

  public AppUser(
      UUID collegeId,
      String googleSub,
      String email,
      String displayName,
      UserRole role,
      Instant now) {
    this.id = UUID.randomUUID();
    this.collegeId = Objects.requireNonNull(collegeId, "collegeId");
    this.googleSub = Objects.requireNonNull(googleSub, "googleSub");
    this.email = email.strip().toLowerCase(Locale.ROOT);
    this.displayName = displayName;
    this.role = Objects.requireNonNull(role, "role");
    this.status = UserStatus.ACTIVE;
    this.consentedAt = Objects.requireNonNull(now, "now");
    this.createdAt = now;
  }

  public static String pendingSubFor(String email) {
    return PENDING_SUB_PREFIX + email.strip().toLowerCase(Locale.ROOT);
  }

  public boolean awaitsFirstSignIn() {
    return googleSub.startsWith(PENDING_SUB_PREFIX);
  }

  public void linkGoogleAccount(String subject) {
    this.googleSub = Objects.requireNonNull(subject, "subject");
  }

  public void rename(String newDisplayName) {
    this.displayName = newDisplayName;
  }

  public void changeRole(UserRole newRole) {
    this.role = Objects.requireNonNull(newRole, "newRole");
  }

  public void changeStatus(UserStatus newStatus) {
    this.status = Objects.requireNonNull(newStatus, "newStatus");
  }

  public UUID getId() {
    return id;
  }

  public UUID getCollegeId() {
    return collegeId;
  }

  public String getGoogleSub() {
    return googleSub;
  }

  public String getEmail() {
    return email;
  }

  public String getDisplayName() {
    return displayName;
  }

  public UserRole getRole() {
    return role;
  }

  public UserStatus getStatus() {
    return status;
  }

  public Instant getConsentedAt() {
    return consentedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
