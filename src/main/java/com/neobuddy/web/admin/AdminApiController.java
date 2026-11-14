package com.neobuddy.web.admin;

import com.neobuddy.ingestion.EmailAdmin;
import com.neobuddy.ingestion.EmailAdmin.EmailRow;
import com.neobuddy.ingestion.SourceAdmin;
import com.neobuddy.ingestion.SourceAdmin.NewInvite;
import com.neobuddy.ingestion.SourceAdmin.SourceView;
import com.neobuddy.notification.DeliveryAdmin;
import com.neobuddy.notification.DeliveryAdmin.DeadDelivery;
import com.neobuddy.processing.StoredMatches;
import com.neobuddy.shared.college.College;
import com.neobuddy.web.auth.NeoBuddyUser;
import com.neobuddy.web.pages.CollegeRequestStore;
import com.neobuddy.web.pages.CollegeRequestStore.Request;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin JSON API, {@code /api/v1/admin} (ROLE_ADMIN only), as listed in docs/ARCHITECTURE.md
 * (Admin and ops endpoints), plus the college and delivery actions the console offers.
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminApiController {

  /** A college as the API shows it. */
  record CollegeJson(
      UUID id,
      String slug,
      String name,
      List<String> emailDomains,
      List<String> senderAllowlist,
      String neoIdPattern,
      String status,
      Map<String, List<String>> classifierOverrides,
      Instant createdAt) {

    static CollegeJson of(College c) {
      var o = c.getClassifierOverrides();
      return new CollegeJson(
          c.getId(),
          c.getSlug(),
          c.getName(),
          c.getAllowedEmailDomains(),
          c.getSenderAllowlist(),
          c.getNeoIdPattern(),
          c.getStatus().name(),
          Map.of(
              "offerSubject", o.offerSubject(),
              "offerBody", o.offerBody(),
              "shortlistSubject", o.shortlistSubject(),
              "shortlistBody", o.shortlistBody(),
              "knownRecruiters", o.knownRecruiters()),
          c.getCreatedAt());
    }
  }

  /** An email row with how many students it matched. */
  record EmailJson(
      String rfcMessageId,
      UUID collegeId,
      String sender,
      String subject,
      Instant receivedAt,
      String eventType,
      String company,
      int matchCount,
      String status,
      String error,
      Instant processedAt) {}

  record InviteJson(UUID id, UUID collegeId, String link, Instant expiresAt) {}

  private final AdminColleges colleges;
  private final CollegeRequestStore requests;
  private final SourceAdmin sources;
  private final EmailAdmin emails;
  private final StoredMatches matches;
  private final DeliveryAdmin deliveries;

  AdminApiController(
      AdminColleges colleges,
      CollegeRequestStore requests,
      SourceAdmin sources,
      EmailAdmin emails,
      StoredMatches matches,
      DeliveryAdmin deliveries) {
    this.colleges = colleges;
    this.requests = requests;
    this.sources = sources;
    this.emails = emails;
    this.matches = matches;
    this.deliveries = deliveries;
  }

  // ---- Colleges --------------------------------------------------------------------------------

  @GetMapping("/colleges")
  List<CollegeJson> colleges() {
    return colleges.all().stream().map(CollegeJson::of).toList();
  }

  @PostMapping("/colleges")
  ResponseEntity<CollegeJson> createCollege(@RequestBody CollegeForm form) {
    College created = colleges.create(form);
    return ResponseEntity.created(URI.create("/api/v1/admin/colleges/" + created.getId()))
        .body(CollegeJson.of(created));
  }

  @PutMapping("/colleges/{id}")
  CollegeJson updateCollege(@PathVariable UUID id, @RequestBody CollegeForm form) {
    return CollegeJson.of(colleges.update(id, form));
  }

  @GetMapping("/college-requests")
  List<Request> collegeRequests() {
    return requests.recent(500);
  }

  // ---- Sources ---------------------------------------------------------------------------------

  @GetMapping("/sources")
  List<SourceView> sources() {
    return sources.sources();
  }

  /**
   * OAuth consent for a new source inbox of a college: makes a single-use invite and sends the
   * browser through it to Google, so the admin's own inbox is connected the same way a campus
   * lead's is.
   */
  @GetMapping("/sources/connect")
  ResponseEntity<Void> connect(
      @RequestParam("college") String slug, @AuthenticationPrincipal NeoBuddyUser admin) {
    College college =
        colleges.all().stream()
            .filter(c -> c.getSlug().equals(slug))
            .findFirst()
            .orElseThrow(NoSuchElementException::new);
    NewInvite invite = sources.createInvite(college.getId(), admin.userId().orElse(null));
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(URI.create(AdminPagesController.inviteLink(invite) + "/start"))
        .build();
  }

  @PostMapping("/sources/invites")
  @ResponseStatus(HttpStatus.CREATED)
  InviteJson createInvite(
      @RequestParam("collegeId") UUID collegeId, @AuthenticationPrincipal NeoBuddyUser admin) {
    NewInvite invite = sources.createInvite(collegeId, admin.userId().orElse(null));
    return new InviteJson(
        invite.id(),
        invite.collegeId(),
        AdminPagesController.inviteLink(invite),
        invite.expiresAt());
  }

  @PostMapping("/sources/{id}/disconnect")
  ResponseEntity<Void> disconnect(@PathVariable UUID id) {
    return sources.disconnect(id)
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }

  // ---- Emails ----------------------------------------------------------------------------------

  @GetMapping("/emails")
  List<EmailJson> emails(
      @RequestParam(name = "college", required = false) UUID collegeId,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "50") int size) {
    List<EmailRow> rows = emails.recent(collegeId, status, page, size);
    Map<String, Integer> counts =
        matches.countsByEmail(rows.stream().map(EmailRow::rfcMessageId).toList());
    return rows.stream()
        .map(r ->
            new EmailJson(
                r.rfcMessageId(),
                r.collegeId(),
                r.sender(),
                r.subject(),
                r.receivedAt(),
                r.eventType(),
                r.company(),
                counts.getOrDefault(r.rfcMessageId(), 0),
                r.status(),
                r.error(),
                r.processedAt()))
        .toList();
  }

  @PostMapping("/emails/{rfcMessageId}/reprocess")
  ResponseEntity<Void> reprocess(@PathVariable String rfcMessageId) {
    emails.reprocess(rfcMessageId);
    return ResponseEntity.accepted().build();
  }

  // ---- Deliveries ------------------------------------------------------------------------------

  /** Only DEAD deliveries are listed; that is what needs review. */
  @GetMapping("/deliveries")
  List<DeadDelivery> deliveries(
      @RequestParam(name = "status", defaultValue = "DEAD") String status,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "50") int size) {
    if (!"DEAD".equals(status)) {
      throw new IllegalArgumentException("Only DEAD is supported");
    }
    return deliveries.dead(page, size);
  }

  @PostMapping("/deliveries/{id}/retry")
  ResponseEntity<Void> retry(@PathVariable UUID id) {
    return deliveries.retry(id)
        ? ResponseEntity.noContent().build()
        : ResponseEntity.status(HttpStatus.CONFLICT).build();
  }

  @PostMapping("/deliveries/{id}/discard")
  ResponseEntity<Void> discard(@PathVariable UUID id) {
    return deliveries.discard(id)
        ? ResponseEntity.noContent().build()
        : ResponseEntity.status(HttpStatus.CONFLICT).build();
  }
}
