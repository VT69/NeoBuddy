package com.neobuddy.ingestion.source;

import com.neobuddy.ingestion.SourceAdmin;
import com.neobuddy.ingestion.SourceAdmin.SourceView;
import com.neobuddy.shared.observability.HealthStatuses;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * One health indicator per source inbox, under {@code sources}, read from the database on every
 * check (no call to Google). An inbox that lost its authorisation or has not been polled for too
 * long is DEGRADED; a disconnected one or one of a paused college is UP with its state as detail.
 * Details name the college and source id, never the address.
 */
@Component("sources")
class SourcesHealth implements CompositeHealthContributor {

  private static final Logger log = LoggerFactory.getLogger(SourcesHealth.class);

  private final SourceAdmin admin;

  SourcesHealth(SourceAdmin admin) {
    this.admin = admin;
  }

  @Override
  public HealthContributor getContributor(String name) {
    List<SourceView> all = sourcesOrNull();
    return (all == null ? Stream.<SourceView>empty() : all.stream())
        .filter(source -> name(source).equals(name))
        .findFirst()
        .<HealthContributor>map(SourcesHealth::indicator)
        .orElse(null);
  }

  @Override
  public Stream<Entry> stream() {
    List<SourceView> all = sourcesOrNull();
    if (all == null) {
      return Stream.of(
          new Entry(
              "listing",
              (HealthIndicator)
                  () ->
                      Health.status(HealthStatuses.DEGRADED)
                          .withDetail("error", "unreadable")
                          .build()));
    }
    if (all.isEmpty()) {
      return Stream.of(
          new Entry("none", (HealthIndicator) () -> Health.up().withDetail("sources", 0).build()));
    }
    return all.stream().map(source -> new Entry(name(source), indicator(source)));
  }

  /** Whatever happens here, the health endpoint itself must keep answering. */
  private List<SourceView> sourcesOrNull() {
    try {
      return admin.sources();
    } catch (RuntimeException e) {
      log.warn("Could not list source inboxes for health: {}", e.getClass().getName());
      return null;
    }
  }

  private static String name(SourceView source) {
    return source.collegeSlug() + "-" + source.id().toString().substring(0, 8);
  }

  private static HealthIndicator indicator(SourceView source) {
    return () -> {
      Health.Builder health =
          source.health().needsAttention() ? Health.status(HealthStatuses.DEGRADED) : Health.up();
      health
          .withDetail("college", source.collegeSlug())
          .withDetail("sourceId", source.id().toString())
          .withDetail("state", source.health().name());
      if (source.lastSyncedAt() != null) {
        health.withDetail("lastSyncedAt", source.lastSyncedAt().toString());
      }
      return health.build();
    };
  }
}
