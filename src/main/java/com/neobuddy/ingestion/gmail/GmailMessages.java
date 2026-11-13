package com.neobuddy.ingestion.gmail;

import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.neobuddy.ingestion.AttachmentRef;
import com.neobuddy.ingestion.RawEmail;
import com.neobuddy.ingestion.source.LoadedMessage;
import com.neobuddy.ingestion.source.MailHeader;
import com.neobuddy.ingestion.source.MailRef;
import com.neobuddy.ingestion.source.SenderAllowlist;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns Gmail API messages into NeoBuddy's own types. Pure functions, no network. */
final class GmailMessages {

  private static final Pattern CHARSET =
      Pattern.compile("charset\\s*=\\s*\"?([^\";\\s]+)\"?", Pattern.CASE_INSENSITIVE);

  private GmailMessages() {}

  static MailHeader header(MailRef ref, Message message) {
    MessagePart payload = message.getPayload();
    boolean draft = message.getLabelIds() != null && message.getLabelIds().contains("DRAFT");
    return new MailHeader(
        ref,
        messageId(ref, payload),
        SenderAllowlist.addressOf(headerValue(payload, "From")),
        headerValue(payload, "Subject"),
        receivedAt(message),
        draft);
  }

  /**
   * Walks the MIME tree. A part with a filename is an attachment, wherever it sits and whether its
   * disposition is "attachment" or "inline". Parts without a filename contribute to the plain-text
   * or HTML body by their type, and multiparts are descended into to any depth.
   */
  static LoadedMessage loaded(MailRef ref, Message message) {
    MessagePart payload = message.getPayload();
    Walk walk = new Walk();
    if (payload != null) {
      walk.visit(payload);
    }
    RawEmail email =
        new RawEmail(
            messageId(ref, payload),
            ref.gmailId(),
            SenderAllowlist.addressOf(headerValue(payload, "From")),
            headerValue(payload, "Subject"),
            receivedAt(message),
            walk.text.isEmpty() ? null : walk.text.toString(),
            walk.html.isEmpty() ? null : walk.html.toString(),
            walk.attachments);
    // RawEmail copies the list but keeps the same AttachmentRef instances, which are the keys.
    return new LoadedMessage(email, walk.contents);
  }

  /** The Message-ID header, or a stable stand-in so a message without one is still deduped. */
  private static String messageId(MailRef ref, MessagePart payload) {
    String header = headerValue(payload, "Message-ID");
    if (header == null || header.isBlank()) {
      return "<gmail-" + ref.gmailId() + "@neobuddy.invalid>";
    }
    return header.strip();
  }

  private static Instant receivedAt(Message message) {
    return message.getInternalDate() == null
        ? Instant.now()
        : Instant.ofEpochMilli(message.getInternalDate());
  }

  /** Header names are case-insensitive; Gmail passes them through as the sender wrote them. */
  private static String headerValue(MessagePart part, String name) {
    if (part == null || part.getHeaders() == null) {
      return null;
    }
    for (MessagePartHeader header : part.getHeaders()) {
      if (name.equalsIgnoreCase(header.getName())) {
        return header.getValue();
      }
    }
    return null;
  }

  private static final class Walk {

    private final StringBuilder text = new StringBuilder();
    private final StringBuilder html = new StringBuilder();
    private final List<AttachmentRef> attachments = new ArrayList<>();
    private final Map<AttachmentRef, LoadedMessage.Content> contents = new IdentityHashMap<>();

    private void visit(MessagePart part) {
      String mimeType =
          part.getMimeType() == null ? "" : part.getMimeType().toLowerCase(Locale.ROOT);
      if (part.getFilename() != null && !part.getFilename().isBlank()) {
        attachment(part);
      } else if (part.getParts() != null && !part.getParts().isEmpty()) {
        part.getParts().forEach(this::visit);
      } else if (mimeType.equals("text/plain")) {
        append(text, part);
      } else if (mimeType.equals("text/html")) {
        append(html, part);
      }
    }

    private void attachment(MessagePart part) {
      MessagePartBody body = part.getBody();
      long size = body == null || body.getSize() == null ? 0 : body.getSize();
      AttachmentRef ref = new AttachmentRef(part.getFilename(), part.getMimeType(), size);
      attachments.add(ref);
      if (body != null && body.getAttachmentId() != null) {
        contents.put(ref, new LoadedMessage.Content(body.getAttachmentId(), null));
      } else {
        byte[] inline = body == null || body.getData() == null ? new byte[0] : body.decodeData();
        contents.put(ref, new LoadedMessage.Content(null, inline));
      }
    }

    private static void append(StringBuilder target, MessagePart part) {
      MessagePartBody body = part.getBody();
      if (body == null || body.getData() == null) {
        return;
      }
      String decoded = new String(body.decodeData(), charsetOf(part));
      if (!target.isEmpty()) {
        target.append('\n');
      }
      target.append(decoded);
    }

    private static Charset charsetOf(MessagePart part) {
      String contentType = headerValue(part, "Content-Type");
      if (contentType != null) {
        Matcher matcher = CHARSET.matcher(contentType);
        if (matcher.find()) {
          try {
            return Charset.forName(matcher.group(1));
          } catch (IllegalArgumentException e) {
            // Unknown or malformed charset name: fall through to UTF-8.
          }
        }
      }
      return StandardCharsets.UTF_8;
    }
  }
}
