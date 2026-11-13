package com.neobuddy.web.me;

import com.neobuddy.web.support.Problems;
import com.neobuddy.web.support.RateLimitedException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.ui.Model;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Errors of htmx requests: the problem's message, rendered into the page's flash area. The status
 * stays an error status (htmx is configured to swap 4xx answers), and {@code HX-Retarget} points
 * the swap at the flash area whatever the request targeted.
 */
@ControllerAdvice(assignableTypes = MeFragmentsController.class)
class MeFragmentProblems {

  private static final Logger log = LoggerFactory.getLogger(MeFragmentProblems.class);

  @ExceptionHandler(BindException.class)
  String invalid(BindException failure, Model model, HttpServletResponse response) {
    String message =
        failure.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getDefaultMessage())
            .distinct()
            .reduce((a, b) -> a + " " + b)
            .orElse("Some fields are missing or invalid.");
    return flash(model, response, HttpStatus.BAD_REQUEST.value(), message);
  }

  @ExceptionHandler(RuntimeException.class)
  String known(RuntimeException failure, Model model, HttpServletResponse response) {
    ProblemDetail problem =
        Problems.from(failure)
            .orElseGet(() -> {
              log.error(
                  "Unexpected failure in a dashboard action: {}", failure.getClass().getName());
              return Problems.problem(
                  HttpStatus.INTERNAL_SERVER_ERROR,
                  "internal-error",
                  "Something went wrong",
                  "Something went wrong on our side. Try again in a moment.");
            });
    if (failure instanceof RateLimitedException limited) {
      response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(limited.retryAfterSeconds()));
    }
    return flash(model, response, problem.getStatus(), problem.getDetail());
  }

  private static String flash(
      Model model, HttpServletResponse response, int status, String message) {
    model.addAttribute("message", message);
    model.addAttribute("kind", "error");
    response.setStatus(status);
    response.setHeader("HX-Retarget", "#flash");
    response.setHeader("HX-Reswap", "innerHTML");
    return MeFragmentsController.FLASH;
  }
}
