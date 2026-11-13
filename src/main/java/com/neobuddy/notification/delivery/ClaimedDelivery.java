package com.neobuddy.notification.delivery;

import java.util.UUID;

/**
 * A due delivery this dispatcher has claimed.
 *
 * @param attempts how many tries have already failed
 */
record ClaimedDelivery(UUID id, UUID matchId, UUID channelId, int attempts) {}
