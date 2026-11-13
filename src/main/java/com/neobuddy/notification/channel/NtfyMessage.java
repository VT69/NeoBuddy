package com.neobuddy.notification.channel;

/**
 * A push message as ntfy takes it.
 *
 * @param title the headline
 * @param body the message text
 * @param tags comma-separated ntfy tags (emoji short codes)
 * @param priority ntfy priority name
 * @param click address opened when the notification is tapped
 */
record NtfyMessage(String title, String body, String tags, String priority, String click) {

  /** Never prints the content. */
  @Override
  public String toString() {
    return "NtfyMessage[hidden]";
  }
}
