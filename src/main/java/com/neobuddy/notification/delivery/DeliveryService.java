package com.neobuddy.notification.delivery;

import com.neobuddy.notification.NotificationChannel;
import com.neobuddy.processing.MatchHit;
import com.neobuddy.processing.MatchesFound;
import com.neobuddy.processing.StoredMatch;
import com.neobuddy.processing.StoredMatches;
import com.neobuddy.subscriber.ChannelDirectory;
import com.neobuddy.subscriber.ChannelType;
import com.neobuddy.subscriber.SubscriberView.ChannelView;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fans every match out to its user's channels: one {@code delivery} row per verified, enabled
 * channel. The rows are the outbox the {@link DeliveryDispatcher} works through.
 *
 * <p><b>Why a plain, synchronous listener on {@link MatchesFound}.</b> Processing publishes the
 * event inside the transaction that writes the {@code match_hit} rows, and a plain {@code
 * EventListener} runs right there, in that transaction. So matches and deliveries commit together
 * or not at all: a delivery can never exist for a match that was rolled back, and a committed match
 * can never be left without its deliveries by a crash in between. An after-commit listener would
 * open exactly that gap, and would also put the event, with every student's row in it, into the
 * event registry.
 *
 * <p>The deliveries are created from the stored matches, not from the event's contents, so a hit
 * that was not stored (its user was deleted meanwhile) gets none; the foreign key from {@code
 * delivery} to {@code match_hit} is the last line of defence. Handling the same event again changes
 * nothing: {@code unique (match_id, channel_id)} decides.
 *
 * <p>If this fails, the email's whole transaction rolls back and the email is marked FAILED, rather
 * than keeping matches nobody is told about.
 */
@Service
class DeliveryService {

  private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

  private final StoredMatches matches;
  private final ChannelDirectory channels;
  private final DeliveryStore deliveries;
  private final Clock clock;
  private final Set<ChannelType> sendable;

  DeliveryService(
      StoredMatches matches,
      ChannelDirectory channels,
      DeliveryStore deliveries,
      Clock clock,
      List<NotificationChannel> senders) {
    this.matches = matches;
    this.channels = channels;
    this.deliveries = deliveries;
    this.clock = clock;
    Set<ChannelType> types = EnumSet.noneOf(ChannelType.class);
    senders.forEach(sender -> types.add(sender.type()));
    this.sendable = types;
  }

  @EventListener
  @Transactional
  public void on(MatchesFound event) {
    Set<UUID> matchedUsers =
        event.hits().stream().map(MatchHit::userId).collect(Collectors.toSet());
    Instant now = clock.instant();
    int created = 0;
    for (StoredMatch match : matches.ofEmail(event.rfcMessageId())) {
      if (!matchedUsers.contains(match.userId())) {
        continue;
      }
      for (ChannelView channel : channels.deliverable(match.userId())) {
        // A channel type nothing can send to yet would only pile up rows that end DEAD.
        if (sendable.contains(channel.type())
            && deliveries.insertIfAbsent(match.id(), channel.id(), now)) {
          created++;
        }
      }
    }
    log.info("Email {}: {} deliveries queued", event.rfcMessageId(), created);
  }
}
