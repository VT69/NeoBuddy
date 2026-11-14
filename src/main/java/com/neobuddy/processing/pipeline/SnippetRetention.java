package com.neobuddy.processing.pipeline;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Clears each match's snippet (the student's own row) once it is older than the retention period,
 * 180 days by default, as the privacy policy promises. The match itself stays, so the student's
 * alert history still shows the company, type and date.
 */
@Component
class SnippetRetention {

  private static final Logger log = LoggerFactory.getLogger(SnippetRetention.class);

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final Duration retention;

  SnippetRetention(
      JdbcTemplate jdbc,
      Clock clock,
      @Value("${neobuddy.matching.snippet-retention:P180D}") Duration retention) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.retention = retention;
  }

  /** Every night at 03:15 IST. */
  @Scheduled(cron = "0 45 21 * * *", zone = "UTC")
  @SchedulerLock(name = "snippet-retention", lockAtMostFor = "PT30M")
  void scheduled() {
    purge();
  }

  /**
   * @return how many snippets were cleared
   */
  int purge() {
    OffsetDateTime cutoff =
        OffsetDateTime.ofInstant(clock.instant().minus(retention), ZoneOffset.UTC);
    int cleared =
        jdbc.update(
            "update match_hit set context_snippet = null"
                + " where created_at < ? and context_snippet is not null",
            cutoff);
    if (cleared > 0) {
      log.info("Cleared {} match snippets older than {}", cleared, retention);
    }
    return cleared;
  }
}
