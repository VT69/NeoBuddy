package com.neobuddy.notification;

import java.util.UUID;

/**
 * Sends a test alert to one of the student's channels through the normal delivery pipeline: a
 * synthetic match flagged as a test, delivered by the dispatcher like any other. Limiting how often
 * a student may ask is the caller's job.
 */
public interface TestAlerts {

  /**
   * @return the id of the queued delivery
   * @throws TestAlertNotPossibleException if the channel is not the user's, not verified, switched
   *     off, or of a kind NeoBuddy cannot send to yet, or the user has no identifier
   * @throws com.neobuddy.subscriber.SubscriberNotFoundException if the user does not exist
   */
  UUID send(UUID userId, UUID channelId);
}
