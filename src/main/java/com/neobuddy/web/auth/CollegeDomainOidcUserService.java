package com.neobuddy.web.auth;

import com.neobuddy.subscriber.IdentifierNormalizer;
import com.neobuddy.subscriber.OidcClaims;
import com.neobuddy.subscriber.RegistrationRejectedException;
import com.neobuddy.subscriber.SubscriberService;
import com.neobuddy.subscriber.SubscriberView;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Decides, at Google sign-in, who may come in. Google's verified ID token is checked by the
 * standard {@code OidcUserService} first; then the {@code hd} claim (when present) and the email's
 * domain must belong to an ACTIVE or PILOT college ({@link SubscriberService#signIn}).
 *
 * <p>A student with an account becomes a USER (or ADMIN). A student of a supported college without
 * one becomes PENDING_CONSENT: no account is created until they accept the consent notice, which
 * then calls {@link SubscriberService#registerFromOidc}. Anyone else is refused with an error code
 * the failure handler turns into a polite page; the email's domain goes along so the "request your
 * college" form can show it. Log lines carry the reason only.
 */
public class CollegeDomainOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

  private static final Logger log = LoggerFactory.getLogger(CollegeDomainOidcUserService.class);

  private final OAuth2UserService<OidcUserRequest, OidcUser> google;
  private final SubscriberService subscribers;

  public CollegeDomainOidcUserService(
      OAuth2UserService<OidcUserRequest, OidcUser> google, SubscriberService subscribers) {
    this.google = google;
    this.subscribers = subscribers;
  }

  @Override
  public OidcUser loadUser(OidcUserRequest request) {
    OidcUser user = google.loadUser(request);
    OidcClaims claims = NeoBuddyUser.claimsOf(user);
    Optional<SubscriberView> account;
    try {
      account = subscribers.signIn(claims);
    } catch (RegistrationRejectedException e) {
      log.info("Sign-in refused: {}", e.reason());
      String domain =
          claims.email() == null
              ? ""
              : IdentifierNormalizer.domainOf(claims.email().strip().toLowerCase(Locale.ROOT));
      throw new OAuth2AuthenticationException(
          new OAuth2Error(SignInErrors.codeOf(e.reason()), domain, null), e.reason().name());
    }
    return account
        .map(found -> NeoBuddyUser.account(found, user.getIdToken(), user.getUserInfo()))
        .orElseGet(() -> NeoBuddyUser.pendingConsent(user.getIdToken(), user.getUserInfo()));
  }
}
