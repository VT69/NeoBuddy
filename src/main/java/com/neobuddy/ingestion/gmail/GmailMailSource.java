package com.neobuddy.ingestion.gmail;

import com.google.api.client.http.HttpResponseException;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.History;
import com.google.api.services.gmail.model.HistoryMessageAdded;
import com.google.api.services.gmail.model.ListHistoryResponse;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.neobuddy.ingestion.source.ChangePage;
import com.neobuddy.ingestion.source.LoadedMessage;
import com.neobuddy.ingestion.source.MailHeader;
import com.neobuddy.ingestion.source.MailRef;
import com.neobuddy.ingestion.source.MailSource;
import com.neobuddy.ingestion.source.MailSourceException;
import com.neobuddy.ingestion.source.SourceAuthExpiredException;
import com.neobuddy.ingestion.source.StaleCursorException;
import com.neobuddy.ingestion.source.SyncCursor;
import io.github.resilience4j.retry.Retry;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * One Gmail inbox, read with the {@code gmail.readonly} scope.
 *
 * <p>Every request is retried with exponential backoff on HTTP 429 and 5xx and on read timeouts.
 * Failures are translated so that nothing outside this package depends on Google's client: an
 * {@code invalid_grant} from the token endpoint (or a 401) becomes {@link
 * SourceAuthExpiredException}, a 404 on history becomes {@link StaleCursorException}, and
 * everything else a {@link MailSourceException} that names only the operation and the status.
 */
final class GmailMailSource implements MailSource {

  private static final String ME = "me";
  private static final long PAGE_SIZE = 500L;
  private static final List<String> MESSAGE_ADDED = List.of("messageAdded");
  private static final List<String> HEADERS_NEEDED = List.of("Message-ID", "From", "Subject");

  private final UUID id;
  private final Gmail gmail;
  private final Retry retry;

  GmailMailSource(UUID id, Gmail gmail, Retry retry) {
    this.id = id;
    this.gmail = gmail;
    this.retry = retry;
  }

  @Override
  public UUID id() {
    return id;
  }

  @Override
  public ChangePage changesSince(SyncCursor cursor) {
    ListHistoryResponse response;
    try {
      response =
          call(
              "history.list",
              () ->
                  gmail
                      .users()
                      .history()
                      .list(ME)
                      .setStartHistoryId(BigInteger.valueOf(cursor.historyId()))
                      .setHistoryTypes(MESSAGE_ADDED)
                      .setMaxResults(PAGE_SIZE)
                      .setPageToken(cursor.pageToken())
                      .execute());
    } catch (NotFound e) {
      throw new StaleCursorException();
    }
    Set<String> ids = new LinkedHashSet<>();
    long highestSeen = cursor.historyId();
    if (response.getHistory() != null) {
      for (History record : response.getHistory()) {
        if (record.getId() != null) {
          highestSeen = Math.max(highestSeen, record.getId().longValue());
        }
        if (record.getMessagesAdded() != null) {
          for (HistoryMessageAdded added : record.getMessagesAdded()) {
            if (added.getMessage() != null && added.getMessage().getId() != null) {
              ids.add(added.getMessage().getId());
            }
          }
        }
      }
    }
    String next = response.getNextPageToken();
    // On the last page Gmail reports the mailbox's current history id: everything is seen.
    long checkpoint =
        next == null && response.getHistoryId() != null
            ? Math.max(highestSeen, response.getHistoryId().longValue())
            : highestSeen;
    return new ChangePage(ids.stream().map(MailRef::new).toList(), checkpoint, next);
  }

  @Override
  public List<MailRef> recentMessages(int days) {
    List<MailRef> refs = new ArrayList<>();
    String token = null;
    do {
      String pageToken = token;
      ListMessagesResponse response =
          call(
              "messages.list",
              () ->
                  gmail
                      .users()
                      .messages()
                      .list(ME)
                      .setQ("newer_than:" + days + "d")
                      .setMaxResults(PAGE_SIZE)
                      .setPageToken(pageToken)
                      .execute());
      if (response.getMessages() != null) {
        response.getMessages().forEach(message -> refs.add(new MailRef(message.getId())));
      }
      token = response.getNextPageToken();
    } while (token != null);
    return refs;
  }

  @Override
  public long currentHistoryId() {
    BigInteger historyId =
        call("getProfile", () -> gmail.users().getProfile(ME).execute()).getHistoryId();
    if (historyId == null) {
      throw new MailSourceException("getProfile returned no history id");
    }
    return historyId.longValue();
  }

  @Override
  public Optional<MailHeader> header(MailRef ref) {
    try {
      Message message =
          call(
              "messages.get",
              () ->
                  gmail
                      .users()
                      .messages()
                      .get(ME, ref.gmailId())
                      .setFormat("metadata")
                      .setMetadataHeaders(HEADERS_NEEDED)
                      .execute());
      return Optional.of(GmailMessages.header(ref, message));
    } catch (NotFound e) {
      // Deleted between the history entry and now.
      return Optional.empty();
    }
  }

  @Override
  public LoadedMessage load(MailRef ref) {
    Message message =
        call(
            "messages.get",
            () -> gmail.users().messages().get(ME, ref.gmailId()).setFormat("full").execute());
    return GmailMessages.loaded(ref, message);
  }

  @Override
  public InputStream openAttachment(MailRef ref, String attachmentId) {
    InputStream json =
        call(
            "attachments.get",
            () ->
                gmail
                    .users()
                    .messages()
                    .attachments()
                    .get(ME, ref.gmailId(), attachmentId)
                    .executeAsInputStream());
    // The response is {"size": n, "data": "<base64url>"}; decode the data without holding the
    // whole attachment in memory twice.
    return Base64.getUrlDecoder().wrap(new JsonStringFieldInputStream(json, "data"));
  }

  private <T> T call(String operation, Callable<T> request) {
    try {
      return retry.executeCallable(request);
    } catch (Exception e) {
      throw translate(operation, e);
    }
  }

  static boolean isRetryable(Throwable failure) {
    if (isInvalidGrant(failure)) {
      return false;
    }
    if (failure instanceof HttpResponseException http) {
      return http.getStatusCode() == 429 || http.getStatusCode() >= 500;
    }
    return failure instanceof SocketTimeoutException;
  }

  private static MailSourceException translate(String operation, Exception failure) {
    if (isInvalidGrant(failure)) {
      return new SourceAuthExpiredException();
    }
    if (failure instanceof HttpResponseException http) {
      return switch (http.getStatusCode()) {
        case 401 -> new SourceAuthExpiredException();
        case 404 -> new NotFound(operation);
        default ->
            new MailSourceException(
                "Gmail " + operation + " failed with HTTP " + http.getStatusCode());
      };
    }
    // Only the type: a message from the HTTP layer could contain a URL with ids, or content.
    return new MailSourceException(
        "Gmail " + operation + " failed: " + failure.getClass().getSimpleName());
  }

  /** Google answers a revoked or expired refresh token with the OAuth error invalid_grant. */
  private static boolean isInvalidGrant(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      String message = t.getMessage();
      if (message != null && message.contains("invalid_grant")) {
        return true;
      }
      if (t instanceof HttpResponseException http
          && http.getContent() != null
          && http.getContent().contains("invalid_grant")) {
        return true;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return false;
  }

  /** HTTP 404; each caller decides what a missing thing means. */
  private static final class NotFound extends MailSourceException {

    private static final long serialVersionUID = 1L;

    private NotFound(String operation) {
      super("Gmail " + operation + " failed with HTTP 404");
    }
  }
}
