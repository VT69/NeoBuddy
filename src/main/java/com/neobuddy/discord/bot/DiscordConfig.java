package com.neobuddy.discord.bot;

import com.neobuddy.notification.AlertHistory;
import com.neobuddy.shared.college.CollegeRepository;
import com.neobuddy.subscriber.ChannelDirectory;
import com.neobuddy.subscriber.ChannelVerificationService;
import com.neobuddy.subscriber.SubscriberService;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * The Discord bot, created only when a token is configured ({@link DiscordEnabled}). JDA connects
 * in the background, so a Discord outage never stops the application from starting; commands are
 * registered once the bot is ready. It asks for no optional gateway intents, so it receives only
 * guild events (the server and its channels): no messages and no member list.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(DiscordEnabled.class)
@EnableConfigurationProperties(DiscordProperties.class)
class DiscordConfig {

  private static final Logger log = LoggerFactory.getLogger(DiscordConfig.class);
  private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

  @Bean
  SlashCommands slashCommands(
      ChannelVerificationService verification,
      ChannelDirectory directory,
      SubscriberService subscribers,
      AlertHistory history,
      ObjectProvider<DiscordDmChannel> dms) {
    // Lazy: the DM channel needs JDA, and JDA needs these commands' listener.
    return new SlashCommands(
        verification,
        directory,
        subscribers,
        history,
        (userId, text) -> dms.getObject().sendText(userId, text));
  }

  @Bean
  SlashCommandListener slashCommandListener(SlashCommands commands, DiscordProperties properties) {
    return new SlashCommandListener(commands, properties.guildId());
  }

  @Bean(destroyMethod = "")
  JDA jda(DiscordProperties properties, SlashCommandListener listener) {
    require(properties.guildId(), "neobuddy.discord.guild-id (NEOBUDDY_DISCORD_GUILD_ID)");
    require(
        properties.opsChannelId(),
        "neobuddy.discord.ops-channel-id (NEOBUDDY_DISCORD_OPS_CHANNEL_ID)");
    // No optional intents: JDA always subscribes to guild events (the GUILDS bit), which is all
    // the bot needs. No messages, no message content, no member list.
    return JDABuilder.createLight(properties.token(), EnumSet.noneOf(GatewayIntent.class))
        .setMemberCachePolicy(MemberCachePolicy.NONE)
        .setChunkingFilter(ChunkingFilter.NONE)
        .addEventListeners(listener)
        .build();
  }

  @Bean
  DiscordDmChannel discordDmChannel(JDA jda) {
    return new DiscordDmChannel(jda);
  }

  @Bean
  OpsChannel opsChannel(JDA jda, DiscordProperties properties) {
    return new OpsChannel(jda, properties.opsChannelId());
  }

  @Bean
  AdminNotifier adminNotifier(
      OpsChannel ops, CollegeRepository colleges, DiscordProperties properties, Clock clock) {
    return new AdminNotifier(ops, colleges, properties, clock);
  }

  @Bean
  JdaShutdown jdaShutdown(JDA jda) {
    return new JdaShutdown(jda);
  }

  private static void require(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be set when the Discord bot token is");
    }
  }

  /** Disconnects cleanly when the application stops, waiting briefly for queued requests. */
  static final class JdaShutdown implements AutoCloseable {

    private final JDA jda;

    JdaShutdown(JDA jda) {
      this.jda = jda;
    }

    @Override
    public void close() throws InterruptedException {
      jda.shutdown();
      if (!jda.awaitShutdown(SHUTDOWN_WAIT)) {
        log.warn("Discord: shutdown took longer than {}; forcing it", SHUTDOWN_WAIT);
        jda.shutdownNow();
      }
    }
  }
}
