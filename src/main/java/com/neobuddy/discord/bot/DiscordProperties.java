package com.neobuddy.discord.bot;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Discord settings ({@code neobuddy.discord.*}).
 *
 * @param token the bot token (SSM {@code DISCORD_TOKEN}); empty switches the module off
 * @param guildId the NeoBuddy server; commands are registered there and only answered there
 * @param opsChannelId the #ops channel admin alerts go to
 * @param inviteUrl the server invite shown next to a link code
 * @param deadAlertThreshold more DEAD deliveries than this within {@code deadAlertWindow} alert ops
 * @param deadAlertWindow the window for counting DEAD deliveries
 * @param failureAlertGap at most one processing-failure post per this period; the rest are counted
 */
@ConfigurationProperties(prefix = "neobuddy.discord")
public record DiscordProperties(
    String token,
    String guildId,
    String opsChannelId,
    String inviteUrl,
    @DefaultValue("5") int deadAlertThreshold,
    @DefaultValue("PT1H") Duration deadAlertWindow,
    @DefaultValue("PT10M") Duration failureAlertGap) {

  public boolean enabled() {
    return token != null && !token.isBlank();
  }

  /** Never prints the token. */
  @Override
  public String toString() {
    return "DiscordProperties[guildId=" + guildId + ", opsChannelId=" + opsChannelId + "]";
  }
}
