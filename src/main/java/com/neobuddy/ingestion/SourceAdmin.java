package com.neobuddy.ingestion;

import com.neobuddy.ingestion.SourceConnectException.Reason;
import com.neobuddy.ingestion.gmail.GmailMailSourceFactory;
import com.neobuddy.ingestion.gmail.GmailWebAuthorization;
import com.neobuddy.ingestion.source.IngestionProperties;
import com.neobuddy.ingestion.source.OAuthClient;
import com.neobuddy.ingestion.source.SourceInbox;
import com.neobuddy.ingestion.source.SourceInboxRepository;
import com.neobuddy.ingestion.source.SourceInviteStore;
import com.neobuddy.ingestion.source.SourceStatus;
import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Source inboxes for the admin console: their state, invite links that let a campus lead connect
 * their college's inbox through Google's web consent screen, and disconnecting.
 *
 * <p>An invite link carries a random 256-bit token, is valid for 24 hours and can connect one inbox
 * once. Only the token's SHA-256 hash is stored. Tokens, codes and addresses are never logged.
 */
@Service
public class SourceAdmin {

  /** How long an invite link works. */
  public static final Duration INVITE_VALIDITY = Duration.ofHours(24);

  /** Where a source stands, as the admin console and its health indicator see it. */
  public enum Health {
    /** Polled recently. */
    OK,
    /** Connected; the first poll has not run yet. */
    WAITING,
    /** Active, but not polled for longer than expected. */
    STALE,
    /** Google rejected the token; it has to be connected again. */
    AUTH_EXPIRED,
    /** Disconnected by an admin. */
    DISCONNECTED,
    /** Its college is paused, so it is not polled. */
    COLLEGE_PAUSED;

    /** True when someone should look at it. */
    public boolean needsAttention() {
      return this == STALE || this == AUTH_EXPIRED;
    }
  }

  /**
   * One source inbox.
   *
   * @param status ACTIVE, AUTH_EXPIRED or DISABLED
   * @param cursor the Gmail history id polling continues from; null before the first poll
   * @param oauthClient DESKTOP or WEB: the OAuth client that issued its token
   */
  public record SourceView(
      UUID id,
      UUID collegeId,
      String collegeSlug,
      String email,
      String status,
      Instant lastSyncedAt,
      Long cursor,
      String oauthClient,
      Health health) {}

  /** A new invite link's token: shown once, in the link. */
  public record NewInvite(UUID id, UUID collegeId, String token, Instant expiresAt) {}

  /** What the invite page shows; nothing secret. */
  public record InviteView(UUID collegeId, String collegeName, Instant expiresAt) {}

  /** An invite as the admin console lists it. */
  public record InviteSummary(
      UUID id,
      UUID collegeId,
      Instant createdAt,
      Instant expiresAt,
      Instant usedAt,
      UUID sourceId) {

    public boolean expired(Instant now) {
      return usedAt == null && !expiresAt.isAfter(now);
    }
  }

  /**
   * The start of the Google flow. The caller keeps {@code state} and {@code codeVerifier} in the
   * browser's session and sends the browser to {@code authorizationUrl}.
   */
  public record ConnectStart(URI authorizationUrl, String state, String codeVerifier) {}

  /** The inbox that was connected. */
  public record Connected(UUID sourceId, UUID collegeId, String email, boolean reconnected) {}

  private static final Logger log = LoggerFactory.getLogger(SourceAdmin.class);

  private final SourceInboxRepository sources;
  private final SourceInviteStore invites;
  private final CollegeRepository colleges;
  private final GmailWebAuthorization google;
  private final GmailMailSourceFactory gmail;
  private final IngestionProperties ingestion;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  SourceAdmin(
      SourceInboxRepository sources,
      SourceInviteStore invites,
      CollegeRepository colleges,
      GmailWebAuthorization google,
      GmailMailSourceFactory gmail,
      IngestionProperties ingestion,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.sources = sources;
    this.invites = invites;
    this.colleges = colleges;
    this.google = google;
    this.gmail = gmail;
    this.ingestion = ingestion;
    this.transaction = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  // ---- State -----------------------------------------------------------------------------------

  /** Every source inbox, by college slug and address. */
  public List<SourceView> sources() {
    Map<UUID, College> byId =
        colleges.findAll().stream().collect(Collectors.toMap(College::getId, Function.identity()));
    Instant now = clock.instant();
    return sources.findSummaries().stream()
        .map(s -> view(s, byId, now))
        .sorted(Comparator.comparing(SourceView::collegeSlug).thenComparing(SourceView::email))
        .toList();
  }

  /** True when the web connect flow can run (the web OAuth client is configured). */
  public boolean webConnectConfigured() {
    return google.configured();
  }

  private SourceView view(
      SourceInboxRepository.Summary source, Map<UUID, College> colleges, Instant now) {
    College college = colleges.get(source.getCollegeId());
    return new SourceView(
        source.getId(),
        source.getCollegeId(),
        college == null ? "?" : college.getSlug(),
        source.getEmail(),
        source.getStatus().name(),
        source.getLastSyncedAt(),
        source.getLastHistoryId(),
        source.getOauthClient().name(),
        health(source, college, now));
  }

  private Health health(SourceInboxRepository.Summary source, College college, Instant now) {
    return switch (source.getStatus()) {
      case AUTH_EXPIRED -> Health.AUTH_EXPIRED;
      case DISABLED -> Health.DISCONNECTED;
      case ACTIVE -> {
        if (college == null || !college.acceptsSubscribers()) {
          yield Health.COLLEGE_PAUSED;
        }
        if (source.getLastSyncedAt() == null) {
          yield Health.WAITING;
        }
        yield source.getLastSyncedAt().isBefore(now.minus(staleAfter())) ? Health.STALE : Health.OK;
      }
    };
  }

  /** Three missed polls, and never less than ten minutes. */
  public Duration staleAfter() {
    Duration three = ingestion.pollInterval().multipliedBy(3);
    return three.compareTo(Duration.ofMinutes(10)) < 0 ? Duration.ofMinutes(10) : three;
  }

  // ---- Invites ---------------------------------------------------------------------------------

  /**
   * Creates an invite link for a college.
   *
   * @param createdBy the admin's user id, may be null
   * @throws IllegalArgumentException if the college does not exist
   */
  public NewInvite createInvite(UUID collegeId, UUID createdBy) {
    colleges.findById(collegeId).orElseThrow(() -> new IllegalArgumentException("Unknown college"));
    String token = randomToken(32);
    Instant now = clock.instant();
    Instant expiresAt = now.plus(INVITE_VALIDITY);
    UUID id = UUID.randomUUID();
    transaction.executeWithoutResult(
        status -> invites.insert(id, collegeId, hash(token), createdBy, now, expiresAt));
    log.info("Source invite {} created for college {}", id, collegeId);
    return new NewInvite(id, collegeId, token, expiresAt);
  }

  /** The invite behind a link, if it can still be used. */
  public Optional<InviteView> usableInvite(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    return invites
        .findByTokenHash(hash(token))
        .filter(row -> row.usedAt() == null && row.expiresAt().isAfter(now))
        .flatMap(row ->
            colleges
                .findById(row.collegeId())
                .map(college ->
                    new InviteView(college.getId(), college.getName(), row.expiresAt())));
  }

  /** The most recent invites, newest first. */
  public List<InviteSummary> recentInvites(int limit) {
    return invites.recent(limit).stream()
        .map(row ->
            new InviteSummary(
                row.id(),
                row.collegeId(),
                row.createdAt(),
                row.expiresAt(),
                row.usedAt(),
                row.sourceId()))
        .toList();
  }

  // ---- The Google flow -------------------------------------------------------------------------

  /**
   * @param redirectUri the callback registered on the web client
   * @throws SourceConnectException INVITE_UNUSABLE or NOT_CONFIGURED
   */
  public ConnectStart start(String token, String redirectUri) {
    if (usableInvite(token).isEmpty()) {
      throw new SourceConnectException(Reason.INVITE_UNUSABLE);
    }
    String state = randomToken(24);
    String verifier = randomToken(48);
    return new ConnectStart(
        google.authorizationUrl(redirectUri, state, challengeFor(verifier)), state, verifier);
  }

  /**
   * Finishes the flow: exchanges the code, finds the mailbox address and stores the inbox for the
   * invite's college, consuming the invite. A new inbox starts from its first poll, so mail that
   * was already there is never read.
   *
   * @throws SourceConnectException for every way this can fail
   */
  public Connected complete(String token, String code, String redirectUri, String codeVerifier) {
    if (usableInvite(token).isEmpty()) {
      throw new SourceConnectException(Reason.INVITE_UNUSABLE);
    }
    String refreshToken = google.exchange(code, redirectUri, codeVerifier);
    String address;
    try {
      address =
          gmail.mailboxAddress(refreshToken, OAuthClient.WEB).strip().toLowerCase(Locale.ROOT);
    } catch (RuntimeException e) {
      google.revoke(refreshToken);
      throw new SourceConnectException(Reason.GOOGLE_REFUSED);
    }
    String tokenHash = hash(token);
    Connected connected;
    try {
      connected = transaction.execute(status -> store(tokenHash, address, refreshToken));
    } catch (SourceConnectException e) {
      if (e.reason() == Reason.INVITE_UNUSABLE) {
        // Lost a race for the link: the grant this request obtained is not used anywhere.
        google.revoke(refreshToken);
      }
      throw e;
    }
    log.info(
        "Source {} {} for college {} through an invite link",
        connected.sourceId(),
        connected.reconnected() ? "reconnected" : "connected",
        connected.collegeId());
    return connected;
  }

  private Connected store(String tokenHash, String address, String refreshToken) {
    Instant now = clock.instant();
    UUID collegeId =
        invites
            .findByTokenHash(tokenHash)
            .map(SourceInviteStore.Row::collegeId)
            .orElseThrow(() -> new SourceConnectException(Reason.INVITE_UNUSABLE));
    Optional<SourceInbox> existing = sources.findByEmail(address);
    if (existing.isPresent() && !existing.get().getCollegeId().equals(collegeId)) {
      throw new SourceConnectException(Reason.BELONGS_TO_ANOTHER_COLLEGE);
    }
    if (invites.consume(tokenHash, now).isEmpty()) {
      throw new SourceConnectException(Reason.INVITE_UNUSABLE);
    }
    SourceInbox inbox;
    if (existing.isPresent()) {
      inbox = existing.get();
      inbox.reconnect(refreshToken, OAuthClient.WEB);
    } else {
      inbox =
          sources.saveAndFlush(new SourceInbox(collegeId, address, refreshToken, OAuthClient.WEB));
    }
    invites.recordSource(tokenHash, inbox.getId());
    return new Connected(inbox.getId(), collegeId, address, existing.isPresent());
  }

  // ---- Disconnect ------------------------------------------------------------------------------

  /**
   * Stops polling the inbox and asks Google to revoke its token. Emails it brought stay.
   *
   * @return false if there is no such source
   */
  public boolean disconnect(UUID sourceId) {
    String[] token = new String[1];
    Boolean found =
        transaction.execute(status ->
            sources
                .findById(sourceId)
                .map(source -> {
                  token[0] = source.getRefreshToken();
                  source.disconnect();
                  return true;
                })
                .orElse(false));
    if (Boolean.TRUE.equals(found)) {
      google.revoke(token[0]);
      log.info("Source {} disconnected by an admin", sourceId);
    }
    return Boolean.TRUE.equals(found);
  }

  /** True if the source exists and is not disconnected. */
  public boolean isConnected(UUID sourceId) {
    return sources
        .findById(sourceId)
        .filter(s -> s.getStatus() != SourceStatus.DISABLED)
        .isPresent();
  }

  // ---- Helpers ---------------------------------------------------------------------------------

  private String randomToken(int bytes) {
    byte[] value = new byte[bytes];
    random.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  static String hash(String token) {
    return HexFormat.of().formatHex(sha256(token.getBytes(StandardCharsets.UTF_8)));
  }

  private static String challengeFor(String verifier) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is missing", e);
    }
  }
}
