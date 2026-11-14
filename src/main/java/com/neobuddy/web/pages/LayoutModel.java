package com.neobuddy.web.pages;

import com.neobuddy.web.auth.NeoBuddyUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** What every page's header needs: whether to show the signed-in navigation. */
@ControllerAdvice
class LayoutModel {

  @ModelAttribute("signedIn")
  boolean signedIn(@AuthenticationPrincipal NeoBuddyUser user) {
    return user != null && !user.needsConsent();
  }

  @ModelAttribute("pendingConsent")
  boolean pendingConsent(@AuthenticationPrincipal NeoBuddyUser user) {
    return user != null && user.needsConsent();
  }
}
