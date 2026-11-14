package com.neobuddy.notification.delivery;

import com.neobuddy.ingestion.EmailSummary;
import com.neobuddy.ingestion.ProcessedEmails;
import com.neobuddy.notification.AlertHistory;
import com.neobuddy.notification.AlertSummary;
import com.neobuddy.notification.DeliveryStatus;
import com.neobuddy.notification.NotificationChannel;
import com.neobuddy.notification.TestAlertNotPossibleException;
import com.neobuddy.notification.TestAlertNotPossibleException.Reason;
import com.neobuddy.notification.TestAlerts;
import com.neobuddy.processing.StoredMatch;
import com.neobuddy.processing.StoredMatches;
import com.neobuddy.processing.TestMatches;
import com.neobuddy.subscriber.ChannelType;
import com.neobuddy.subscriber.IdentifierType;
import com.neobuddy.subscriber.SubscriberService;
import com.neobuddy.subscriber.SubscriberView;
import com.neobuddy.subscriber.SubscriberView.ChannelView;
import com.neobuddy.subscriber.SubscriberView.IdentifierView;
import java.time.Clock;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The student-facing side of the outbox: test alerts and the alert history. */
@Service
class DeliveryQueries implements TestAlerts, AlertHistory {

  private static final int MAX_PAGE_SIZE = 100;

  private final SubscriberService subscribers;
  private final StoredMatches matches;
  private final TestMatches testMatches;
  private final ProcessedEmails emails;
  private final DeliveryStore deliveries;
  private final Clock clock;
  private final Set<ChannelType> sendable;

  DeliveryQueries(
      SubscriberService subscribers,
      StoredMatches matches,
      TestMatches testMatches,
      ProcessedEmails emails,
      DeliveryStore deliveries,
      Clock clock,
      List<NotificationChannel> senders) {
    this.subscribers = subscribers;
    this.matches = matches;
    this.testMatches = testMatches;
    this.emails = emails;
    this.deliveries = deliveries;
    this.clock = clock;
    Set<ChannelType> types = EnumSet.noneOf(ChannelType.class);
    senders.forEach(sender -> types.add(sender.type()));
    this.sendable = types;
  }

  @Override
  @Transactional
  public UUID send(UUID userId, UUID channelId) {
    SubscriberView user = subscribers.get(userId);
    ChannelView channel =
        user.channels().stream()
            .filter(c -> c.id().equals(channelId))
            .findFirst()
            .orElseThrow(() -> new TestAlertNotPossibleException(Reason.CHANNEL_NOT_FOUND));
    if (!channel.verified() || !channel.enabled() || !sendable.contains(channel.type())) {
      throw new TestAlertNotPossibleException(Reason.CHANNEL_NOT_READY);
    }
    // Prefer the NeoID, the identifier every student has.
    IdentifierView identifier =
        user.identifiers().stream()
            .min(Comparator.comparing((IdentifierView i) -> i.type() != IdentifierType.NEO_ID))
            .orElseThrow(() -> new TestAlertNotPossibleException(Reason.NO_IDENTIFIER));
    StoredMatch match = testMatches.createTestMatch(user.collegeId(), userId, identifier.id());
    deliveries.insertIfAbsent(match.id(), channelId, clock.instant());
    return deliveries.idOf(match.id(), channelId).orElseThrow();
  }

  @Override
  @Transactional(readOnly = true)
  public List<AlertSummary> recent(UUID userId, int page, int size) {
    int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
    List<StoredMatch> found = matches.ofUser(userId, Math.max(page, 0) * limit, limit);
    if (found.isEmpty()) {
      return List.of();
    }
    Map<UUID, ChannelType> channelTypes =
        subscribers.get(userId).channels().stream()
            .collect(Collectors.toMap(ChannelView::id, ChannelView::type));
    Map<UUID, List<DeliveryStore.Row>> byMatch =
        deliveries.ofMatches(found.stream().map(StoredMatch::id).toList()).stream()
            .collect(Collectors.groupingBy(DeliveryStore.Row::matchId));
    return found.stream()
        .map(match -> {
          Optional<EmailSummary> email = emails.find(match.rfcMessageId());
          List<AlertSummary.Delivery> sent =
              byMatch.getOrDefault(match.id(), List.of()).stream()
                  .map(row ->
                      new AlertSummary.Delivery(
                          channelTypes.get(row.channelId()),
                          DeliveryStatus.valueOf(row.status()),
                          row.attempts(),
                          row.sentAt()))
                  .sorted(
                      Comparator.comparing(
                          AlertSummary.Delivery::channelType,
                          Comparator.nullsLast(Comparator.naturalOrder())))
                  .toList();
          return new AlertSummary(
              match.id(),
              match.eventType(),
              email.map(EmailSummary::company).orElse(null),
              email.map(EmailSummary::subject).orElse(null),
              match.contextSnippet(),
              email.map(EmailSummary::receivedAt).orElse(match.createdAt()),
              match.test(),
              sent);
        })
        .toList();
  }
}
