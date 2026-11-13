package com.neobuddy.notification.channel;

/** An email ready to send: subject, plain-text part and HTML part. */
record EmailContent(String subject, String text, String html) {

  /** Never prints the content. */
  @Override
  public String toString() {
    return "EmailContent[hidden]";
  }
}
