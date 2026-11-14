package com.neobuddy.web.me;

import com.neobuddy.web.auth.NeoBuddyUser;
import com.neobuddy.web.me.MeRequests.AddIdentifier;
import com.neobuddy.web.me.MeRequests.PatchChannel;
import com.neobuddy.web.me.MeRequests.StartEmail;
import com.neobuddy.web.me.MeRequests.VerifyEmail;
import com.neobuddy.web.me.MeViews.EmailVerificationStarted;
import com.neobuddy.web.me.MeViews.Me;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The htmx side of {@code /api/v1/me}: the same paths as {@link MeApiController}, chosen when the
 * request carries {@code HX-Request: true}, taking form fields and answering with the page fragment
 * to swap in. Errors become a message in the page's flash area ({@link MeFragmentProblems}).
 */
@Controller
@RequestMapping(path = "/api/v1/me", headers = "HX-Request=true")
class MeFragmentsController {

  static final String IDENTIFIERS = "fragments/identifiers :: identifiers";
  static final String CHANNELS = "fragments/channels :: channels";
  static final String ALERTS = "fragments/alerts :: alerts";
  static final String FLASH = "fragments/flash :: flash";

  private final MeService me;

  MeFragmentsController(MeService me) {
    this.me = me;
  }

  @DeleteMapping
  ResponseEntity<Void> delete(
      @AuthenticationPrincipal NeoBuddyUser user,
      Authentication authentication,
      HttpServletRequest request,
      HttpServletResponse response) {
    me.deleteAccount(user.requireUserId());
    new SecurityContextLogoutHandler().logout(request, response, authentication);
    return ResponseEntity.noContent().header("HX-Redirect", "/?deleted").build();
  }

  @PostMapping("/identifiers")
  String addIdentifier(
      @AuthenticationPrincipal NeoBuddyUser user,
      @Valid @ModelAttribute AddIdentifier form,
      Model model) {
    me.addIdentifier(user.requireUserId(), form.type(), form.value());
    return identifiers(user, model, "Saved.");
  }

  @DeleteMapping("/identifiers/{id}")
  String removeIdentifier(
      @AuthenticationPrincipal NeoBuddyUser user, @PathVariable UUID id, Model model) {
    me.removeIdentifier(user.requireUserId(), id);
    return identifiers(user, model, "Removed.");
  }

  @PostMapping("/channels/email")
  String startEmail(
      @AuthenticationPrincipal NeoBuddyUser user,
      @Valid @ModelAttribute StartEmail form,
      Model model,
      HttpServletResponse response) {
    EmailVerificationStarted started =
        me.startEmailVerification(user.requireUserId(), form.address());
    if (!started.alreadyVerified()) {
      model.addAttribute("emailCodeSent", true);
      response.setStatus(HttpStatus.ACCEPTED.value());
    }
    return channels(
        user,
        model,
        started.alreadyVerified()
            ? "That address is already verified."
            : "We emailed you a 6-digit code. It works for 10 minutes.");
  }

  @PostMapping("/channels/email/verify")
  String verifyEmail(
      @AuthenticationPrincipal NeoBuddyUser user,
      @Valid @ModelAttribute VerifyEmail form,
      Model model) {
    me.confirmEmail(user.requireUserId(), form.code());
    return channels(user, model, "Email verified. Send yourself a test alert.");
  }

  @PostMapping("/channels/ntfy")
  String createNtfy(
      @AuthenticationPrincipal NeoBuddyUser user, Model model, HttpServletResponse response) {
    model.addAttribute("ntfy", me.createNtfyChannel(user.requireUserId()));
    model.addAttribute("ntfyOpen", true);
    response.setStatus(HttpStatus.CREATED.value());
    return channels(user, model, "Push is set up. Subscribe to your topic in the ntfy app.");
  }

  @PatchMapping("/channels/{id}")
  String patchChannel(
      @AuthenticationPrincipal NeoBuddyUser user,
      @PathVariable UUID id,
      @Valid @ModelAttribute PatchChannel form,
      Model model) {
    me.setChannelEnabled(user.requireUserId(), id, form.enabled());
    return channels(user, model, form.enabled() ? "Channel switched on." : "Channel paused.");
  }

  @PostMapping("/channels/{id}/test")
  String test(
      @AuthenticationPrincipal NeoBuddyUser user,
      @PathVariable UUID id,
      Model model,
      HttpServletResponse response) {
    model.addAttribute("message", me.sendTestAlert(user.requireUserId(), id).message());
    model.addAttribute("kind", "ok");
    response.setStatus(HttpStatus.ACCEPTED.value());
    return FLASH;
  }

  @GetMapping("/alerts")
  String alerts(
      @AuthenticationPrincipal NeoBuddyUser user,
      @RequestParam(name = "page", defaultValue = "0") int page,
      Model model) {
    model.addAttribute("alerts", me.alerts(user.requireUserId(), page));
    return ALERTS;
  }

  private String identifiers(NeoBuddyUser user, Model model, String message) {
    model.addAttribute("me", me.me(user.requireUserId()));
    model.addAttribute("message", message);
    return IDENTIFIERS;
  }

  private String channels(NeoBuddyUser user, Model model, String message) {
    Me current = me.me(user.requireUserId());
    model.addAttribute("me", current);
    if (!model.containsAttribute("ntfy")) {
      model.addAttribute("ntfy", me.existingNtfySetup(current));
    }
    model.addAttribute("message", message);
    return CHANNELS;
  }
}
