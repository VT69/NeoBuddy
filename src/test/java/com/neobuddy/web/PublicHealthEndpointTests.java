package com.neobuddy.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobuddy.testsupport.TestDatabaseInitializer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * What the internet sees of a running instance before sign-in exists: the health endpoint, with the
 * status only, and nothing else. The deploy job relies on the first part; the second keeps database
 * and disk details private. Uses the default configuration, as production does.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestDatabaseInitializer.class)
class PublicHealthEndpointTests {

  @LocalServerPort private int port;

  private final HttpClient http = HttpClient.newHttpClient();

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void healthIsPublicAndShowsTheStatusOnly() throws Exception {
    HttpResponse<String> response = get("/actuator/health");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("\"status\":\"UP\"")
        .doesNotContain("components")
        .doesNotContain("details")
        .doesNotContain("PostgreSQL")
        .doesNotContain("diskSpace");
  }

  @ParameterizedTest
  @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
  void probesArePublicAndShowTheStatusOnly(String path) throws Exception {
    HttpResponse<String> response = get(path);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"/actuator/env", "/actuator/beans", "/actuator/prometheus", "/", "/api/v1/me"})
  void everythingElseIsRefused(String path) throws Exception {
    assertThat(get(path).statusCode()).isIn(401, 403, 404);
  }
}
