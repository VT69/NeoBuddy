package com.neobuddy.web.auth;

import com.neobuddy.subscriber.RegistrationRejectedException.Reason;
import java.util.Locale;
import java.util.Set;

/** The OAuth2 error codes a refused sign-in carries, and the pages they lead to. */
public final class SignInErrors {

  public static final String COLLEGE_NOT_SUPPORTED = "college_not_supported";
  public static final String COLLEGE_PAUSED = "college_paused";

  /**
   * Codes the sign-in problem page knows how to explain; anything else is "something went wrong".
   */
  public static final Set<String> EXPLAINED =
      Set.of(
          "email_not_verified",
          "hosted_domain_mismatch",
          "email_already_registered",
          "access_denied");

  private SignInErrors() {}

  static String codeOf(Reason reason) {
    return reason.name().toLowerCase(Locale.ROOT);
  }
}
