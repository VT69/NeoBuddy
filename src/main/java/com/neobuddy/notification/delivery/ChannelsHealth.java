package com.neobuddy.notification.delivery;

import com.neobuddy.notification.ChannelHealth;
import com.neobuddy.notification.ChannelHealth.ChannelState;
import com.neobuddy.shared.observability.HealthStatuses;
import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * One health indicator per alert channel provider, under {@code channels}: DEGRADED after {@value
 * ChannelHealth#DEGRADED_AFTER} retryable failures in a row, UP otherwise. Built from the outcomes
 * of real sends, so checking health never sends anything.
 */
@Component("channels")
class ChannelsHealth implements CompositeHealthContributor {

  private final ChannelHealth channels;

  ChannelsHealth(ChannelHealth channels) {
    this.channels = channels;
  }

  @Override
  public HealthContributor getContributor(String name) {
    return channels.states().stream()
        .filter(state -> name(state).equals(name))
        .findFirst()
        .<HealthContributor>map(ChannelsHealth::indicator)
        .orElse(null);
  }

  @Override
  public Stream<Entry> stream() {
    return channels.states().stream().map(state -> new Entry(name(state), indicator(state)));
  }

  private static String name(ChannelState state) {
    return state.type().name().toLowerCase(Locale.ROOT);
  }

  private static HealthIndicator indicator(ChannelState state) {
    return () -> {
      Health.Builder health =
          state.state() == ChannelHealth.State.DEGRADED
              ? Health.status(HealthStatuses.DEGRADED)
              : Health.up();
      health
          .withDetail("provider", state.provider())
          .withDetail("state", state.state().name())
          .withDetail("consecutiveFailures", state.consecutiveFailures());
      if (state.lastSuccess() != null) {
        health.withDetail("lastSuccess", state.lastSuccess().toString());
      }
      if (state.lastFailure() != null) {
        health.withDetail("lastFailure", state.lastFailure().toString());
        health.withDetail("lastFailureReason", String.valueOf(state.lastFailureReason()));
      }
      return health.build();
    };
  }
}
