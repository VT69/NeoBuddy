package com.neobuddy.discord.bot;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** True when a bot token is configured. An empty token (the default) switches Discord off. */
class DiscordEnabled extends SpringBootCondition {

  static final String TOKEN = "neobuddy.discord.token";

  @Override
  public ConditionOutcome getMatchOutcome(
      ConditionContext context, AnnotatedTypeMetadata metadata) {
    String token = context.getEnvironment().getProperty(TOKEN, "");
    return token.isBlank()
        ? ConditionOutcome.noMatch("no Discord bot token (" + TOKEN + ")")
        : ConditionOutcome.match("Discord bot token configured");
  }
}
