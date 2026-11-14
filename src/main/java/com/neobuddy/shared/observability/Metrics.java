package com.neobuddy.shared.observability;

/**
 * Metric names, exactly as docs/ARCHITECTURE.md (Observability) lists them once the Prometheus
 * naming convention is applied: {@code emails.ingested} is exported as {@code
 * emails_ingested_total}, the timer {@code delivery.latency} as {@code delivery_latency_seconds}.
 * Every metric carries the college slug in the {@link #COLLEGE} tag.
 */
public final class Metrics {

  /** Counter; new allow-listed emails recorded by ingestion. */
  public static final String EMAILS_INGESTED = "emails.ingested";

  /** Counter, tag {@code type}: the attachment's detected file type. */
  public static final String EXTRACTION_FAILURES = "extraction.failures";

  /** Counter, tag {@code event}: SHORTLIST, OFFER or OTHER. Newly stored matches only. */
  public static final String MATCHES = "matches";

  /** Timer (histogram): email received by the source inbox to alert accepted by the channel. */
  public static final String DELIVERY_LATENCY = "delivery.latency";

  /** Counter, tag {@code channel}: deliveries given up. */
  public static final String DELIVERIES_DEAD = "deliveries.dead";

  public static final String COLLEGE = "college";

  /** Used when the college of an event can no longer be found. */
  public static final String UNKNOWN = "unknown";

  private Metrics() {}
}
