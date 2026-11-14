package com.neobuddy.web.me;

import com.neobuddy.web.support.Problems;
import com.neobuddy.web.support.RateLimitedException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 9457 problems for the JSON API. Spring's own errors (bad JSON, 405...) come from the base.
 */
@RestControllerAdvice(assignableTypes = MeApiController.class)
class MeApiProblems extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(MeApiProblems.class);

  @ExceptionHandler(RuntimeException.class)
  ResponseEntity<ProblemDetail> known(RuntimeException failure) {
    ProblemDetail problem =
        Problems.from(failure)
            .orElseGet(() -> {
              log.error("Unexpected failure in the student API: {}", failure.getClass().getName());
              return Problems.problem(
                  HttpStatus.INTERNAL_SERVER_ERROR,
                  "internal-error",
                  "Something went wrong",
                  "Something went wrong on our side. Try again in a moment.");
            });
    ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.getStatus());
    if (failure instanceof RateLimitedException limited) {
      response.header(HttpHeaders.RETRY_AFTER, Long.toString(limited.retryAfterSeconds()));
    }
    return response.body(problem);
  }

  /** Lists each invalid field with its message; never echoes what was entered. */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem =
        Problems.problem(
            HttpStatus.BAD_REQUEST,
            "validation-failed",
            "Invalid request",
            "Some fields are missing or invalid.");
    Map<String, String> errors = new LinkedHashMap<>();
    ex.getBindingResult()
        .getFieldErrors()
        .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
    problem.setProperty("errors", errors);
    return ResponseEntity.badRequest().body(problem);
  }
}
