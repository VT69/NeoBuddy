package com.neobuddy.web.support;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-memory rate limits (Bucket4j), one token bucket per limit and key. Lives in this instance's
 * memory, so a restart resets it and a second instance would have its own; enough for one instance.
 * Idle buckets are dropped every hour so keys such as IP addresses cannot pile up.
 */
@Component
public class RateLimiter {

  /** The limits the web layer applies. */
  public enum Limit {
    /** Email verification codes sent, per user. */
    OTP_SEND(3, Duration.ofHours(1)),
    /** Test alerts, per user. */
    TEST_ALERT(5, Duration.ofHours(1)),
    /** "Request your college" submissions, per client address. */
    COLLEGE_REQUEST(5, Duration.ofHours(1));

    private final int capacity;
    private final Duration period;

    Limit(int capacity, Duration period) {
      this.capacity = capacity;
      this.period = period;
    }

    public int capacity() {
      return capacity;
    }

    public Duration period() {
      return period;
    }
  }

  private record Entry(Bucket bucket, Instant lastUsed) {}

  private final Map<String, Entry> buckets = new ConcurrentHashMap<>();
  private final Clock clock;

  public RateLimiter(Clock clock) {
    this.clock = clock;
  }

  /**
   * Takes one token.
   *
   * @throws RateLimitedException if the limit is used up, saying when to try again
   */
  public void consume(Limit limit, String key) {
    Entry entry =
        buckets.compute(
            limit.name() + ":" + key,
            (k, existing) ->
                new Entry(
                    existing == null ? newBucket(limit) : existing.bucket(), clock.instant()));
    ConsumptionProbe probe = entry.bucket().tryConsumeAndReturnRemaining(1);
    if (!probe.isConsumed()) {
      Duration wait = Duration.ofNanos(probe.getNanosToWaitForRefill());
      throw new RateLimitedException(limit, wait.toSeconds() + 1);
    }
  }

  @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
  void dropIdleBuckets() {
    Instant now = clock.instant();
    buckets
        .entrySet()
        .removeIf(e -> e.getValue().lastUsed().plus(limitOf(e.getKey()).period()).isBefore(now));
  }

  private static Limit limitOf(String key) {
    return Limit.valueOf(key.substring(0, key.indexOf(':')));
  }

  private static Bucket newBucket(Limit limit) {
    return Bucket.builder()
        .addLimit(l -> l.capacity(limit.capacity()).refillGreedy(limit.capacity(), limit.period()))
        .build();
  }
}
