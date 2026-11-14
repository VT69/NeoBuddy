package com.neobuddy.web.support;

/** A rate limit is used up. */
public class RateLimitedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient RateLimiter.Limit limit;
  private final long retryAfterSeconds;

  public RateLimitedException(RateLimiter.Limit limit, long retryAfterSeconds) {
    super("Rate limit " + limit + " used up");
    this.limit = limit;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public RateLimiter.Limit limit() {
    return limit;
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
