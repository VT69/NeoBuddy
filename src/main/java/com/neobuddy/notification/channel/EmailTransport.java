package com.neobuddy.notification.channel;

import com.neobuddy.notification.DeliveryResult;
import com.neobuddy.subscriber.ChannelAddress;

/** Sends one email. Implemented by whichever email channel is active. */
interface EmailTransport {

  DeliveryResult send(ChannelAddress to, EmailContent content);
}
