package com.neobuddy.subscriber;

import java.util.UUID;

/**
 * Where to send an alert: a verified, enabled channel with its decrypted address. Only the
 * notification module needs this; everything else works with {@link SubscriberView}.
 */
public record ChannelTarget(
    UUID channelId, UUID userId, ChannelType type, ChannelAddress address) {}
