package com.neobuddy.notification;

/**
 * How one attempt to send an alert ended.
 *
 * @param outcome sent, worth retrying, or pointless to retry
 * @param reason for failures, a short technical reason such as {@code HTTP 429}; it is stored and
 *     logged, so it must never contain an address, a topic or any alert content
 */
public record DeliveryResult(Outcome outcome, String reason) {

  public enum Outcome {
    SUCCESS,
    RETRYABLE,
    PERMANENT
  }

  private static final DeliveryResult SUCCESS = new DeliveryResult(Outcome.SUCCESS, null);

  public static DeliveryResult success() {
    return SUCCESS;
  }

  /** A failure that may pass on its own: throttling, a server error, a timeout. */
  public static DeliveryResult retryable(String reason) {
    return new DeliveryResult(Outcome.RETRYABLE, reason);
  }

  /** A failure that trying again cannot fix: a rejected address, a topic that does not exist. */
  public static DeliveryResult permanent(String reason) {
    return new DeliveryResult(Outcome.PERMANENT, reason);
  }
}
