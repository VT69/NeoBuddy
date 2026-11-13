package com.neobuddy.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DeliveryPropertiesTests {

  private static DeliveryProperties defaults() {
    return new DeliveryProperties(
        true, Duration.ofSeconds(10), 50, 6, Duration.ofMinutes(1), Duration.ofMinutes(5));
  }

  @Test
  void backoffDoublesFromOneMinute() {
    DeliveryProperties properties = defaults();

    assertThat(IntStream.rangeClosed(1, 6).mapToObj(properties::backoffAfter))
        .containsExactly(
            Duration.ofMinutes(1),
            Duration.ofMinutes(2),
            Duration.ofMinutes(4),
            Duration.ofMinutes(8),
            Duration.ofMinutes(16),
            Duration.ofMinutes(32));
  }

  @Test
  void backoffFollowsTheConfiguredBase() {
    DeliveryProperties properties =
        new DeliveryProperties(
            true, Duration.ofSeconds(10), 50, 3, Duration.ofSeconds(30), Duration.ofMinutes(5));

    assertThat(properties.backoffAfter(1)).isEqualTo(Duration.ofSeconds(30));
    assertThat(properties.backoffAfter(3)).isEqualTo(Duration.ofMinutes(2));
  }

  @Test
  void backoffNeverOverflows() {
    assertThat(defaults().backoffAfter(Integer.MAX_VALUE)).isPositive();
    assertThat(defaults().backoffAfter(0)).isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  void attemptsAreExhaustedAtTheMaximum() {
    DeliveryProperties properties = defaults();

    assertThat(properties.isExhausted(5)).isFalse();
    assertThat(properties.isExhausted(6)).isTrue();
    assertThat(properties.isExhausted(7)).isTrue();
  }

  @Test
  void rejectsSettingsThatWouldStopDelivery() {
    assertThatThrownBy(() ->
            new DeliveryProperties(
                true, Duration.ofSeconds(10), 0, 6, Duration.ofMinutes(1), Duration.ofMinutes(5)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new DeliveryProperties(
                true, Duration.ofSeconds(10), 50, 6, Duration.ofMinutes(1), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
