package com.neobuddy.web.pages;

import com.neobuddy.subscriber.IdentifierType;
import com.neobuddy.subscriber.SubscriberNotFoundException;
import com.neobuddy.web.auth.NeoBuddyUser;
import com.neobuddy.web.me.MeService;
import com.neobuddy.web.me.MeViews.Me;
import com.neobuddy.web.support.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The signed-in student's pages: onboarding wizard, dashboard and settings. Wizard steps 1 and 2
 * are plain form posts (they work without JavaScript); the channel step and the dashboard use the
 * htmx side of {@code /api/v1/me}.
 */
@Controller
class StudentPagesController {

  private static final DateTimeFormatter MEMBER_SINCE =
      DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneId.of("Asia/Kolkata"));

  private final MeService me;

  StudentPagesController(MeService me) {
    this.me = me;
  }

  @GetMapping("/dashboard")
  String dashboard(@AuthenticationPrincipal NeoBuddyUser user, Model model) {
    Me current = me.me(user.requireUserId());
    if (!current.hasNeoId()) {
      return "redirect:/onboarding";
    }
    model.addAttribute("me", current);
    model.addAttribute("ntfy", me.existingNtfySetup(current));
    model.addAttribute("alerts", me.alerts(current.id(), 0));
    return "dashboard";
  }

  /**
   * @param step 1: NeoID, 2: registration number and college email (optional), 3: channels
   */
  @GetMapping("/onboarding")
  String onboarding(
      @AuthenticationPrincipal NeoBuddyUser user,
      @RequestParam(name = "step", defaultValue = "0") int step,
      Model model) {
    Me current = me.me(user.requireUserId());
    int shown = !current.hasNeoId() ? 1 : (step >= 2 && step <= 3 ? step : 2);
    model.addAttribute("me", current);
    model.addAttribute("step", shown);
    model.addAttribute("googleEmail", user.getEmail());
    if (shown == 3) {
      model.addAttribute("ntfy", me.existingNtfySetup(current));
    }
    return "onboarding";
  }

  @PostMapping("/onboarding/neo-id")
  String saveNeoId(
      @AuthenticationPrincipal NeoBuddyUser user,
      @RequestParam(name = "neoId", defaultValue = "") String neoId,
      RedirectAttributes redirect) {
    try {
      me.addIdentifier(user.requireUserId(), IdentifierType.NEO_ID, neoId);
    } catch (RuntimeException e) {
      redirect.addFlashAttribute("errors", List.of(messageOf(e)));
      return "redirect:/onboarding?step=1";
    }
    return "redirect:/onboarding?step=2";
  }

  /** Both fields are optional; whatever is filled in is saved, errors are shown per field. */
  @PostMapping("/onboarding/more-identifiers")
  String saveMoreIdentifiers(
      @AuthenticationPrincipal NeoBuddyUser user,
      @RequestParam(name = "regNo", defaultValue = "") String regNo,
      @RequestParam(name = "collegeEmail", defaultValue = "") String collegeEmail,
      RedirectAttributes redirect) {
    List<String> errors = new ArrayList<>();
    Me current = me.me(user.requireUserId());
    save(user, current, IdentifierType.REG_NO, regNo, "Registration number", errors);
    save(user, current, IdentifierType.COLLEGE_EMAIL, collegeEmail, "College email", errors);
    if (!errors.isEmpty()) {
      redirect.addFlashAttribute("errors", errors);
      return "redirect:/onboarding?step=2";
    }
    return "redirect:/onboarding?step=3";
  }

  @GetMapping("/settings")
  String settings(@AuthenticationPrincipal NeoBuddyUser user, Model model) {
    Me current = me.me(user.requireUserId());
    model.addAttribute("me", current);
    model.addAttribute(
        "memberSince",
        current.consentedAt() == null ? "" : MEMBER_SINCE.format(current.consentedAt()));
    return "settings";
  }

  @PostMapping("/settings/pause-all")
  String pauseAll(@AuthenticationPrincipal NeoBuddyUser user, RedirectAttributes redirect) {
    me.setAllChannelsEnabled(user.requireUserId(), false);
    redirect.addFlashAttribute("notice", "All alerts are paused. Your matches are still recorded.");
    return "redirect:/settings";
  }

  @PostMapping("/settings/resume-all")
  String resumeAll(@AuthenticationPrincipal NeoBuddyUser user, RedirectAttributes redirect) {
    me.setAllChannelsEnabled(user.requireUserId(), true);
    redirect.addFlashAttribute("notice", "Alerts are back on for your verified channels.");
    return "redirect:/settings";
  }

  /** The account is gone (deleted in another tab): sign out and start over. */
  @ExceptionHandler(SubscriberNotFoundException.class)
  String accountGone(
      Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
    new SecurityContextLogoutHandler().logout(request, response, authentication);
    return "redirect:/";
  }

  private void save(
      NeoBuddyUser user,
      Me current,
      IdentifierType type,
      String value,
      String label,
      List<String> errors) {
    if (value.isBlank()) {
      return;
    }
    try {
      me.addIdentifier(user.requireUserId(), type, value);
    } catch (RuntimeException e) {
      if (current.identifier(type) != null) {
        errors.add(label + ": you already have one. Change it on the dashboard.");
      } else {
        errors.add(label + ": " + messageOf(e));
      }
    }
  }

  private static String messageOf(RuntimeException e) {
    return Problems.from(e).map(problem -> problem.getDetail()).orElseThrow(() -> e);
  }
}
