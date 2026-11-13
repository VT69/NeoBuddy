package com.neobuddy.processing;

import com.neobuddy.ingestion.AttachmentRef;
import java.io.IOException;
import java.io.InputStream;

/**
 * Port for fetching the content of an attachment of the email being processed. The caller of {@link
 * ProcessingService} supplies it (Gmail in production, bytes in memory in tests), which keeps
 * processing free of any mail API.
 */
@FunctionalInterface
public interface AttachmentSource {

  /** An email without attachments, or whose attachments should be ignored. */
  AttachmentSource NONE =
      attachment -> {
        throw new IOException("No attachment content available");
      };

  /**
   * Opens the content of one attachment. {@link ProcessingService} closes the stream.
   *
   * @throws IOException if the content cannot be fetched; that attachment is then reported as
   *     failed and the rest of the email is still processed
   */
  InputStream open(AttachmentRef attachment) throws IOException;
}
