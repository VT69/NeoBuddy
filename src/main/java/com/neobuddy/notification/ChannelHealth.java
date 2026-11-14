package com.neobuddy.notification;

import com.neobuddy.subscriber.ChannelType;
import java.time.Instant;
import java.util.List;

/**
 * How each alert channel's provider has been doing, from the outcome of recent sends. Passive on
 * purpose: health checks run every few seconds and must not send anything or call a provider.
 */
public interface ChannelHealth {

  /** After this many retryable failures in a row, with no success in between, it is DEGRADED. */
  int DEGRADED_AFTER = 3;

  enum State {
    /** No send since the application started. */
    IDLE,
    OK,
    /** The provider failed repeatedly; alerts are piling up in RETRY. */
    DEGRADED
  }

  /**
   * @param provider which implementation sends: ntfy, ses, log or discord
   * @param consecutiveFailures retryable failures since the last success
   * @param lastFailureReason short technical reason, never an address
   */
  record ChannelState(
      ChannelType type,
      String provider,
      State state,
      int consecutiveFailures,
      Instant lastSuccess,
      Instant lastFailure,
      String lastFailureReason) {}

  /** One entry per channel the application can send to. */
  List<ChannelState> states();
}
