package com.neobuddy.shared.observability;

import org.springframework.boot.health.contributor.Status;

/**
 * Health statuses beyond Spring Boot's own.
 *
 * <p>{@link #DEGRADED} means one part (a source inbox, an alert channel) needs a person, while the
 * application itself serves. application.yml ranks it below UP for the public aggregate, so a
 * lapsed inbox can never fail a deploy or page the "site is down" alarm, and above UP in the {@code
 * ops} health group the admin console reads.
 */
public final class HealthStatuses {

  public static final Status DEGRADED = new Status("DEGRADED");

  private HealthStatuses() {}
}
