package com.neobuddy.web;

import com.neobuddy.subscriber.SubscriberService;
import com.neobuddy.web.auth.CollegeDomainOidcUserService;
import com.neobuddy.web.auth.NeoBuddyUser;
import com.neobuddy.web.auth.SignInHandlers;
import com.neobuddy.web.support.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who may see what.
 *
 * <ul>
 *   <li>Public: the landing, privacy and terms pages, the "not supported" page and its request
 *       form, static files, the health endpoint.
 *   <li>Google sign-in (OpenID Connect, only {@code openid email profile}), limited to students of
 *       ACTIVE or PILOT colleges by {@link CollegeDomainOidcUserService}.
 *   <li>PENDING_CONSENT (signed in, no account yet): only the consent step.
 *   <li>USER: the dashboard, onboarding, settings and {@code /api/v1/me/**}. ADMIN comes from
 *       {@code app_user.role}; admin pages arrive with T11.
 * </ul>
 *
 * CSRF protection is on for every state-changing request; htmx sends the token in a header. The
 * session cookie is Secure, HttpOnly and SameSite=Lax (application.yml).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  static final String CSP =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
          + " object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

  @Bean
  SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      SubscriberService subscribers,
      SignInHandlers signInHandlers,
      SecurityContextRepository contexts,
      JsonMapper json)
      throws Exception {
    return http.securityContext(context -> context.securityContextRepository(contexts))
        .authorizeHttpRequests(auth ->
            auth.requestMatchers(
                    "/",
                    "/privacy",
                    "/terms",
                    "/not-supported",
                    "/sign-in-problem",
                    "/css/**",
                    "/js/**",
                    "/favicon.svg",
                    "/error",
                    "/actuator/health",
                    "/actuator/health/**")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/college-requests")
                .permitAll()
                .requestMatchers("/consent")
                .hasAuthority(NeoBuddyUser.ROLE_PENDING_CONSENT)
                .requestMatchers(
                    "/dashboard",
                    "/onboarding",
                    "/onboarding/**",
                    "/settings/**",
                    "/settings",
                    "/api/v1/me",
                    "/api/v1/me/**")
                .hasAuthority(NeoBuddyUser.ROLE_USER)
                .anyRequest()
                .denyAll())
        .oauth2Login(login ->
            login
                .loginPage("/")
                .userInfoEndpoint(userInfo ->
                    userInfo.oidcUserService(
                        new CollegeDomainOidcUserService(new OidcUserService(), subscribers)))
                .successHandler(signInHandlers)
                .failureHandler(signInHandlers))
        .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/"))
        .exceptionHandling(errors ->
            errors
                .authenticationEntryPoint(entryPoint(json))
                .accessDeniedHandler(accessDenied(json)))
        .headers(headers ->
            headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                .referrerPolicy(
                    referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
        .build();
  }

  /** No local users. Declared so Spring Boot does not generate and log a default password. */
  @Bean
  UserDetailsService userDetailsService() {
    return new InMemoryUserDetailsManager();
  }

  /** Not signed in: a problem for the API, a full-page redirect for htmx, the landing page else. */
  private static AuthenticationEntryPoint entryPoint(JsonMapper json) {
    return (request, response, exception) -> {
      if (isApi(request) && !isHtmx(request)) {
        writeProblem(
            response,
            json,
            Problems.problem(
                HttpStatus.UNAUTHORIZED,
                "not-signed-in",
                "Not signed in",
                "Sign in with your college Google account."));
      } else if (isHtmx(request)) {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader("HX-Redirect", request.getContextPath() + "/");
      } else {
        response.sendRedirect(request.getContextPath() + "/");
      }
    };
  }

  /**
   * Signed in but not allowed: a failed CSRF check or the wrong role. Someone who still has to
   * consent is sent to the consent step instead of an error.
   */
  private static AccessDeniedHandler accessDenied(JsonMapper json) {
    return (request, response, exception) -> {
      boolean csrf = exception instanceof CsrfException;
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      boolean pending =
          authentication != null
              && authentication.getPrincipal() instanceof NeoBuddyUser user
              && user.needsConsent();
      if (!csrf && pending && !isApi(request) && !isHtmx(request)) {
        response.sendRedirect(request.getContextPath() + "/consent");
        return;
      }
      ProblemDetail problem =
          csrf
              ? Problems.problem(
                  HttpStatus.FORBIDDEN,
                  "csrf-failed",
                  "Form expired",
                  "The page was open too long or the request did not come from NeoBuddy. Reload"
                      + " the page and try again.")
              : Problems.problem(
                  HttpStatus.FORBIDDEN,
                  "forbidden",
                  "Not allowed",
                  pending ? "Accept the consent notice first." : "You may not do that.");
      if (isApi(request) && !isHtmx(request)) {
        writeProblem(response, json, problem);
      } else {
        response.sendError(HttpStatus.FORBIDDEN.value(), problem.getDetail());
      }
    };
  }

  private static boolean isApi(HttpServletRequest request) {
    return request.getRequestURI().startsWith(request.getContextPath() + "/api/");
  }

  private static boolean isHtmx(HttpServletRequest request) {
    return "true".equals(request.getHeader("HX-Request"));
  }

  private static void writeProblem(
      HttpServletResponse response, JsonMapper json, ProblemDetail problem) throws IOException {
    response.setStatus(problem.getStatus());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    json.writeValue(response.getOutputStream(), problem);
  }
}
