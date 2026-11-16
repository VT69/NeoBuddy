package com.neobuddy.web.pages;

import com.neobuddy.web.auth.NeoBuddyUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * What every page needs: whether to show the signed-in navigation, and which outside services run
 * NeoBuddy here, so the privacy policy and consent page name the right ones.
 */
@ControllerAdvice
class LayoutModel {

  private final String hosting;
  private final String emailProvider;

  LayoutModel(
      @Value("${neobuddy.web.hosting:aws}") String hosting,
      @Value("${neobuddy.channels.email.provider:ses}") String emailProvider) {
    this.hosting = hosting.strip();
    this.emailProvider = emailProvider.strip();
  }

  /** {@code aws} or {@code render}. */
  @ModelAttribute("hosting")
  String hosting() {
    return hosting;
  }

  /** {@code ses}, {@code brevo} or {@code log}. */
  @ModelAttribute("emailProvider")
  String emailProvider() {
    return emailProvider;
  }

  @ModelAttribute("signedIn")
  boolean signedIn(@AuthenticationPrincipal NeoBuddyUser user) {
    return user != null && !user.needsConsent();
  }

  @ModelAttribute("pendingConsent")
  boolean pendingConsent(@AuthenticationPrincipal NeoBuddyUser user) {
    return user != null && user.needsConsent();
  }
}
