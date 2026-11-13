package com.neobuddy.ingestion.source;

import java.util.List;

/**
 * One page of new messages.
 *
 * @param messages the messages added, without duplicates, oldest first
 * @param checkpointHistoryId the history id it is safe to resume from once this page has been
 *     handled: everything up to it has been seen
 * @param nextPageToken token for the next page, null if this was the last
 */
public record ChangePage(List<MailRef> messages, long checkpointHistoryId, String nextPageToken) {

  public ChangePage {
    messages = List.copyOf(messages);
  }

  public boolean isLast() {
    return nextPageToken == null;
  }
}
