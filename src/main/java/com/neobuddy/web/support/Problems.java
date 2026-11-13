package com.neobuddy.web.support;

import com.neobuddy.notification.TestAlertNotPossibleException;
import com.neobuddy.subscriber.IdentifierAlreadyClaimedException;
import com.neobuddy.subscriber.InvalidChannelAddressException;
import com.neobuddy.subscriber.InvalidIdentifierException;
import com.neobuddy.subscriber.SubscriberNotFoundException;
import com.neobuddy.subscriber.VerificationNotSentException;
import java.net.URI;
import java.util.Optional;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;

/**
 * The one place that turns a failure into an RFC 9457 problem: a stable {@code type} per error, a
 * status and a message a student can act on. The JSON API returns it as is; the htmx fragments show
 * its detail. Never includes the value the user entered.
 */
public final class Problems {

  public static final String TYPE_PREFIX = "/problems/";

  private Problems() {}

  public static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(TYPE_PREFIX + type));
    problem.setTitle(title);
    return problem;
  }

  /** The problem for a known failure; empty for anything unexpected. */
  public static Optional<ProblemDetail> from(Throwable failure) {
    return Optional.ofNullable(
        switch (failure) {
          case IdentifierAlreadyClaimedException e ->
              problem(
                  HttpStatus.CONFLICT,
                  "identifier-already-claimed",
                  "Identifier already claimed",
                  "Another account already uses this identifier. If it is yours, ask the admin to"
                      + " sort it out.");
          case InvalidIdentifierException e ->
              problem(
                  HttpStatus.UNPROCESSABLE_CONTENT,
                  "invalid-identifier",
                  "Invalid identifier",
                  switch (e.reason()) {
                    case BLANK -> "Enter a value.";
                    case WRONG_FORMAT ->
                        "That is not in the format your college uses. Check it and try again.";
                    case NOT_A_COLLEGE_EMAIL -> "Use an address on your college's email domain.";
                    case ALREADY_HAS_ONE_OF_THIS_TYPE ->
                        "You already have one of these. Remove it first to change it.";
                  });
          case InvalidChannelAddressException e ->
              problem(
                  HttpStatus.UNPROCESSABLE_CONTENT,
                  "invalid-email-address",
                  "Invalid email address",
                  "Enter a valid email address.");
          case VerificationNotSentException e ->
              problem(
                  HttpStatus.SERVICE_UNAVAILABLE,
                  "email-not-sent",
                  "Code not sent",
                  "The code could not be emailed to that address. Check it and try again later.");
          case EmailCodeRejectedException e ->
              switch (e.result()) {
                case WRONG_CODE ->
                    problem(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "wrong-code",
                        "Wrong code",
                        "That code is not right. Check the latest email and try again.");
                case TOO_MANY_ATTEMPTS ->
                    problem(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "code-locked",
                        "Too many attempts",
                        "Too many wrong codes. Ask for a new code.");
                case EXPIRED ->
                    problem(
                        HttpStatus.GONE,
                        "code-expired",
                        "Code expired",
                        "That code has expired. Ask for a new one.");
                case NO_PENDING_CODE ->
                    problem(
                        HttpStatus.CONFLICT,
                        "no-pending-code",
                        "No code to check",
                        "Ask for a code first.");
                case VERIFIED -> null;
              };
          case RateLimitedException e -> {
            ProblemDetail p =
                problem(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "rate-limited",
                    "Too many requests",
                    "You have done that too often. Try again in "
                        + minutes(e.retryAfterSeconds())
                        + ".");
            p.setProperty("retryAfterSeconds", e.retryAfterSeconds());
            yield p;
          }
          case TestAlertNotPossibleException e ->
              switch (e.reason()) {
                case CHANNEL_NOT_FOUND ->
                    problem(
                        HttpStatus.NOT_FOUND,
                        "channel-not-found",
                        "Channel not found",
                        "You have no such channel.");
                case CHANNEL_NOT_READY ->
                    problem(
                        HttpStatus.CONFLICT,
                        "channel-not-ready",
                        "Channel not ready",
                        "Verify this channel and switch it on before sending a test.");
                case NO_IDENTIFIER ->
                    problem(
                        HttpStatus.CONFLICT,
                        "identifier-required",
                        "Identifier required",
                        "Add your NeoID before sending a test alert.");
              };
          case SubscriberNotFoundException e ->
              problem(HttpStatus.NOT_FOUND, "not-found", "Not found", e.getMessage() + ".");
          case TypeMismatchException e ->
              problem(
                  HttpStatus.BAD_REQUEST,
                  "bad-request",
                  "Bad request",
                  "The request is malformed.");
          case ErrorResponse e -> e.getBody();
          default -> null;
        });
  }

  private static String minutes(long seconds) {
    long minutes = Math.max(1, (seconds + 59) / 60);
    return minutes == 1 ? "a minute" : minutes + " minutes";
  }
}
