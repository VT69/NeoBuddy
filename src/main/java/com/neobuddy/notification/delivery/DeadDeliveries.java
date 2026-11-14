package com.neobuddy.notification.delivery;

import com.neobuddy.ingestion.EmailSummary;
import com.neobuddy.ingestion.ProcessedEmails;
import com.neobuddy.notification.DeliveryAdmin;
import com.neobuddy.processing.StoredMatch;
import com.neobuddy.processing.StoredMatches;
import com.neobuddy.subscriber.ChannelDirectory;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Dead deliveries for the admin console. Log lines carry delivery ids only. */
@Service
class DeadDeliveries implements DeliveryAdmin {

  private static final Logger log = LoggerFactory.getLogger(DeadDeliveries.class);
  private static final int MAX_PAGE_SIZE = 200;

  private final DeliveryStore deliveries;
  private final StoredMatches matches;
  private final ProcessedEmails emails;
  private final ChannelDirectory channels;
  private final Clock clock;

  DeadDeliveries(
      DeliveryStore deliveries,
      StoredMatches matches,
      ProcessedEmails emails,
      ChannelDirectory channels,
      Clock clock) {
    this.deliveries = deliveries;
    this.matches = matches;
    this.emails = emails;
    this.channels = channels;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public List<DeadDelivery> dead(int page, int size) {
    int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
    return deliveries.dead(Math.max(page, 0) * limit, limit).stream()
        .map(row -> {
          Optional<StoredMatch> match = matches.find(row.matchId());
          Optional<EmailSummary> email = match.flatMap(m -> emails.find(m.rfcMessageId()));
          return new DeadDelivery(
              row.id(),
              email.map(EmailSummary::collegeId).orElse(null),
              channels.typeOf(row.channelId()).orElse(null),
              match.map(StoredMatch::rfcMessageId).orElse(null),
              email.map(EmailSummary::subject).orElse(null),
              email.map(EmailSummary::receivedAt).orElse(null),
              row.attempts(),
              row.lastError(),
              match.map(StoredMatch::test).orElse(false));
        })
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public int countDead() {
    return deliveries.countDead();
  }

  @Override
  @Transactional
  public boolean retry(UUID deliveryId) {
    boolean revived = deliveries.revive(deliveryId, clock.instant());
    if (revived) {
      log.info("Delivery {} queued again by an admin", deliveryId);
    }
    return revived;
  }

  @Override
  @Transactional
  public boolean discard(UUID deliveryId) {
    boolean discarded = deliveries.discard(deliveryId);
    if (discarded) {
      log.info("Delivery {} discarded by an admin", deliveryId);
    }
    return discarded;
  }
}
