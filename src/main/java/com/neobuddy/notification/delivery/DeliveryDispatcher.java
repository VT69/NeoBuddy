package com.neobuddy.notification.delivery;

import com.neobuddy.ingestion.EmailSummary;
import com.neobuddy.ingestion.ProcessedEmails;
import com.neobuddy.notification.Alert;
import com.neobuddy.notification.DeliveryDead;
import com.neobuddy.notification.DeliveryResult;
import com.neobuddy.notification.NotificationChannel;
import com.neobuddy.processing.StoredMatch;
import com.neobuddy.processing.StoredMatches;
import com.neobuddy.subscriber.ChannelDirectory;
import com.neobuddy.subscriber.ChannelTarget;
import com.neobuddy.subscriber.ChannelType;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Works through the outbox: claims due deliveries, sends each through the {@link
 * NotificationChannel} for its channel type and records how it went.
 *
 * <ul>
 *   <li>sent: SENT
 *   <li>retryable failure: RETRY, due again after base * 2^(attempts - 1); DEAD once the last
 *       allowed attempt has failed
 *   <li>permanent failure: DEAD at once
 * </ul>
 *
 * Every DEAD delivery is announced with {@link DeliveryDead}.
 *
 * <p>Delivery is at-least-once with a very small window: if the process dies after a channel
 * accepted the alert and before SENT is recorded, the alert goes out again when the claim times
 * out. Short of that, two dispatchers never send the same delivery; see {@link
 * DeliveryStore#claimDue}.
 *
 * <p>Log lines carry delivery ids, the Message-ID and the channel type; never an address.
 */
@Component
class DeliveryDispatcher {

  private static final Logger log = LoggerFactory.getLogger(DeliveryDispatcher.class);

  static final String CHANNEL_UNAVAILABLE = "CHANNEL_UNAVAILABLE";
  static final String MATCH_GONE = "MATCH_GONE";
  static final String NO_SENDER = "NO_SENDER";

  private final DeliveryStore deliveries;
  private final StoredMatches matches;
  private final ProcessedEmails emails;
  private final ChannelDirectory channels;
  private final Map<ChannelType, NotificationChannel> senders;
  private final DeliveryProperties properties;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate transaction;
  private final Clock clock;

  DeliveryDispatcher(
      DeliveryStore deliveries,
      StoredMatches matches,
      ProcessedEmails emails,
      ChannelDirectory channels,
      List<NotificationChannel> senders,
      DeliveryProperties properties,
      ApplicationEventPublisher events,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.deliveries = deliveries;
    this.matches = matches;
    this.emails = emails;
    this.channels = channels;
    this.senders = new EnumMap<>(ChannelType.class);
    for (NotificationChannel sender : senders) {
      if (this.senders.put(sender.type(), sender) != null) {
        throw new IllegalStateException("Two notification channels for type " + sender.type());
      }
    }
    this.properties = properties;
    this.events = events;
    this.transaction = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * Claims one batch of due deliveries and sends them.
   *
   * @return how many deliveries were claimed
   */
  int dispatchDue() {
    Instant claimedAt = clock.instant();
    List<ClaimedDelivery> claimed =
        deliveries.claimDue(claimedAt, properties.batchSize(), properties.claimTimeout());
    // Stop well before the claim runs out, so no other dispatcher picks up a row mid-send.
    Instant deadline = claimedAt.plus(properties.claimTimeout().dividedBy(2));
    for (int i = 0; i < claimed.size(); i++) {
      if (clock.instant().isAfter(deadline)) {
        List<ClaimedDelivery> rest = claimed.subList(i, claimed.size());
        log.warn("Dispatch batch ran long; {} deliveries put back", rest.size());
        deliveries.release(rest.stream().map(ClaimedDelivery::id).toList(), clock.instant());
        break;
      }
      dispatch(claimed.get(i));
    }
    return claimed.size();
  }

  private void dispatch(ClaimedDelivery delivery) {
    ChannelType type = null;
    String messageId = null;
    DeliveryResult result;
    try {
      Optional<StoredMatch> match = matches.find(delivery.matchId());
      Optional<EmailSummary> email = match.flatMap(m -> emails.find(m.rfcMessageId()));
      messageId = match.map(StoredMatch::rfcMessageId).orElse(null);
      Optional<ChannelTarget> target = channels.target(delivery.channelId());
      type = target.map(ChannelTarget::type).orElse(null);
      NotificationChannel sender = type == null ? null : senders.get(type);
      if (match.isEmpty() || email.isEmpty()) {
        result = DeliveryResult.permanent(MATCH_GONE);
      } else if (target.isEmpty()) {
        // Unverified, switched off or deleted since the alert was queued.
        result = DeliveryResult.permanent(CHANNEL_UNAVAILABLE);
      } else if (sender == null) {
        result = DeliveryResult.permanent(NO_SENDER);
      } else {
        result = sender.send(target.get().address(), alert(match.get(), email.get()));
      }
    } catch (RuntimeException e) {
      // The class only: a message could carry an address or content.
      result = DeliveryResult.retryable("UNEXPECTED " + e.getClass().getSimpleName());
    }
    record(delivery, type, messageId, result);
  }

  private void record(
      ClaimedDelivery delivery, ChannelType type, String messageId, DeliveryResult result) {
    int attempts = delivery.attempts() + 1;
    Instant now = clock.instant();
    switch (result.outcome()) {
      case SUCCESS -> {
        deliveries.markSent(delivery.id(), attempts, now);
        log.info("Delivery {} of email {} via {}: SENT", delivery.id(), messageId, type);
      }
      case RETRYABLE -> {
        if (properties.isExhausted(attempts)) {
          dead(delivery, type, messageId, attempts, result.reason());
        } else {
          Instant next = now.plus(properties.backoffAfter(attempts));
          deliveries.markRetry(delivery.id(), attempts, next, result.reason());
          log.warn(
              "Delivery {} of email {} via {}: attempt {} failed ({}), retrying at {}",
              delivery.id(),
              messageId,
              type,
              attempts,
              result.reason(),
              next);
        }
      }
      case PERMANENT -> dead(delivery, type, messageId, attempts, result.reason());
    }
  }

  /** One transaction, so listeners that wait for a commit see the event exactly when it is true. */
  private void dead(
      ClaimedDelivery delivery, ChannelType type, String messageId, int attempts, String reason) {
    transaction.executeWithoutResult(tx -> {
      if (deliveries.markDead(delivery.id(), attempts, reason)) {
        events.publishEvent(
            new DeliveryDead(
                delivery.id(),
                delivery.matchId(),
                delivery.channelId(),
                type,
                messageId,
                attempts,
                reason));
      }
    });
    log.warn(
        "Delivery {} of email {} via {}: DEAD after {} attempts ({})",
        delivery.id(),
        messageId,
        type,
        attempts,
        reason);
  }

  private static Alert alert(StoredMatch match, EmailSummary email) {
    return new Alert(
        match.eventType(),
        email.company(),
        email.subject(),
        match.contextSnippet(),
        email.receivedAt(),
        email.rfcMessageId(),
        match.test());
  }
}
