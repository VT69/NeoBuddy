package com.neobuddy.notification;

import com.neobuddy.subscriber.ChannelType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Dead deliveries for the admin console: list them, try one again, or let it go. */
public interface DeliveryAdmin {

  /**
   * One delivery that was given up. No address and no snippet: the admin sees what failed, not what
   * the student was told.
   *
   * @param channelType null if the channel was deleted since
   * @param rfcMessageId null if the match is gone
   * @param subject the email's subject, may be null
   * @param reason the short technical reason stored with it
   */
  record DeadDelivery(
      UUID id,
      UUID collegeId,
      ChannelType channelType,
      String rfcMessageId,
      String subject,
      Instant receivedAt,
      int attempts,
      String reason,
      boolean test) {}

  List<DeadDelivery> dead(int page, int size);

  int countDead();

  /**
   * Queues a dead delivery again with a fresh set of attempts.
   *
   * @return false if it is not dead (any more)
   */
  boolean retry(UUID deliveryId);

  /**
   * Marks a dead delivery DISCARDED: it is never sent, and reprocessing its email does not queue it
   * again.
   *
   * @return false if it is not dead (any more)
   */
  boolean discard(UUID deliveryId);
}
