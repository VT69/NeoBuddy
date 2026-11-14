package com.neobuddy.notification.delivery;

import com.neobuddy.notification.ChannelHealth;
import com.neobuddy.notification.DeliveryResult;
import com.neobuddy.notification.NotificationChannel;
import com.neobuddy.subscriber.ChannelType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Remembers how the last sends of each channel went, in memory, per instance. Only retryable
 * failures count against a provider: a permanent one (DMs closed, a rejected address) is about one
 * student's channel, not about the provider.
 */
@Component
class ChannelOutcomes implements ChannelHealth {

  private static final class Tracker {
    int consecutiveFailures;
    Instant lastSuccess;
    Instant lastFailure;
    String lastFailureReason;
  }

  private final Map<ChannelType, String> providers = new EnumMap<>(ChannelType.class);
  private final Map<ChannelType, Tracker> trackers = new EnumMap<>(ChannelType.class);

  ChannelOutcomes(List<NotificationChannel> senders) {
    for (NotificationChannel sender : senders) {
      providers.put(sender.type(), sender.provider());
      trackers.put(sender.type(), new Tracker());
    }
  }

  synchronized void record(ChannelType type, DeliveryResult result, Instant now) {
    Tracker tracker = type == null ? null : trackers.get(type);
    if (tracker == null) {
      return;
    }
    switch (result.outcome()) {
      case SUCCESS -> {
        tracker.consecutiveFailures = 0;
        tracker.lastSuccess = now;
      }
      case RETRYABLE -> {
        tracker.consecutiveFailures++;
        tracker.lastFailure = now;
        tracker.lastFailureReason = result.reason();
      }
      case PERMANENT -> {
        // About the student's channel, not the provider.
      }
    }
  }

  @Override
  public synchronized List<ChannelState> states() {
    List<ChannelState> states = new ArrayList<>();
    trackers.forEach((type, t) -> {
      State state;
      if (t.consecutiveFailures >= DEGRADED_AFTER) {
        state = State.DEGRADED;
      } else if (t.lastSuccess == null && t.lastFailure == null) {
        state = State.IDLE;
      } else {
        state = State.OK;
      }
      states.add(
          new ChannelState(
              type,
              providers.get(type),
              state,
              t.consecutiveFailures,
              t.lastSuccess,
              t.lastFailure,
              t.lastFailureReason));
    });
    return List.copyOf(states);
  }
}
