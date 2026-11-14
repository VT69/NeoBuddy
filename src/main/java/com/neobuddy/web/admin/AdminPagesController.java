package com.neobuddy.web.admin;

import com.neobuddy.ingestion.EmailAdmin;
import com.neobuddy.ingestion.EmailAdmin.EmailRow;
import com.neobuddy.ingestion.EmailNotReprocessableException;
import com.neobuddy.ingestion.SourceAdmin;
import com.neobuddy.ingestion.SourceAdmin.NewInvite;
import com.neobuddy.ingestion.SourceAdmin.SourceView;
import com.neobuddy.notification.ChannelHealth;
import com.neobuddy.notification.DeliveryAdmin;
import com.neobuddy.notification.DeliveryAdmin.DeadDelivery;
import com.neobuddy.processing.StoredMatches;
import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeStatus;
import com.neobuddy.web.auth.NeoBuddyUser;
import com.neobuddy.web.pages.CollegeRequestStore;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The admin console (ROLE_ADMIN only, see SecurityConfig): colleges and college requests, source
 * inboxes and invite links, recent emails and reprocessing, dead deliveries. Pages are plain forms
 * that also work without JavaScript; htmx swaps a delivery row in place.
 *
 * <p>No page shows an email body, a snippet or a student's channel address.
 */
@Controller
class AdminPagesController {

  private static final Set<String> EMAIL_STATUSES = Set.of("RECEIVED", "PROCESSED", "FAILED");
  private static final int PAGE_SIZE = 50;

  private final AdminColleges colleges;
  private final CollegeRequestStore requests;
  private final SourceAdmin sources;
  private final EmailAdmin emails;
  private final StoredMatches matches;
  private final DeliveryAdmin deliveries;
  private final ChannelHealth channels;

  AdminPagesController(
      AdminColleges colleges,
      CollegeRequestStore requests,
      SourceAdmin sources,
      EmailAdmin emails,
      StoredMatches matches,
      DeliveryAdmin deliveries,
      ChannelHealth channels) {
    this.colleges = colleges;
    this.requests = requests;
    this.sources = sources;
    this.emails = emails;
    this.matches = matches;
    this.deliveries = deliveries;
    this.channels = channels;
  }

  @ModelAttribute("adminSection")
  String section() {
    return "";
  }

  // ---- Overview --------------------------------------------------------------------------------

  @GetMapping("/admin")
  String overview(Model model) {
    List<College> all = colleges.all();
    List<SourceView> sourceList = sources.sources();
    model.addAttribute("adminSection", "overview");
    model.addAttribute("colleges", all);
    model.addAttribute(
        "collegeCounts",
        all.stream().collect(Collectors.groupingBy(College::getStatus, Collectors.counting())));
    model.addAttribute("statuses", CollegeStatus.values());
    model.addAttribute("sources", sourceList);
    model.addAttribute(
        "sourcesNeedingAttention",
        sourceList.stream().filter(s -> s.health().needsAttention()).count());
    model.addAttribute("deadCount", deliveries.countDead());
    model.addAttribute("failedEmails", emails.recent(null, "FAILED", 0, 10));
    model.addAttribute("channels", channels.states());
    model.addAttribute("requestCount", requests.recent(1000).size());
    model.addAttribute("slugs", slugs());
    return "admin/overview";
  }

  // ---- Colleges --------------------------------------------------------------------------------

  @GetMapping("/admin/colleges")
  String colleges(Model model) {
    model.addAttribute("adminSection", "colleges");
    model.addAttribute("colleges", colleges.all());
    return "admin/colleges";
  }

  @GetMapping("/admin/colleges/new")
  String newCollege(@RequestParam(name = "request", required = false) UUID requestId, Model model) {
    CollegeForm form =
        requestId == null
            ? CollegeForm.blank(null, null)
            : requests
                .find(requestId)
                .map(r -> CollegeForm.blank(r.collegeName(), r.emailDomain()))
                .orElse(CollegeForm.blank(null, null));
    if (!model.containsAttribute("form")) {
      model.addAttribute("form", form);
    }
    model.addAttribute("adminSection", "colleges");
    model.addAttribute("collegeId", null);
    model.addAttribute("statuses", CollegeStatus.values());
    return "admin/college-form";
  }

  @PostMapping("/admin/colleges")
  String createCollege(@ModelAttribute CollegeForm form, RedirectAttributes redirect) {
    try {
      College created = colleges.create(form);
      redirect.addFlashAttribute(
          "notice",
          "College "
              + created.getSlug()
              + " created as "
              + created.getStatus()
              + ". Next: invite its campus lead to connect the placement inbox.");
      return "redirect:/admin/colleges/" + created.getId();
    } catch (AdminFormException e) {
      redirect.addFlashAttribute("errors", e.errors());
      redirect.addFlashAttribute("form", form);
      return "redirect:/admin/colleges/new";
    }
  }

  @GetMapping("/admin/colleges/{id}")
  String editCollege(@PathVariable UUID id, Model model) {
    College college = colleges.find(id).orElseThrow(AdminPagesController::notFound);
    if (!model.containsAttribute("form")) {
      model.addAttribute("form", CollegeForm.of(college));
    }
    model.addAttribute("adminSection", "colleges");
    model.addAttribute("collegeId", id);
    model.addAttribute("college", college);
    model.addAttribute("statuses", CollegeStatus.values());
    model.addAttribute(
        "collegeSources",
        sources.sources().stream().filter(s -> s.collegeId().equals(id)).toList());
    return "admin/college-form";
  }

  @PostMapping("/admin/colleges/{id}")
  String updateCollege(
      @PathVariable UUID id, @ModelAttribute CollegeForm form, RedirectAttributes redirect) {
    try {
      College updated = colleges.update(id, form);
      redirect.addFlashAttribute("notice", "Saved " + updated.getSlug() + ".");
    } catch (AdminFormException e) {
      redirect.addFlashAttribute("errors", e.errors());
      redirect.addFlashAttribute("form", form);
    } catch (NoSuchElementException e) {
      throw notFound();
    }
    return "redirect:/admin/colleges/" + id;
  }

  // ---- College requests ------------------------------------------------------------------------

  @GetMapping("/admin/requests")
  String requests(Model model) {
    model.addAttribute("adminSection", "requests");
    model.addAttribute("requests", requests.recent(500));
    return "admin/requests";
  }

  @PostMapping("/admin/requests/{id}/dismiss")
  String dismissRequest(@PathVariable UUID id, RedirectAttributes redirect) {
    if (requests.delete(id)) {
      redirect.addFlashAttribute("notice", "Request dismissed.");
    }
    return "redirect:/admin/requests";
  }

  // ---- Sources ---------------------------------------------------------------------------------

  @GetMapping("/admin/sources")
  String sources(Model model) {
    model.addAttribute("adminSection", "sources");
    model.addAttribute("sources", sources.sources());
    model.addAttribute("colleges", colleges.all());
    model.addAttribute("invites", sources.recentInvites(20));
    model.addAttribute("slugs", slugs());
    model.addAttribute("webConnectConfigured", sources.webConnectConfigured());
    model.addAttribute("staleAfterMinutes", sources.staleAfter().toMinutes());
    return "admin/sources";
  }

  @PostMapping("/admin/sources/invites")
  String createInvite(
      @RequestParam("collegeId") UUID collegeId,
      @AuthenticationPrincipal NeoBuddyUser admin,
      RedirectAttributes redirect) {
    try {
      NewInvite invite = sources.createInvite(collegeId, admin.userId().orElse(null));
      redirect.addFlashAttribute("inviteLink", inviteLink(invite));
      redirect.addFlashAttribute("inviteExpiresAt", invite.expiresAt());
    } catch (IllegalArgumentException e) {
      redirect.addFlashAttribute("errors", List.of("College: choose an existing college."));
    }
    return "redirect:/admin/sources";
  }

  @PostMapping("/admin/sources/{id}/disconnect")
  String disconnect(@PathVariable UUID id, RedirectAttributes redirect) {
    if (!sources.disconnect(id)) {
      throw notFound();
    }
    redirect.addFlashAttribute(
        "notice", "Source disconnected. Its emails stay; it is no longer polled.");
    return "redirect:/admin/sources";
  }

  // ---- Emails ----------------------------------------------------------------------------------

  @GetMapping("/admin/emails")
  String emails(
      @RequestParam(name = "college", required = false) UUID collegeId,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "page", defaultValue = "0") int page,
      Model model) {
    String filter = status != null && EMAIL_STATUSES.contains(status) ? status : null;
    List<EmailRow> rows = emails.recent(collegeId, filter, page, PAGE_SIZE);
    model.addAttribute("adminSection", "emails");
    model.addAttribute("emails", rows);
    model.addAttribute(
        "matchCounts", matches.countsByEmail(rows.stream().map(EmailRow::rfcMessageId).toList()));
    model.addAttribute("slugs", slugs());
    model.addAttribute("colleges", colleges.all());
    model.addAttribute("college", collegeId);
    model.addAttribute("status", filter);
    model.addAttribute("page", Math.max(page, 0));
    model.addAttribute("hasNext", rows.size() == PAGE_SIZE);
    return "admin/emails";
  }

  @PostMapping("/admin/emails/reprocess")
  String reprocess(@RequestParam("messageId") String messageId, RedirectAttributes redirect) {
    try {
      emails.reprocess(messageId.strip());
      redirect.addFlashAttribute(
          "notice", "Queued for processing again. Refresh in a minute to see the result.");
    } catch (EmailNotReprocessableException e) {
      redirect.addFlashAttribute("errors", List.of(AdminProblems.messageOf(e)));
    }
    return "redirect:/admin/emails";
  }

  // ---- Dead deliveries -------------------------------------------------------------------------

  @GetMapping("/admin/deliveries")
  String deliveries(@RequestParam(name = "page", defaultValue = "0") int page, Model model) {
    List<DeadDelivery> dead = deliveries.dead(page, PAGE_SIZE);
    model.addAttribute("adminSection", "deliveries");
    model.addAttribute("deliveries", dead);
    model.addAttribute("deadCount", deliveries.countDead());
    model.addAttribute("slugs", slugs());
    model.addAttribute("page", Math.max(page, 0));
    model.addAttribute("hasNext", dead.size() == PAGE_SIZE);
    return "admin/deliveries";
  }

  /** htmx swaps the row for the returned one; a plain form post goes back to the list. */
  @PostMapping("/admin/deliveries/{id}/retry")
  String retry(
      @PathVariable UUID id,
      @RequestHeader(name = "HX-Request", required = false) String htmx,
      Model model,
      RedirectAttributes redirect) {
    return settled(id, deliveries.retry(id), "Queued again", htmx, model, redirect);
  }

  @PostMapping("/admin/deliveries/{id}/discard")
  String discard(
      @PathVariable UUID id,
      @RequestHeader(name = "HX-Request", required = false) String htmx,
      Model model,
      RedirectAttributes redirect) {
    return settled(id, deliveries.discard(id), "Discarded", htmx, model, redirect);
  }

  private String settled(
      UUID id,
      boolean changed,
      String outcome,
      String htmx,
      Model model,
      RedirectAttributes redirect) {
    String message = changed ? outcome : "No longer dead; nothing changed";
    if ("true".equals(htmx)) {
      model.addAttribute("deliveryId", id);
      model.addAttribute("outcome", message);
      return "admin/deliveries :: settled";
    }
    redirect.addFlashAttribute("notice", message + ".");
    return "redirect:/admin/deliveries";
  }

  // ---- Helpers ---------------------------------------------------------------------------------

  private Map<UUID, String> slugs() {
    return colleges.all().stream()
        .collect(Collectors.toMap(College::getId, College::getSlug, (a, b) -> a));
  }

  static String inviteLink(NewInvite invite) {
    return ServletUriComponentsBuilder.fromCurrentContextPath()
        .path("/sources/connect/{token}")
        .buildAndExpand(invite.token())
        .toUriString();
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND);
  }
}
