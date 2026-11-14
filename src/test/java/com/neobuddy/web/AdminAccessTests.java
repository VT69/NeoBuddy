package com.neobuddy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.neobuddy.shared.college.College;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every route under {@code /admin} and {@code /api/v1/admin}, found from the application's own
 * request mappings so a new endpoint cannot be forgotten, refuses a signed-in student with 403.
 */
class AdminAccessTests extends WebDatabaseTest {

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mappings;

  private Student student;

  @BeforeEach
  void aStudent() {
    College college = newCollege();
    student = newStudent(college);
  }

  private record Route(HttpMethod method, String path) {}

  private List<Route> adminRoutes() {
    List<Route> routes = new ArrayList<>();
    mappings
        .getHandlerMethods()
        .forEach((RequestMappingInfo info, Object handler) -> {
          Set<String> patterns = info.getPatternValues();
          Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
          for (String pattern : patterns) {
            if (!pattern.equals("/admin")
                && !pattern.startsWith("/admin/")
                && !pattern.startsWith("/api/v1/admin")) {
              continue;
            }
            String path =
                pattern
                    .replace("{rfcMessageId}", "missing@mail.example")
                    .replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
            if (methods.isEmpty()) {
              routes.add(new Route(HttpMethod.GET, path));
            } else {
              methods.forEach(m -> routes.add(new Route(HttpMethod.valueOf(m.name()), path)));
            }
          }
        });
    return routes;
  }

  private MvcResult perform(Route route, boolean withStudent) throws Exception {
    MockHttpServletRequestBuilder request = request(route.method(), route.path());
    if (route.method() != HttpMethod.GET) {
      request = request.with(csrf()).param("collegeId", UUID.randomUUID().toString());
    }
    if (withStudent) {
      request = request.with(as(student));
    }
    return mvc.perform(request).andReturn();
  }

  @Test
  void theAdminRoutesAreFound() {
    List<Route> routes = adminRoutes();

    assertThat(routes)
        .extracting(Route::path)
        .contains(
            "/admin",
            "/admin/colleges",
            "/admin/requests",
            "/admin/sources",
            "/admin/emails",
            "/admin/deliveries",
            "/api/v1/admin/sources",
            "/api/v1/admin/sources/connect",
            "/api/v1/admin/deliveries",
            "/api/v1/admin/emails/missing@mail.example/reprocess");
    assertThat(routes).hasSizeGreaterThanOrEqualTo(25);
  }

  @Test
  void everyAdminRouteRefusesAStudentWith403() throws Exception {
    List<String> notForbidden = new ArrayList<>();
    for (Route route : adminRoutes()) {
      int status = perform(route, true).getResponse().getStatus();
      if (status != 403) {
        notForbidden.add(route.method() + " " + route.path() + " -> " + status);
      }
    }

    assertThat(notForbidden).isEmpty();
  }

  @Test
  void everyAdminRouteRefusesStrangers() throws Exception {
    List<String> allowed = new ArrayList<>();
    for (Route route : adminRoutes()) {
      int status = perform(route, false).getResponse().getStatus();
      if (status < 300 || status >= 500) {
        allowed.add(route.method() + " " + route.path() + " -> " + status);
      }
    }

    assertThat(allowed).isEmpty();
  }

  @Test
  void operatorEndpointsRefuseAStudent() throws Exception {
    for (String path : List.of("/actuator/health/ops", "/actuator/prometheus")) {
      assertThat(perform(new Route(HttpMethod.GET, path), true).getResponse().getStatus())
          .as(path)
          .isEqualTo(403);
    }
  }
}
