package com.neobuddy.web.admin;

import com.neobuddy.ingestion.EmailNotReprocessableException;
import com.neobuddy.ingestion.SourceConnectException;
import com.neobuddy.web.support.Problems;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 problems for the admin API. Messages never echo what was sent. */
@RestControllerAdvice(assignableTypes = AdminApiController.class)
class AdminProblems {

  @ExceptionHandler(AdminFormException.class)
  ProblemDetail invalid(AdminFormException e) {
    ProblemDetail problem =
        Problems.problem(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "invalid-college",
            "Invalid college",
            "Some fields are not valid.");
    problem.setProperty("errors", e.errors());
    return problem;
  }

  @ExceptionHandler(EmailNotReprocessableException.class)
  ProblemDetail notReprocessable(EmailNotReprocessableException e) {
    HttpStatus status =
        e.reason() == EmailNotReprocessableException.Reason.NOT_FOUND
            ? HttpStatus.NOT_FOUND
            : HttpStatus.CONFLICT;
    return Problems.problem(status, "email-not-reprocessable", "Cannot reprocess", messageOf(e));
  }

  @ExceptionHandler(NoSuchElementException.class)
  ProblemDetail notFound(NoSuchElementException e) {
    return Problems.problem(HttpStatus.NOT_FOUND, "not-found", "Not found", "No such item.");
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail badRequest(IllegalArgumentException e) {
    return Problems.problem(
        HttpStatus.BAD_REQUEST, "bad-request", "Bad request", "A parameter is not valid.");
  }

  @ExceptionHandler(SourceConnectException.class)
  ProblemDetail connect(SourceConnectException e) {
    return Problems.problem(
        HttpStatus.CONFLICT, "source-connect-failed", "Cannot connect", messageOf(e));
  }

  static String messageOf(EmailNotReprocessableException e) {
    return switch (e.reason()) {
      case NOT_FOUND -> "No email with that Message-ID was ingested.";
      case TEST_EMAIL -> "That is a test-alert email; there is nothing to fetch again.";
      case SOURCE_UNAVAILABLE ->
          "Its source inbox is disconnected or lost its authorisation; connect it again first.";
    };
  }

  static String messageOf(SourceConnectException e) {
    return switch (e.reason()) {
      case INVITE_UNUSABLE ->
          "This link has expired or was already used. Ask the NeoBuddy admin for a new one.";
      case NOT_CONFIGURED -> "Connecting inboxes from the browser is not set up on this server.";
      case DENIED -> "Access was not granted, so nothing was connected.";
      case GOOGLE_REFUSED, GOOGLE_UNREACHABLE ->
          "Google did not complete the connection. Try the link again in a minute.";
      case SCOPE_NOT_GRANTED ->
          "Read access to Gmail was not ticked on Google's screen. Open the link again and allow"
              + " it.";
      case NO_REFRESH_TOKEN ->
          "Google did not hand out lasting access. Remove NeoBuddy at"
              + " myaccount.google.com/permissions and open the link again.";
      case BELONGS_TO_ANOTHER_COLLEGE ->
          "That inbox is already connected for another college, so it was not connected here.";
    };
  }
}
