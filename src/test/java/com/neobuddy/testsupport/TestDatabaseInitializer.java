package com.neobuddy.testsupport;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Points the application's datasource at the test database before the context starts.
 *
 * <ul>
 *   <li>External mode ({@code NEOBUDDY_TEST_DB_URL} set): uses that PostgreSQL and resets it once
 *       per test run with Flyway clean + migrate. Only allowed in the {@code test} profile and only
 *       for databases whose name ends in {@code _test}.
 *   <li>Default: one Testcontainers Postgres 16 shared by the whole test run.
 * </ul>
 */
public class TestDatabaseInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  static final String TEST_PROFILE = "test";

  private static final Object LOCK = new Object();
  private static Map<String, Object> datasourceProperties;

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(new MapPropertySource("testDatabase", datasource(context)));
  }

  private static Map<String, Object> datasource(ConfigurableApplicationContext context) {
    synchronized (LOCK) {
      if (datasourceProperties == null) {
        datasourceProperties =
            ExternalTestDatabase.from(System.getenv())
                .map(database -> useExternal(database, context))
                .orElseGet(TestDatabaseInitializer::useContainer);
      }
      return datasourceProperties;
    }
  }

  private static Map<String, Object> useExternal(
      ExternalTestDatabase database, ConfigurableApplicationContext context) {
    if (!context.getEnvironment().acceptsProfiles(Profiles.of(TEST_PROFILE))) {
      throw new IllegalStateException(
          "Cleaning a database is only allowed in the '" + TEST_PROFILE + "' profile");
    }
    Flyway flyway =
        Flyway.configure()
            .dataSource(database.url(), database.username(), database.password())
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load();
    flyway.clean();
    flyway.migrate();
    return properties(database.url(), database.username(), database.password());
  }

  private static Map<String, Object> useContainer() {
    // Stopped by Testcontainers' reaper when the test JVM exits.
    PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");
    postgres.start();
    return properties(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private static Map<String, Object> properties(String url, String username, String password) {
    return Map.of(
        "spring.datasource.url", url,
        "spring.datasource.username", username,
        "spring.datasource.password", password);
  }
}
