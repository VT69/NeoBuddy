package com.neobuddy.ingestion;

import java.io.IOException;
import java.io.InputStream;

/** An email fetched for processing, with a way to fetch the content of its attachments. */
public interface LoadedMail {

  RawEmail email();

  /**
   * Opens the content of one of {@link RawEmail#attachments()}; pass the very same instance. The
   * caller closes the stream.
   *
   * @throws IOException if the attachment is not part of this email or cannot be fetched
   */
  InputStream openAttachment(AttachmentRef attachment) throws IOException;
}
