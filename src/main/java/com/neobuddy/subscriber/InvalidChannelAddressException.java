package com.neobuddy.subscriber;

/** The address cannot be used for this channel type, for example a malformed email address. */
public class InvalidChannelAddressException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidChannelAddressException(ChannelType type) {
    super("Not a valid " + type + " address");
  }
}
