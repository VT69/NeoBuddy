package com.neobuddy.web.pages;

import com.neobuddy.web.auth.NeoBuddyUser;
import com.neobuddy.web.auth.SignInErrors;
import com.neobuddy.web.auth.SignInHandlers;
import com.neobuddy.web.support.RateLimitedException;
import com.neobuddy.web.support.RateLimiter;
import com.neobuddy.web.support.RateLimiter.Limit;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Pages anyone can open: landing, privacy policy, terms, and the refused-sign-in pages. */
@Controller
class PublicPagesController {

  private final CollegeRequestStore requests;
  private final RateLimiter rateLimiter;
  private final String contactEmail;

  PublicPagesController(
      CollegeRequestStore requests,
      RateLimiter rateLimiter,
      @Value("${neobuddy.web.contact-email:}") String contactEmail) {
    this.requests = requests;
    this.rateLimiter = rateLimiter;
    this.contactEmail = contactEmail;
  }

  @ModelAttribute("contactEmail")
  String contactEmail() {
    return contactEmail.isBlank() ? null : contactEmail.strip();
  }

  @GetMapping("/")
  String landing(@AuthenticationPrincipal NeoBuddyUser user, Model model) {
    model.addAttribute("signedIn", user != null && !user.needsConsent());
    return "index";
  }

  @GetMapping("/privacy")
  String privacy() {
    return "privacy";
  }

  @GetMapping("/terms")
  String terms() {
    return "terms";
  }

  @GetMapping("/sign-in-problem")
  String signInProblem(
      @RequestParam(name = "reason", defaultValue = "unknown") String reason, Model model) {
    model.addAttribute("reason", SignInErrors.EXPLAINED.contains(reason) ? reason : "unknown");
    return "sign-in-problem";
  }

  @GetMapping("/not-supported")
  String notSupported(HttpSession session, Model model) {
    if (!model.containsAttribute("form")) {
      Object domain = session.getAttribute(SignInHandlers.REFUSED_DOMAIN);
      model.addAttribute(
          "form", new CollegeRequestForm(null, domain == null ? null : domain.toString(), null));
    }
    return "not-supported";
  }

  @PostMapping("/college-requests")
  String requestCollege(
      @Valid @ModelAttribute("form") CollegeRequestForm form,
      BindingResult errors,
      HttpServletRequest request,
      Model model) {
    if (errors.hasErrors()) {
      model.addAttribute(
          "errors",
          errors.getFieldErrors().stream()
              .map(error -> error.getDefaultMessage())
              .distinct()
              .toList());
      return "not-supported";
    }
    try {
      rateLimiter.consume(Limit.COLLEGE_REQUEST, request.getRemoteAddr());
    } catch (RateLimitedException e) {
      model.addAttribute("errors", List.of("Too many requests. Try again later."));
      return "not-supported";
    }
    requests.save(
        form.collegeName().strip(),
        form.emailDomain().strip().replaceFirst("^@", "").toLowerCase(Locale.ROOT),
        form.contactEmail() == null || form.contactEmail().isBlank()
            ? null
            : form.contactEmail().strip().toLowerCase(Locale.ROOT));
    request.getSession().removeAttribute(SignInHandlers.REFUSED_DOMAIN);
    return "redirect:/not-supported?requested";
  }
}
