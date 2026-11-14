package com.neobuddy.discord.bot;

import com.neobuddy.notification.Alert;
import com.neobuddy.notification.AlertFormat;
import com.neobuddy.notification.DeliveryResult;
import com.neobuddy.notification.NotificationChannel;
import com.neobuddy.processing.EventType;
import com.neobuddy.subscriber.ChannelAddress;
import com.neobuddy.subscriber.ChannelType;
import java.util.function.Function;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;

/**
 * Alerts by Discord direct message: open the private channel with the student and send an embed
 * with the headline, company, their row and the time.
 *
 * <ul>
 *   <li>50007 "Cannot send messages to this user" (DMs from server members switched off, or the
 *       student left the server): PERMANENT {@value #DMS_CLOSED}; the dashboard explains the fix.
 *   <li>Unknown user: PERMANENT.
 *   <li>Discord 5xx, network failures, the bot not connected: RETRYABLE. Discord's rate limits
 *       never fail a send: JDA waits for the next free slot before it completes.
 *   <li>Any other refusal: PERMANENT.
 * </ul>
 *
 * Reasons carry codes only, never the Discord user id.
 */
class DiscordDmChannel implements NotificationChannel, DiscordDms {

  static final String DMS_CLOSED = "DISCORD_DMS_CLOSED";
  static final String UNKNOWN_USER = "DISCORD_UNKNOWN_USER";
  static final String NOT_CONNECTED = "DISCORD_NOT_CONNECTED";

  private static final int OFFER = 0x1D7A46;
  private static final int SHORTLIST = 0x1F5FBF;
  private static final int OTHER = 0x5D6676;

  private final JDA jda;

  DiscordDmChannel(JDA jda) {
    this.jda = jda;
  }

  @Override
  public ChannelType type() {
    return ChannelType.DISCORD;
  }

  @Override
  public DeliveryResult send(ChannelAddress to, Alert alert) {
    MessageEmbed embed = embed(alert);
    return deliver(to.value(), channel -> channel.sendMessageEmbeds(embed));
  }

  @Override
  public DeliveryResult sendText(String discordUserId, String text) {
    return deliver(discordUserId, channel -> channel.sendMessage(text));
  }

  static MessageEmbed embed(Alert alert) {
    EmbedBuilder embed =
        new EmbedBuilder()
            .setTitle(AlertFormat.headline(alert), AlertFormat.mailboxLink(alert))
            .setDescription("```\n" + AlertFormat.snippet(alert).replace("`", "'") + "\n```")
            .setColor(
                alert.test()
                    ? OTHER
                    : alert.eventType() == EventType.OFFER
                        ? OFFER
                        : alert.eventType() == EventType.SHORTLIST ? SHORTLIST : OTHER)
            .setTimestamp(alert.receivedAt())
            .setFooter("NeoBuddy");
    String company = AlertFormat.oneLine(alert.company());
    if (!alert.test() && !company.isEmpty()) {
      embed.addField("Company", company, true);
    }
    embed.addField("Received", AlertFormat.receivedAt(alert), true);
    return embed.build();
  }

  private DeliveryResult deliver(
      String discordUserId, Function<PrivateChannel, MessageCreateAction> message) {
    if (jda.getStatus() != JDA.Status.CONNECTED) {
      return DeliveryResult.retryable(NOT_CONNECTED);
    }
    try {
      User user = jda.retrieveUserById(discordUserId).complete();
      PrivateChannel channel = user.openPrivateChannel().complete();
      message.apply(channel).complete();
      return DeliveryResult.success();
    } catch (ErrorResponseException e) {
      return classify(e);
    } catch (RuntimeException e) {
      return DeliveryResult.retryable("DISCORD " + e.getClass().getSimpleName());
    }
  }

  static DeliveryResult classify(ErrorResponseException e) {
    ErrorResponse response = e.getErrorResponse();
    if (response == ErrorResponse.CANNOT_SEND_TO_USER) {
      return DeliveryResult.permanent(DMS_CLOSED);
    }
    if (response == ErrorResponse.UNKNOWN_USER) {
      return DeliveryResult.permanent(UNKNOWN_USER);
    }
    if (e.isServerError()) {
      return DeliveryResult.retryable("DISCORD_SERVER_ERROR");
    }
    return DeliveryResult.permanent("DISCORD " + e.getErrorCode());
  }
}
