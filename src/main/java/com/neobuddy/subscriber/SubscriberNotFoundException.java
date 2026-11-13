package com.neobuddy.subscriber;

/** The user, or an identifier or channel of that user, does not exist. */
public class SubscriberNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SubscriberNotFoundException(String what) {
    super(what + " not found");
  }
}
