/**
 * Delivery outbox, dispatcher and the outbound alert channels. The base package is the public API:
 * the {@link com.neobuddy.notification.NotificationChannel} strategy and its types, and the {@link
 * com.neobuddy.notification.DeliveryDead} event.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Notification")
package com.neobuddy.notification;
