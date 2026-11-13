package com.neobuddy.ingestion.source;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls every active source inbox on a fixed delay. ShedLock makes sure only one instance polls at
 * a time, so two instances never fetch the same page; if both did, the Message-ID dedupe would
 * still keep the result correct.
 */
@Component
@ConditionalOnProperty(
    prefix = "neobuddy.ingestion",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
class MailPoller {

  private final SourceSync sync;

  MailPoller(SourceSync sync) {
    this.sync = sync;
  }

  @Scheduled(
      initialDelayString = "${neobuddy.ingestion.initial-delay:PT15S}",
      fixedDelayString = "${neobuddy.ingestion.poll-interval:PT2M}")
  @SchedulerLock(name = "mail-poller", lockAtMostFor = "PT15M", lockAtLeastFor = "PT5S")
  void poll() {
    sync.syncAll();
  }
}
