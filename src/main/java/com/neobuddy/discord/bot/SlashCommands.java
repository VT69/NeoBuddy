package com.neobuddy.discord.bot;

import com.neobuddy.notification.AlertHistory;
import com.neobuddy.notification.AlertSummary;
import com.neobuddy.notification.DeliveryResult;
import com.neobuddy.subscriber.ChannelDirectory;
import com.neobuddy.subscriber.ChannelTarget;
import com.neobuddy.subscriber.ChannelType;
import com.neobuddy.subscriber.ChannelVerificationService;
import com.neobuddy.subscriber.ChannelVerificationService.DiscordLinkResult;
import com.neobuddy.subscriber.IdentifierType;
import com.neobuddy.subscriber.SubscriberService;
import com.neobuddy.subscriber.SubscriberView;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * What each slash command does, given the Discord user who ran it. Every answer is a short text the
 * listener sends back as an ephemeral reply, seen only by that user. A Discord account is found by
 * its user id, the (encrypted) address of a NeoBuddy Discord channel.
 */
class SlashCommands {

  static final String LINK = "link";
  static final String STATUS = "status";
  static final String PAUSE = "pause";
  static final String RESUME = "resume";
  static final String UNLINK = "unlink";

  static final String NOT_LINKED =
      "Your Discord account is not linked to NeoBuddy. On the NeoBuddy dashboard choose"
          + " \"Connect Discord\", then run /link with the code it shows.";

  private static final int LINK_ATTEMPTS = 5;
  private static final Duration LINK_PERIOD = Duration.ofMinutes(10);
  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)
          .withZone(ZoneId.of("Asia/Kolkata"));

  private final ChannelVerificationService verification;
  private final ChannelDirectory directory;
  private final SubscriberService subscribers;
  private final AlertHistory history;
  private final DiscordDms dms;
  private final Map<String, Bucket> linkAttempts = new ConcurrentHashMap<>();

  SlashCommands(
      ChannelVerificationService verification,
      ChannelDirectory directory,
      SubscriberService subscribers,
      AlertHistory history,
      DiscordDms dms) {
    this.verification = verification;
    this.directory = directory;
    this.subscribers = subscribers;
    this.history = history;
    this.dms = dms;
  }

  /**
   * @param code the {@code code} option of /link; null for the other commands
   */
  String handle(String command, String discordUserId, String code) {
    return switch (command) {
      case LINK -> link(discordUserId, code);
      case STATUS -> status(discordUserId);
      case PAUSE -> setEnabled(discordUserId, false);
      case RESUME -> setEnabled(discordUserId, true);
      case UNLINK -> unlink(discordUserId);
      default -> "Unknown command.";
    };
  }

  String link(String discordUserId, String code) {
    Bucket bucket =
        linkAttempts.computeIfAbsent(
            discordUserId,
            id ->
                Bucket.builder()
                    .addLimit(
                        l -> l.capacity(LINK_ATTEMPTS).refillGreedy(LINK_ATTEMPTS, LINK_PERIOD))
                    .build());
    if (!bucket.tryConsume(1)) {
      return "Too many tries. Wait a few minutes and get a fresh code from the dashboard.";
    }
    DiscordLinkResult result = verification.linkDiscord(code, discordUserId);
    return switch (result.status()) {
      case LINKED -> {
        DeliveryResult dm =
            dms.sendText(
                discordUserId,
                "You're linked! NeoBuddy will send your placement alerts here. Use /pause,"
                    + " /resume, /status and /unlink in the NeoBuddy server.");
        yield dm.outcome() == DeliveryResult.Outcome.SUCCESS
            ? "Linked. Check your DMs: we just sent you a confirmation."
            : "Linked, but we could not DM you. In this server's Privacy Settings, switch on"
                + " \"Direct Messages\" (allow DMs from server members), then send a test alert"
                + " from the dashboard.";
      }
      case INVALID_CODE ->
          "That code does not work. Codes work once, for 10 minutes; get a new one on the"
              + " dashboard.";
      case EXPIRED -> "That code has expired. Get a new one on the dashboard.";
      case LINKED_TO_ANOTHER_ACCOUNT ->
          "This Discord account is already linked to another NeoBuddy account. Run /unlink first,"
              + " then link again.";
    };
  }

  String status(String discordUserId) {
    Optional<ChannelTarget> channel = linked(discordUserId);
    if (channel.isEmpty()) {
      return NOT_LINKED;
    }
    SubscriberView user = subscribers.get(channel.get().userId());
    boolean enabled =
        user.channels().stream()
            .filter(c -> c.id().equals(channel.get().channelId()))
            .anyMatch(SubscriberView.ChannelView::enabled);
    StringBuilder reply = new StringBuilder();
    reply.append("**Discord alerts:** ").append(enabled ? "on" : "paused (/resume)").append('\n');
    reply.append("**Identifiers:** ");
    reply.append(
        user.identifiers().isEmpty()
            ? "none yet (add your NeoID on the dashboard)"
            : user.identifiers().stream()
                .map(i -> label(i.type()) + " `" + i.value() + "`")
                .collect(Collectors.joining(", ")));
    reply.append('\n');
    List<AlertSummary> recent = history.recent(user.id(), 0, 3);
    reply.append("**Last alerts:**");
    if (recent.isEmpty()) {
      reply.append(" none yet");
    }
    for (AlertSummary alert : recent) {
      reply
          .append("\n• ")
          .append(title(alert))
          .append(" (")
          .append(WHEN.format(alert.receivedAt()))
          .append(")");
    }
    return reply.toString();
  }

  String setEnabled(String discordUserId, boolean enabled) {
    Optional<ChannelTarget> channel = linked(discordUserId);
    if (channel.isEmpty()) {
      return NOT_LINKED;
    }
    subscribers.setChannelEnabled(channel.get().userId(), channel.get().channelId(), enabled);
    return enabled
        ? "Discord alerts are back on."
        : "Discord alerts paused. Push and email are not affected. /resume switches them back on.";
  }

  String unlink(String discordUserId) {
    Optional<ChannelTarget> channel = linked(discordUserId);
    if (channel.isEmpty()) {
      return NOT_LINKED;
    }
    subscribers.removeChannel(channel.get().userId(), channel.get().channelId());
    return "Unlinked. NeoBuddy will not DM you any more. Your other channels are unchanged.";
  }

  private Optional<ChannelTarget> linked(String discordUserId) {
    return directory.findByAddress(ChannelType.DISCORD, discordUserId);
  }

  private static String title(AlertSummary alert) {
    if (alert.test()) {
      return "Test alert";
    }
    String about = alert.company() != null ? alert.company() : alert.subject();
    return switch (alert.eventType()) {
      case SHORTLIST -> "Shortlisted: " + about;
      case OFFER -> "Offer: " + about;
      case OTHER -> "Mentioned: " + (alert.subject() != null ? alert.subject() : about);
    };
  }

  private static String label(IdentifierType type) {
    return switch (type) {
      case NEO_ID -> "NeoID";
      case REG_NO -> "Reg. no.";
      case COLLEGE_EMAIL -> "College email";
    };
  }
}
