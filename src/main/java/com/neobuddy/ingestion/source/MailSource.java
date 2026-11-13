package com.neobuddy.ingestion.source;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port to one connected inbox. Gmail implements it today; a forwarding inbox or another provider
 * can later, without the poller changing.
 *
 * <p>Every method may throw {@link SourceAuthExpiredException} if the inbox's authorisation is no
 * longer valid, or {@link MailSourceException} for any other failure.
 */
public interface MailSource {

  /** The id of the source inbox this talks to. */
  UUID id();

  /**
   * One page of messages added since the cursor.
   *
   * @throws StaleCursorException if the provider no longer has history that far back
   */
  ChangePage changesSince(SyncCursor cursor);

  /** Every message of the last {@code days} days; the fallback when the cursor is stale. */
  List<MailRef> recentMessages(int days);

  /** The inbox's current position, to start "from now". */
  long currentHistoryId();

  /** Headers only, enough to filter and dedupe. Empty if the message no longer exists. */
  Optional<MailHeader> header(MailRef ref);

  /** The whole message: bodies and what is known about each attachment. */
  LoadedMessage load(MailRef ref);

  /** The content of an attachment that is not carried inline. The caller closes the stream. */
  InputStream openAttachment(MailRef ref, String attachmentId);
}
