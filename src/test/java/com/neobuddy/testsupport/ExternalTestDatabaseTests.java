package com.neobuddy.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ExternalTestDatabaseTests {

  private static final String URL = "jdbc:postgresql://localhost:5432/neobuddy_test";

  @Test
  void noUrlMeansTestcontainers() {
    assertThat(ExternalTestDatabase.from(Map.of())).isEmpty();
    assertThat(ExternalTestDatabase.from(Map.of(ExternalTestDatabase.URL_VAR, " "))).isEmpty();
  }

  @Test
  void readsUrlUserAndPassword() {
    assertThat(ExternalTestDatabase.from(env(URL)))
        .contains(new ExternalTestDatabase(URL, "tester", "s3cret"));
  }

  @Test
  void urlWithoutCredentialsIsRejected() {
    assertThatThrownBy(() -> ExternalTestDatabase.from(Map.of(ExternalTestDatabase.URL_VAR, URL)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ExternalTestDatabase.USER_VAR);
  }

  @ParameterizedTest
  @CsvSource({
    "jdbc:postgresql://localhost:5432/neobuddy_test, neobuddy_test",
    "jdbc:postgresql://localhost/neobuddy_test?sslmode=disable, neobuddy_test",
    "'jdbc:postgresql://db1:5432,db2:5432/neobuddy_test', neobuddy_test",
    "jdbc:postgresql:neobuddy_test, neobuddy_test",
    "jdbc:postgresql://localhost:5432/neobuddy, neobuddy",
  })
  void extractsDatabaseName(String url, String expected) {
    assertThat(ExternalTestDatabase.databaseName(url)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbc:postgresql://localhost:5432/neobuddy",
        "jdbc:postgresql://localhost:5432/neobuddy_test_backup",
        "jdbc:postgresql://localhost:5432/postgres?currentSchema=neobuddy_test",
        "jdbc:postgresql://neobuddy_test:5432/neobuddy",
        "jdbc:postgresql://localhost:5432/",
        "jdbc:mysql://localhost/neobuddy_test",
      })
  void refusesAnythingThatIsNotATestDatabase(String url) {
    assertThatThrownBy(() -> ExternalTestDatabase.from(env(url)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void toStringHidesThePassword() {
    assertThat(new ExternalTestDatabase(URL, "tester", "s3cret").toString())
        .doesNotContain("s3cret");
  }

  private static Map<String, String> env(String url) {
    return Map.of(
        ExternalTestDatabase.URL_VAR, url,
        ExternalTestDatabase.USER_VAR, "tester",
        ExternalTestDatabase.PASSWORD_VAR, "s3cret");
  }
}
