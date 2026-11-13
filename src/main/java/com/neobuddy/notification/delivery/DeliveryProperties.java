package com.neobuddy.notification.delivery;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Outbox settings ({@code neobuddy.delivery.*}).
 *
 * @param enabled false switches the scheduled dispatcher off (tests dispatch by hand)
 * @param pollInterval pause between dispatcher runs
 * @param batchSize how many due deliveries one run claims
 * @param maxAttempts tries per delivery, the first one included; then it is DEAD
 * @param baseBackoff wait after the first failed try; doubles after each further one
 * @param claimTimeout how long a claimed delivery is hidden from other dispatchers; if the instance
 *     that claimed it dies, it becomes due again after this
 */
@ConfigurationProperties(prefix = "neobuddy.delivery")
public record DeliveryProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("PT10S") Duration pollInterval,
    @DefaultValue("50") int batchSize,
    @DefaultValue("6") int maxAttempts,
    @DefaultValue("PT1M") Duration baseBackoff,
    @DefaultValue("PT5M") Duration claimTimeout) {

  private static final int MAX_SHIFT = 20;

  public DeliveryProperties {
    if (batchSize < 1 || maxAttempts < 1) {
      throw new IllegalArgumentException("batch-size and max-attempts must be at least 1");
    }
    if (baseBackoff.isNegative() || claimTimeout.isNegative() || claimTimeout.isZero()) {
      throw new IllegalArgumentException("base-backoff and claim-timeout must be positive");
    }
  }

  /**
   * How long to wait after the given number of failed attempts: base * 2^(attempts - 1), so 1, 2,
   * 4, 8 and 16 minutes with the defaults.
   */
  public Duration backoffAfter(int failedAttempts) {
    int shift = Math.min(Math.max(failedAttempts, 1) - 1, MAX_SHIFT);
    return baseBackoff.multipliedBy(1L << shift);
  }

  /** True once this many attempts have failed and no more are allowed. */
  public boolean isExhausted(int failedAttempts) {
    return failedAttempts >= maxAttempts;
  }
}
