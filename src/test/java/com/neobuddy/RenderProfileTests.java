package com.neobuddy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.neobuddy.notification.NotificationChannel;
import com.neobuddy.notification.delivery.DeliveryProperties;
import com.neobuddy.subscriber.ChannelType;
import com.neobuddy.testsupport.SslTestDatabaseInitializer;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.apache.catalina.Valve;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * The {@code render} profile, started the way Render starts it, against an empty TLS-only
 * PostgreSQL standing in for Supabase: Flyway builds the schema at startup over an encrypted
 * connection, the pool stays small, email goes through Brevo, delivery is dispatched on commit and
 * the health check answers. The {@code test} profile underneath only supplies placeholder secrets
 * and keeps the poller and the scheduled dispatcher off.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "NEOBUDDY_BREVO_API_KEY=xkeysib-test-key",
      "NEOBUDDY_BREVO_FROM=NeoBuddy <alerts@neobuddy.example>",
      "RENDER_EXTERNAL_URL=https://neobuddy-test.onrender.com",
    })
@ActiveProfiles({"test", "render"})
@ContextConfiguration(initializers = SslTestDatabaseInitializer.class)
class RenderProfileTests {

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private HikariDataSource dataSource;
  @Autowired private DeliveryProperties delivery;
  @Autowired private List<NotificationChannel> channels;
  @Autowired private ApplicationContext context;

  @Value("${neobuddy.web.hosting}")
  private String hosting;

  private final HttpClient http =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void flywayCreatedTheSchemaAtStartup() {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class))
        .isGreaterThanOrEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where not success", Integer.class))
        .isZero();
    assertThat(jdbc.queryForObject("select count(*) from delivery", Integer.class)).isNotNull();
  }

  @Test
  void theDatabaseIsOnlyReachedOverTls() {
    assumeThat(SslTestDatabaseInitializer.provesEncryption())
        .as("only a TLS-only container proves encryption")
        .isTrue();

    assertThat(dataSource.getDataSourceProperties()).containsEntry("sslmode", "require");
    assertThat(
            jdbc.queryForObject(
                "select ssl from pg_stat_ssl where pid = pg_backend_pid()", Boolean.class))
        .isTrue();
  }

  @Test
  void thePoolStaysWithinSupabaseFreeLimits() {
    assertThat(dataSource.getMaximumPoolSize()).isEqualTo(3);
    assertThat(dataSource.getMinimumIdle()).isEqualTo(1);
  }

  @Test
  void emailGoesThroughBrevo() {
    assertThat(channels)
        .filteredOn(channel -> channel.type() == ChannelType.EMAIL)
        .singleElement()
        .extracting(channel -> channel.getClass().getSimpleName())
        .isEqualTo("BrevoEmailChannel");
    assertThat(context.getBeanNamesForType(software.amazon.awssdk.services.sesv2.SesV2Client.class))
        .isEmpty();
  }

  @Test
  void deliveriesAreDispatchedOnCommitWithAScheduledSweepEveryTwoMinutes() {
    assertThat(delivery.dispatchOnCommit()).isTrue();
    assertThat(delivery.pollInterval()).isEqualTo(Duration.ofMinutes(2));
  }

  @Test
  void theHealthCheckIsUp() throws Exception {
    HttpResponse<String> response = get("/actuator/health");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("components");
  }

  @Test
  void googleSignInReturnsToThePublicHttpsAddress() throws Exception {
    HttpResponse<String> response = get("/oauth2/authorization/google");

    assertThat(response.statusCode()).isEqualTo(302);
    String location =
        URLDecoder.decode(
            response.headers().firstValue("Location").orElseThrow(), StandardCharsets.UTF_8);
    assertThat(location)
        .startsWith("https://accounts.google.com/")
        .contains("redirect_uri=https://neobuddy-test.onrender.com/login/oauth2/code/google");
  }

  @Test
  void tomcatHonoursTheProxysForwardedHeaders() {
    TomcatWebServer server =
        (TomcatWebServer) ((WebServerApplicationContext) context).getWebServer();
    Valve[] valves = server.getTomcat().getEngine().getPipeline().getValves();

    assertThat(valves)
        .filteredOn(RemoteIpValve.class::isInstance)
        .singleElement()
        .satisfies(valve ->
            assertThat(((RemoteIpValve) valve).getProtocolHeader())
                .isEqualToIgnoringCase("X-Forwarded-Proto"));
  }

  @Test
  void thePrivacyPolicyNamesTheServicesThatRunNeoBuddyHere() throws Exception {
    assertThat(hosting).isEqualTo("render");
    assertThat(get("/privacy").body())
        .contains("Render")
        .contains("Supabase")
        .contains("Brevo")
        .doesNotContain("Amazon");
  }
}
