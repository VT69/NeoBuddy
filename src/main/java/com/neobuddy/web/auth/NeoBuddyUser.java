package com.neobuddy.web.auth;

import com.neobuddy.subscriber.OidcClaims;
import com.neobuddy.subscriber.SubscriberView;
import com.neobuddy.subscriber.UserRole;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/**
 * The signed-in Google user, kept in the session. Either a NeoBuddy account (role USER, and ADMIN
 * for admins) or a student of a supported college who has not consented yet (role PENDING_CONSENT,
 * no account, nothing stored).
 */
public class NeoBuddyUser extends DefaultOidcUser {

  @Serial private static final long serialVersionUID = 1L;

  public static final String ROLE_USER = "ROLE_USER";
  public static final String ROLE_ADMIN = "ROLE_ADMIN";
  public static final String ROLE_PENDING_CONSENT = "ROLE_PENDING_CONSENT";

  private final UUID userId;

  private NeoBuddyUser(
      Collection<? extends GrantedAuthority> authorities,
      OidcIdToken idToken,
      OidcUserInfo userInfo,
      UUID userId) {
    super(authorities, idToken, userInfo, "sub");
    this.userId = userId;
  }

  /** A student with an account. */
  public static NeoBuddyUser account(
      SubscriberView account, OidcIdToken idToken, OidcUserInfo userInfo) {
    List<GrantedAuthority> authorities = new ArrayList<>();
    authorities.add(new SimpleGrantedAuthority(ROLE_USER));
    if (account.role() == UserRole.ADMIN) {
      authorities.add(new SimpleGrantedAuthority(ROLE_ADMIN));
    }
    return new NeoBuddyUser(authorities, idToken, userInfo, account.id());
  }

  /** A student who may sign up but has not consented yet. */
  public static NeoBuddyUser pendingConsent(OidcIdToken idToken, OidcUserInfo userInfo) {
    return new NeoBuddyUser(
        List.of(new SimpleGrantedAuthority(ROLE_PENDING_CONSENT)), idToken, userInfo, null);
  }

  /** The same Google sign-in, now with the account created at consent. */
  public NeoBuddyUser withAccount(SubscriberView account) {
    return account(account, getIdToken(), getUserInfo());
  }

  public Optional<UUID> userId() {
    return Optional.ofNullable(userId);
  }

  /**
   * @throws IllegalStateException if there is no account yet; the security rules keep such users
   *     away from everything that calls this
   */
  public UUID requireUserId() {
    if (userId == null) {
      throw new IllegalStateException("No account yet");
    }
    return userId;
  }

  public boolean needsConsent() {
    return userId == null;
  }

  public OidcClaims claims() {
    return claimsOf(this);
  }

  static OidcClaims claimsOf(org.springframework.security.oauth2.core.oidc.user.OidcUser user) {
    return new OidcClaims(
        user.getSubject(),
        user.getEmail(),
        Boolean.TRUE.equals(user.getEmailVerified()),
        user.getFullName(),
        user.getClaimAsString("hd"));
  }

  /** Never prints the email or the token. */
  @Override
  public String toString() {
    return "NeoBuddyUser[userId=" + userId + ", authorities=" + getAuthorities() + "]";
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof NeoBuddyUser other && super.equals(other);
  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }
}
