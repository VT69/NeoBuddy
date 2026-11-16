package com.neobuddy.testsupport;

import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A database for tests of the {@code render} profile, which talks to PostgreSQL only over TLS.
 *
 * <ul>
 *   <li>Default: a fresh Testcontainers PostgreSQL 16 with TLS switched on (the Debian image's
 *       self-signed certificate) and nothing in it, so the application's own Flyway run creates the
 *       schema at startup over an encrypted connection, as it does against Supabase.
 *   <li>External mode ({@code NEOBUDDY_TEST_DB_URL} set): the shared external test database, set up
 *       by {@link TestDatabaseInitializer}. A local PostgreSQL often has no TLS, so there {@code
 *       sslmode} is relaxed to {@code prefer}; only the default mode proves encryption. CI uses the
 *       default mode.
 * </ul>
 */
public class SslTestDatabaseInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  private static final Object LOCK = new Object();
  private static Map<String, Object> properties;

  /** True when the tests run against a TLS-only container, so encryption can be asserted. */
  public static boolean provesEncryption() {
    return ExternalTestDatabase.from(System.getenv()).isEmpty();
  }

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    if (!provesEncryption()) {
      new TestDatabaseInitializer().initialize(context);
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "relaxedSsl",
                  Map.of("spring.datasource.hikari.data-source-properties.sslmode", "prefer")));
      return;
    }
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(new MapPropertySource("sslTestDatabase", container()));
  }

  private static Map<String, Object> container() {
    synchronized (LOCK) {
      if (properties == null) {
        // Stopped by Testcontainers' reaper when the test JVM exits.
        PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16")
                .withCommand(
                    "postgres",
                    "-c",
                    "fsync=off",
                    "-c",
                    "ssl=on",
                    "-c",
                    "ssl_cert_file=/etc/ssl/certs/ssl-cert-snakeoil.pem",
                    "-c",
                    "ssl_key_file=/etc/ssl/private/ssl-cert-snakeoil.key");
        postgres.start();
        properties =
            Map.of(
                "spring.datasource.url", postgres.getJdbcUrl(),
                "spring.datasource.username", postgres.getUsername(),
                "spring.datasource.password", postgres.getPassword());
      }
      return properties;
    }
  }
}
