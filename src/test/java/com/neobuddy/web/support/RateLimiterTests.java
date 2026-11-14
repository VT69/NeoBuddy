package com.neobuddy.web.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neobuddy.web.support.RateLimiter.Limit;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RateLimiterTests {

  private final RateLimiter limiter =
      new RateLimiter(Clock.fixed(Instant.parse("2026-11-13T10:00:00Z"), ZoneOffset.UTC));

  @Test
  void eachLimitAllowsItsCapacityPerKeyThenSaysWhenToRetry() {
    for (int i = 0; i < 3; i++) {
      limiter.consume(Limit.OTP_SEND, "user-a");
    }

    assertThatThrownBy(() -> limiter.consume(Limit.OTP_SEND, "user-a"))
        .isInstanceOfSatisfying(
            RateLimitedException.class,
            e -> {
              assertThat(e.limit()).isEqualTo(Limit.OTP_SEND);
              assertThat(e.retryAfterSeconds()).isBetween(1L, 3601L);
            });
    // Other users and other limits are counted separately.
    limiter.consume(Limit.OTP_SEND, "user-b");
    limiter.consume(Limit.TEST_ALERT, "user-a");
  }

  @Test
  void theLimitsAreTheOnesAskedFor() {
    assertThat(Limit.OTP_SEND.capacity()).isEqualTo(3);
    assertThat(Limit.TEST_ALERT.capacity()).isEqualTo(5);
    assertThat(Limit.OTP_SEND.period()).hasHours(1);
    assertThat(Limit.TEST_ALERT.period()).hasHours(1);
  }
}
