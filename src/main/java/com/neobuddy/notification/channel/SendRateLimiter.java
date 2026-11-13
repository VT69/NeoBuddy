package com.neobuddy.notification.channel;

import com.neobuddy.notification.DeliveryResult;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.util.function.Supplier;

/**
 * Keeps a channel under its provider's rate limit. A send waits for its turn; only if the wait gets
 * too long is it reported as a retryable failure without the provider being called.
 */
final class SendRateLimiter {

  static final String RATE_LIMITED = "RATE_LIMITED";

  private final RateLimiter limiter;

  SendRateLimiter(String name, ChannelProperties.RateLimit settings) {
    this.limiter =
        RateLimiter.of(
            name,
            RateLimiterConfig.custom()
                .limitForPeriod(settings.limitForPeriod())
                .limitRefreshPeriod(settings.refreshPeriod())
                .timeoutDuration(settings.timeout())
                .build());
  }

  DeliveryResult send(Supplier<DeliveryResult> send) {
    return limiter.acquirePermission() ? send.get() : DeliveryResult.retryable(RATE_LIMITED);
  }
}
