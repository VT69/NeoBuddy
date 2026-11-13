package com.neobuddy.web.auth;

import com.neobuddy.subscriber.RegistrationRejectedException;
import com.neobuddy.subscriber.SubscriberService;
import com.neobuddy.subscriber.SubscriberView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The consent step between Google sign-in and the account. Nothing about the student is stored
 * until they accept; accepting creates the account with {@code consented_at} = now and turns the
 * session into a normal USER session.
 */
@Controller
class ConsentController {

  private final SubscriberService subscribers;
  private final SecurityContextRepository contexts;

  ConsentController(SubscriberService subscribers, SecurityContextRepository contexts) {
    this.subscribers = subscribers;
    this.contexts = contexts;
  }

  @GetMapping("/consent")
  String show(@AuthenticationPrincipal NeoBuddyUser user, Model model) {
    model.addAttribute("email", user.getEmail());
    model.addAttribute("name", user.getFullName());
    return "consent";
  }

  @PostMapping("/consent")
  String accept(
      @AuthenticationPrincipal NeoBuddyUser user,
      @RequestParam(name = "agree", defaultValue = "false") boolean agree,
      OAuth2AuthenticationToken authentication,
      HttpServletRequest request,
      HttpServletResponse response,
      Model model) {
    if (!agree) {
      model.addAttribute("email", user.getEmail());
      model.addAttribute("name", user.getFullName());
      model.addAttribute("error", "Tick the box to agree, or sign out if you do not want to.");
      return "consent";
    }
    SubscriberView account;
    try {
      account = subscribers.registerFromOidc(user.claims());
    } catch (RegistrationRejectedException e) {
      return "redirect:/sign-in-problem?reason=" + SignInErrors.codeOf(e.reason());
    }
    NeoBuddyUser upgraded = user.withAccount(account);
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new OAuth2AuthenticationToken(
            upgraded,
            upgraded.getAuthorities(),
            authentication.getAuthorizedClientRegistrationId()));
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
    return "redirect:/onboarding";
  }
}
