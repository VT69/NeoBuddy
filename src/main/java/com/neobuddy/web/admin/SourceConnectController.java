package com.neobuddy.web.admin;

import com.neobuddy.ingestion.SourceAdmin;
import com.neobuddy.ingestion.SourceAdmin.ConnectStart;
import com.neobuddy.ingestion.SourceAdmin.Connected;
import com.neobuddy.ingestion.SourceAdmin.InviteView;
import com.neobuddy.ingestion.SourceConnectException;
import com.neobuddy.ingestion.SourceConnectException.Reason;
import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The invite link a campus lead opens to connect their college's placement inbox. Public: the lead
 * needs no NeoBuddy account. The link's token is the credential; it works once, for 24 hours.
 *
 * <ol>
 *   <li>{@code GET /sources/connect/{token}}: what will happen, and a button.
 *   <li>{@code GET /sources/connect/{token}/start}: remembers the request in the session and sends
 *       the browser to Google ({@code gmail.readonly}, offline, consent).
 *   <li>{@code GET /sources/connect/callback}: checks the state, finishes, consumes the invite.
 * </ol>
 */
@Controller
class SourceConnectController {

  static final String CALLBACK = "/sources/connect/callback";
  private static final String SESSION_KEY = SourceConnectController.class.getName() + ".pending";

  /** What the callback needs from the start, kept in the browser's session only. */
  record Pending(String token, String state, String verifier) implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    @Override
    public String toString() {
      return "Pending[]";
    }
  }

  private final SourceAdmin sources;

  SourceConnectController(SourceAdmin sources) {
    this.sources = sources;
  }

  @GetMapping("/sources/connect/{token}")
  String invite(@PathVariable String token, Model model) {
    Optional<InviteView> invite = sources.usableInvite(token);
    if (invite.isEmpty()) {
      return problem(model, new SourceConnectException(Reason.INVITE_UNUSABLE));
    }
    model.addAttribute("invite", invite.get());
    model.addAttribute("token", token);
    model.addAttribute("configured", sources.webConnectConfigured());
    return "sources/connect";
  }

  @GetMapping("/sources/connect/{token}/start")
  String start(@PathVariable String token, HttpSession session, Model model) {
    try {
      ConnectStart start = sources.start(token, callbackUri());
      session.setAttribute(SESSION_KEY, new Pending(token, start.state(), start.codeVerifier()));
      return "redirect:" + start.authorizationUrl();
    } catch (SourceConnectException e) {
      return problem(model, e);
    }
  }

  @GetMapping(CALLBACK)
  String callback(
      @RequestParam(name = "state", required = false) String state,
      @RequestParam(name = "code", required = false) String code,
      @RequestParam(name = "error", required = false) String error,
      HttpSession session,
      Model model) {
    Object stored = session.getAttribute(SESSION_KEY);
    session.removeAttribute(SESSION_KEY);
    if (!(stored instanceof Pending pending)
        || state == null
        || !sameState(pending.state(), state)) {
      return problem(model, new SourceConnectException(Reason.DENIED));
    }
    if (error != null || code == null || code.isBlank()) {
      return problem(model, new SourceConnectException(Reason.DENIED));
    }
    try {
      Connected connected =
          sources.complete(pending.token(), code, callbackUri(), pending.verifier());
      model.addAttribute("connected", connected);
      return "sources/connected";
    } catch (SourceConnectException e) {
      return problem(model, e);
    }
  }

  private static String problem(Model model, SourceConnectException e) {
    model.addAttribute("message", AdminProblems.messageOf(e));
    model.addAttribute("status", HttpStatus.BAD_REQUEST.value());
    return "sources/connect-problem";
  }

  private static boolean sameState(String expected, String actual) {
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }

  /** Must equal a redirect URI registered on the web OAuth client. */
  static String callbackUri() {
    return ServletUriComponentsBuilder.fromCurrentContextPath().path(CALLBACK).toUriString();
  }
}
