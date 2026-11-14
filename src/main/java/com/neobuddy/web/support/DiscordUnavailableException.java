package com.neobuddy.web.support;

/** Discord is not set up on this deployment (no bot token), so there is nothing to link. */
public class DiscordUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public DiscordUnavailableException() {
    super("Discord is not available");
  }
}
