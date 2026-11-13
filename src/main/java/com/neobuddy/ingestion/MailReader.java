package com.neobuddy.ingestion;

import com.neobuddy.ingestion.source.LoadedMessage;
import com.neobuddy.ingestion.source.MailRef;
import com.neobuddy.ingestion.source.MailSource;
import com.neobuddy.ingestion.source.MailSourceFactory;
import com.neobuddy.ingestion.source.SourceInbox;
import com.neobuddy.ingestion.source.SourceInboxRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fetches the full content of an ingested email from its source inbox. This is how processing gets
 * at a message without knowing anything about Gmail.
 */
@Service
public class MailReader {

  private final SourceInboxRepository sources;
  private final MailSourceFactory factory;

  MailReader(SourceInboxRepository sources, MailSourceFactory factory) {
    this.sources = sources;
    this.factory = factory;
  }

  /**
   * @throws IllegalStateException if the source inbox no longer exists
   * @throws RuntimeException if the message cannot be fetched
   */
  @Transactional(readOnly = true)
  public LoadedMail load(EmailIngested event) {
    SourceInbox inbox =
        sources
            .findById(event.sourceId())
            .orElseThrow(() -> new IllegalStateException("Source inbox no longer exists"));
    MailSource source = factory.open(inbox);
    MailRef ref = new MailRef(event.gmailId());
    LoadedMessage message = source.load(ref);
    // Keep the id the email was recorded under, even if the message has no Message-ID header.
    RawEmail fetched = message.email();
    RawEmail email =
        new RawEmail(
            event.rfcMessageId(),
            fetched.gmailId(),
            fetched.from(),
            fetched.subject(),
            fetched.receivedAt(),
            fetched.textBody(),
            fetched.htmlBody(),
            fetched.attachments());
    return new LoadedMail() {
      @Override
      public RawEmail email() {
        return email;
      }

      @Override
      public InputStream openAttachment(AttachmentRef attachment) throws IOException {
        LoadedMessage.Content content = message.contentOf(attachment);
        if (content == null) {
          throw new IOException("Attachment is not part of this email");
        }
        if (content.inline() != null) {
          return new ByteArrayInputStream(content.inline());
        }
        return source.openAttachment(ref, content.attachmentId());
      }
    };
  }
}
