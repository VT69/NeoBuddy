package com.neobuddy.notification;

import com.neobuddy.subscriber.ChannelType;
import java.util.UUID;

/**
 * Published when a delivery is given up: a permanent failure, or the last allowed attempt failed.
 * Published in the transaction that marks the row DEAD. Carries ids only, no personal data.
 *
 * @param channelType null if the channel could no longer be read
 * @param rfcMessageId the email the alert was about; null if the match is gone
 * @param attempts how many times sending was tried
 * @param reason a short technical reason, as stored in {@code delivery.last_error}
 */
public record DeliveryDead(
    UUID deliveryId,
    UUID matchId,
    UUID channelId,
    ChannelType channelType,
    String rfcMessageId,
    int attempts,
    String reason) {}
