package com.neobuddy.ingestion.source;

import com.neobuddy.ingestion.AttachmentRef;
import com.neobuddy.ingestion.RawEmail;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * A fully fetched message: the {@link RawEmail} plus, for each of its attachments, where the
 * content is. Attachments are looked up by instance, so two attachments with the same name, type
 * and size are still told apart.
 */
public final class LoadedMessage {

  /**
   * Exactly one of the two is set.
   *
   * @param attachmentId the provider's id to fetch the content with
   * @param inline the content itself, when the provider sent it along with the message
   */
  public record Content(String attachmentId, byte[] inline) {}

  private final RawEmail email;
  private final Map<AttachmentRef, Content> contents;

  public LoadedMessage(RawEmail email, Map<AttachmentRef, Content> contents) {
    this.email = email;
    this.contents = new IdentityHashMap<>(contents);
  }

  public RawEmail email() {
    return email;
  }

  /** Null if the attachment is not one of this message's. */
  public Content contentOf(AttachmentRef attachment) {
    return contents.get(attachment);
  }
}
