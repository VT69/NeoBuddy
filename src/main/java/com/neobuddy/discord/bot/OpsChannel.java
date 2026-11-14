package com.neobuddy.discord.bot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The #ops channel. Posting is best effort: a failure is logged, never thrown. */
class OpsChannel {

  private static final Logger log = LoggerFactory.getLogger(OpsChannel.class);

  private final JDA jda;
  private final String channelId;

  OpsChannel(JDA jda, String channelId) {
    this.jda = jda;
    this.channelId = channelId;
  }

  void post(String text) {
    TextChannel channel = jda.getTextChannelById(channelId);
    if (channel == null) {
      log.warn(
          "Discord: #ops channel {} not found (bot not connected yet, or wrong id)", channelId);
      return;
    }
    channel
        .sendMessage(text)
        .queue(
            sent -> {},
            failure ->
                log.warn(
                    "Discord: posting to #ops failed: {}", failure.getClass().getSimpleName()));
  }
}
