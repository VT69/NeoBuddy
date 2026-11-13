package com.neobuddy.web.me;

import com.neobuddy.web.auth.NeoBuddyUser;
import com.neobuddy.web.me.MeRequests.AddIdentifier;
import com.neobuddy.web.me.MeRequests.PatchChannel;
import com.neobuddy.web.me.MeRequests.StartEmail;
import com.neobuddy.web.me.MeRequests.VerifyEmail;
import com.neobuddy.web.me.MeViews.Alerts;
import com.neobuddy.web.me.MeViews.Channel;
import com.neobuddy.web.me.MeViews.EmailVerificationStarted;
import com.neobuddy.web.me.MeViews.Identifier;
import com.neobuddy.web.me.MeViews.Me;
import com.neobuddy.web.me.MeViews.NtfySetup;
import com.neobuddy.web.me.MeViews.TestAlertQueued;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The student API (docs/ARCHITECTURE.md, APIs: Student endpoints), JSON in and out. The same paths
 * answer htmx requests with HTML fragments ({@link MeFragmentsController}). Errors are RFC 9457
 * problems ({@link MeApiProblems}). Discord linking arrives with T10.
 */
@RestController
@RequestMapping("/api/v1/me")
class MeApiController {

  private final MeService me;

  MeApiController(MeService me) {
    this.me = me;
  }

  @GetMapping
  Me get(@AuthenticationPrincipal NeoBuddyUser user) {
    return me.me(user.requireUserId());
  }

  @DeleteMapping
  ResponseEntity<Void> delete(
      @AuthenticationPrincipal NeoBuddyUser user,
      Authentication authentication,
      HttpServletRequest request,
      HttpServletResponse response) {
    me.deleteAccount(user.requireUserId());
    new SecurityContextLogoutHandler().logout(request, response, authentication);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/identifiers")
  ResponseEntity<Identifier> addIdentifier(
      @AuthenticationPrincipal NeoBuddyUser user, @Valid @RequestBody AddIdentifier body) {
    Identifier added = me.addIdentifier(user.requireUserId(), body.type(), body.value());
    return ResponseEntity.created(URI.create("/api/v1/me/identifiers/" + added.id())).body(added);
  }

  @DeleteMapping("/identifiers/{id}")
  ResponseEntity<Void> removeIdentifier(
      @AuthenticationPrincipal NeoBuddyUser user, @PathVariable UUID id) {
    me.removeIdentifier(user.requireUserId(), id);
    return ResponseEntity.noContent().build();
  }

  /** 202 when a code was emailed; 200 when the address was already verified. */
  @PostMapping("/channels/email")
  ResponseEntity<EmailVerificationStarted> startEmail(
      @AuthenticationPrincipal NeoBuddyUser user, @Valid @RequestBody StartEmail body) {
    EmailVerificationStarted started =
        me.startEmailVerification(user.requireUserId(), body.address());
    return ResponseEntity.status(started.alreadyVerified() ? HttpStatus.OK : HttpStatus.ACCEPTED)
        .body(started);
  }

  @PostMapping("/channels/email/verify")
  Map<String, Boolean> verifyEmail(
      @AuthenticationPrincipal NeoBuddyUser user, @Valid @RequestBody VerifyEmail body) {
    me.confirmEmail(user.requireUserId(), body.code());
    return Map.of("verified", true);
  }

  @PostMapping("/channels/ntfy")
  ResponseEntity<NtfySetup> createNtfy(@AuthenticationPrincipal NeoBuddyUser user) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(me.createNtfyChannel(user.requireUserId()));
  }

  @PatchMapping("/channels/{id}")
  Channel patchChannel(
      @AuthenticationPrincipal NeoBuddyUser user,
      @PathVariable UUID id,
      @Valid @RequestBody PatchChannel body) {
    return me.setChannelEnabled(user.requireUserId(), id, body.enabled());
  }

  @PostMapping("/channels/{id}/test")
  ResponseEntity<TestAlertQueued> test(
      @AuthenticationPrincipal NeoBuddyUser user, @PathVariable UUID id) {
    return ResponseEntity.accepted().body(me.sendTestAlert(user.requireUserId(), id));
  }

  @GetMapping("/alerts")
  Alerts alerts(
      @AuthenticationPrincipal NeoBuddyUser user,
      @RequestParam(name = "page", defaultValue = "0") int page) {
    return me.alerts(user.requireUserId(), page);
  }
}
