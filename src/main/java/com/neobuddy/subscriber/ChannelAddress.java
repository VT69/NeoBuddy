package com.neobuddy.subscriber;

/**
 * Where a channel delivers to: a Discord user id, an email address or an ntfy topic. Stored
 * encrypted; an ntfy topic in particular is a secret, since anyone who knows it can read the
 * alerts.
 */
public record ChannelAddress(String value) {

  private static final int MAX_LENGTH = 320;

  public ChannelAddress {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("A channel address must not be blank");
    }
    value = value.strip();
    if (value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("A channel address is at most " + MAX_LENGTH + " long");
    }
  }

  /** Never prints the address. */
  @Override
  public String toString() {
    return "ChannelAddress[hidden]";
  }
}
