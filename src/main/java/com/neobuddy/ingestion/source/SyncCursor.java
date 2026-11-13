package com.neobuddy.ingestion.source;

/**
 * Where to continue reading changes.
 *
 * @param historyId the history id the poll started from; stays the same across pages of one poll
 * @param pageToken the token for the next page, null for the first page
 */
public record SyncCursor(long historyId, String pageToken) {

  public static SyncCursor startingAt(long historyId) {
    return new SyncCursor(historyId, null);
  }
}
