package com.neobuddy;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobuddy.testsupport.DatabaseTest;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DatabaseTest
class FlywayMigrationTests {

  @Autowired private Flyway flyway;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void flywayAppliedV1() {
    assertThat(flyway.info().applied())
        .filteredOn(migration -> "1".equals(migration.getVersion().getVersion()))
        .singleElement()
        .extracting(MigrationInfo::getState)
        .isEqualTo(MigrationState.SUCCESS);
    assertThat(flyway.info().pending()).isEmpty();
  }

  @Test
  void schemaContainsEveryDesignedTable() {
    List<String> tables =
        jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema = 'public'",
            String.class);

    assertThat(tables)
        .contains(
            "college",
            "app_user",
            "identifier",
            "channel",
            "mail_source",
            "processed_email",
            "match_hit",
            "delivery",
            "link_code",
            "shedlock",
            "event_publication");
  }

  @Test
  void tenantTablesCarryCollegeId() {
    List<String> tables =
        jdbc.queryForList(
            "select table_name from information_schema.columns"
                + " where table_schema = 'public' and column_name = 'college_id'",
            String.class);

    assertThat(tables)
        .containsExactlyInAnyOrder("app_user", "identifier", "mail_source", "processed_email");
  }
}
