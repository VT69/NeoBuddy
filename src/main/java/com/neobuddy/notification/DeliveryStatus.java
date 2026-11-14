package com.neobuddy.notification;

/** Where one alert to one channel stands. */
public enum DeliveryStatus {
  /** Queued, not tried yet. */
  PENDING,
  /** A try failed; it will be tried again. */
  RETRY,
  /** The channel accepted it. */
  SENT,
  /** Given up. */
  DEAD
}
