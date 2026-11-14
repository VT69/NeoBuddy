package com.neobuddy.web.auth;

import com.neobuddy.subscriber.IdentifierType;
import com.neobuddy.subscriber.SubscriberService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** Where a Google sign-in lands, successful or not. */
@Component
public class SignInHandlers implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

  /** Session attribute: the email domain of a refused sign-in, for the request form. */
  public static final String REFUSED_DOMAIN = "neobuddy.refusedDomain";

  private final SubscriberService subscribers;

  public SignInHandlers(SubscriberService subscribers) {
    this.subscribers = subscribers;
  }

  /** New students go to the consent step, students without a NeoID to onboarding. */
  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    String target = "/dashboard";
    if (authentication.getPrincipal() instanceof NeoBuddyUser user) {
      if (user.needsConsent()) {
        target = "/consent";
      } else if (subscribers.get(user.requireUserId()).identifiers().stream()
          .noneMatch(identifier -> identifier.type() == IdentifierType.NEO_ID)) {
        target = "/onboarding";
      }
    }
    response.sendRedirect(request.getContextPath() + target);
  }

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    String code =
        exception instanceof OAuth2AuthenticationException oauth
            ? oauth.getError().getErrorCode()
            : "";
    String target;
    if (SignInErrors.COLLEGE_NOT_SUPPORTED.equals(code)) {
      String domain =
          exception instanceof OAuth2AuthenticationException oauth
              ? oauth.getError().getDescription()
              : null;
      if (domain != null && !domain.isBlank()) {
        request.getSession().setAttribute(REFUSED_DOMAIN, domain);
      }
      target = "/not-supported";
    } else if (SignInErrors.COLLEGE_PAUSED.equals(code)) {
      target = "/not-supported?paused";
    } else {
      target =
          UriComponentsBuilder.fromPath("/sign-in-problem")
              .queryParam("reason", SignInErrors.EXPLAINED.contains(code) ? code : "unknown")
              .build()
              .toUriString();
    }
    response.sendRedirect(request.getContextPath() + target);
  }
}
