package com.neobuddy.testsupport;

import java.util.Map;
import java.util.Optional;

/**
 * An already running PostgreSQL to test against, taken from {@code NEOBUDDY_TEST_DB_URL}, {@code
 * NEOBUDDY_TEST_DB_USER} and {@code NEOBUDDY_TEST_DB_PASSWORD}. Tests wipe this database, so its
 * name must end in {@code _test}.
 */
record ExternalTestDatabase(String url, String username, String password) {

  static final String URL_VAR = "NEOBUDDY_TEST_DB_URL";
  static final String USER_VAR = "NEOBUDDY_TEST_DB_USER";
  static final String PASSWORD_VAR = "NEOBUDDY_TEST_DB_PASSWORD";
  static final String REQUIRED_SUFFIX = "_test";

  private static final String JDBC_PREFIX = "jdbc:postgresql:";

  /** Empty when no external database is configured, so Testcontainers should be used. */
  static Optional<ExternalTestDatabase> from(Map<String, String> env) {
    String url = env.get(URL_VAR);
    if (url == null || url.isBlank()) {
      return Optional.empty();
    }
    String username = env.get(USER_VAR);
    String password = env.get(PASSWORD_VAR);
    if (username == null || username.isBlank() || password == null) {
      throw new IllegalStateException(
          URL_VAR + " is set, so " + USER_VAR + " and " + PASSWORD_VAR + " must be set too");
    }
    requireTestDatabase(url);
    return Optional.of(new ExternalTestDatabase(url.strip(), username, password));
  }

  /** Refuses any database that tests must not wipe. */
  static void requireTestDatabase(String jdbcUrl) {
    String name = databaseName(jdbcUrl);
    if (!name.endsWith(REQUIRED_SUFFIX)) {
      throw new IllegalStateException(
          "Refusing to clean database '"
              + name
              + "': the test database name must end in "
              + REQUIRED_SUFFIX);
    }
  }

  /** Database name of a {@code jdbc:postgresql:} URL, without host, port or parameters. */
  static String databaseName(String jdbcUrl) {
    String url = jdbcUrl == null ? "" : jdbcUrl.strip();
    if (!url.startsWith(JDBC_PREFIX)) {
      throw new IllegalStateException(URL_VAR + " must start with " + JDBC_PREFIX);
    }
    String rest = url.substring(JDBC_PREFIX.length());
    int end = indexOfAny(rest, '?', '#');
    if (end >= 0) {
      rest = rest.substring(0, end);
    }
    // Forms: //host:port/database, //host/database, or just database.
    if (rest.startsWith("//")) {
      int slash = rest.indexOf('/', 2);
      rest = slash < 0 ? "" : rest.substring(slash + 1);
    }
    if (rest.isBlank()) {
      throw new IllegalStateException(URL_VAR + " must name a database");
    }
    return rest;
  }

  private static int indexOfAny(String value, char first, char second) {
    int a = value.indexOf(first);
    int b = value.indexOf(second);
    if (a < 0) {
      return b;
    }
    return b < 0 ? a : Math.min(a, b);
  }

  /** Never print the password. */
  @Override
  public String toString() {
    return "ExternalTestDatabase[url=" + url + ", username=" + username + "]";
  }
}
