package com.neobuddy.discord.bot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** The whole module hangs on a non-blank token. */
class DiscordEnabledTests {

  @Configuration(proxyBeanMethods = false)
  @Conditional(DiscordEnabled.class)
  static class OnlyWithAToken {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(OnlyWithAToken.class);

  @Test
  void noTokenNoDiscord() {
    runner.run(context -> assertThat(context).doesNotHaveBean(OnlyWithAToken.class));
    runner
        .withPropertyValues("neobuddy.discord.token=")
        .run(context -> assertThat(context).doesNotHaveBean(OnlyWithAToken.class));
    runner
        .withPropertyValues("neobuddy.discord.token=   ")
        .run(context -> assertThat(context).doesNotHaveBean(OnlyWithAToken.class));
  }

  @Test
  void aTokenSwitchesItOn() {
    runner
        .withPropertyValues("neobuddy.discord.token=abc")
        .run(context -> assertThat(context).hasSingleBean(OnlyWithAToken.class));
  }

  @Test
  void thePropertiesNeverPrintTheToken() {
    DiscordProperties properties =
        new DiscordProperties(
            "secret-token",
            "1",
            "2",
            null,
            5,
            java.time.Duration.ofHours(1),
            java.time.Duration.ofMinutes(10));

    assertThat(properties.enabled()).isTrue();
    assertThat(properties.toString()).doesNotContain("secret-token");
  }
}
