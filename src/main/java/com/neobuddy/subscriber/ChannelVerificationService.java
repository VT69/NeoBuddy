package com.neobuddy.subscriber;

import com.neobuddy.subscriber.SubscriberView.ChannelView;
import com.neobuddy.subscriber.persistence.Channel;
import com.neobuddy.subscriber.persistence.ChannelRepository;
import com.neobuddy.subscriber.persistence.LinkCode;
import com.neobuddy.subscriber.persistence.LinkCodeRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves that a channel belongs to the user before any alert goes to it.
 *
 * <ul>
 *   <li><b>Push:</b> the server invents the ntfy topic, so there is nothing to prove; the channel
 *       is verified as soon as it exists. The topic is the secret: whoever knows it can read the
 *       alerts.
 *   <li><b>Email:</b> a 6-digit code is emailed to the address. Only its SHA-256 hash is stored, it
 *       works for 10 minutes and allows 5 guesses.
 * </ul>
 *
 * <li><b>Discord:</b> the website shows a one-time code; the student runs {@code /link} with it in
 *     the NeoBuddy server, which ties their Discord account to the NeoBuddy one. Only the code's
 *     SHA-256 hash is stored; it works for 10 minutes, once.
 *     </ul>
 */
@Service
@EnableConfigurationProperties(VerificationProperties.class)
public class ChannelVerificationService {

  static final String NTFY_TOPIC_PREFIX = "nb-";
  private static final int NTFY_TOPIC_RANDOM_BYTES = 16;
  private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
  private static final int OTP_BOUND = 1_000_000;
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s.]+$");
  private static final int MAX_EMAIL_LENGTH = 254;
  private static final char[] LINK_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
  private static final int LINK_CODE_LENGTH = 8;
  private static final Pattern DISCORD_USER_ID = Pattern.compile("^\\d{15,21}$");

  /**
   * @param topic the new topic, to show its owner once (subscribe link and QR code)
   */
  public record NtfyChannel(UUID channelId, String topic) {

    /** Never prints the topic. */
    @Override
    public String toString() {
      return "NtfyChannel[channelId=" + channelId + "]";
    }
  }

  /**
   * A code for {@code /link}, shown to its owner once.
   *
   * @param code eight characters from an alphabet without look-alikes (no 0/O, 1/I)
   */
  public record DiscordLinkCode(String code, Instant expiresAt) {

    /** Never prints the code. */
    @Override
    public String toString() {
      return "DiscordLinkCode[expiresAt=" + expiresAt + "]";
    }
  }

  /**
   * @param userId the NeoBuddy account the Discord account is now linked to; null unless LINKED
   */
  public record DiscordLinkResult(DiscordLinkStatus status, UUID userId) {}

  public enum DiscordLinkStatus {
    /** Linked (or linked again to the same account). */
    LINKED,
    /** No such code; mistyped, already used, or replaced by a newer one. */
    INVALID_CODE,
    /** The code is older than 10 minutes. */
    EXPIRED,
    /** This Discord account is linked to another NeoBuddy account; /unlink there first. */
    LINKED_TO_ANOTHER_ACCOUNT
  }

  /**
   * @param alreadyVerified true if this address was verified before, so no code was sent
   * @param expiresAt when the emailed code stops working; null if none was sent
   */
  public record EmailVerificationStarted(
      UUID channelId, boolean alreadyVerified, Instant expiresAt) {}

  private final SubscriberService subscribers;
  private final ChannelDirectory directory;
  private final ChannelRepository channels;
  private final LinkCodeRepository codes;
  private final EmailOtpSender otpSender;
  private final VerificationProperties properties;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  ChannelVerificationService(
      SubscriberService subscribers,
      ChannelDirectory directory,
      ChannelRepository channels,
      LinkCodeRepository codes,
      EmailOtpSender otpSender,
      VerificationProperties properties,
      Clock clock) {
    this.subscribers = subscribers;
    this.directory = directory;
    this.channels = channels;
    this.codes = codes;
    this.otpSender = otpSender;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Gives the user a push channel on a fresh, unguessable topic. Calling it again replaces the
   * topic, which cuts off anyone subscribed to the old one.
   */
  @Transactional
  public NtfyChannel createNtfyChannel(UUID userId) {
    String topic = newNtfyTopic();
    ChannelView channel =
        subscribers.addChannel(userId, ChannelType.NTFY, new ChannelAddress(topic));
    subscribers.markVerified(userId, channel.id());
    return new NtfyChannel(channel.id(), topic);
  }

  /**
   * Saves the address as the user's email channel and emails it a one-time code. A new code
   * replaces any earlier one. Limiting how often a user may ask is the caller's job.
   *
   * @throws InvalidChannelAddressException if it is not an email address
   * @throws VerificationNotSentException if the email could not be sent; nothing is saved then
   */
  @Transactional
  public EmailVerificationStarted startEmailVerification(UUID userId, String emailAddress) {
    String address = emailAddress == null ? "" : emailAddress.strip();
    if (address.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(address).matches()) {
      throw new InvalidChannelAddressException(ChannelType.EMAIL);
    }
    ChannelAddress to = new ChannelAddress(address);
    ChannelView channel = subscribers.addChannel(userId, ChannelType.EMAIL, to);
    codes.deleteByUserIdAndChannelType(userId, ChannelType.EMAIL);
    if (channel.verified()) {
      return new EmailVerificationStarted(channel.id(), true, null);
    }
    String code = String.format(Locale.ROOT, "%06d", random.nextInt(OTP_BOUND));
    Instant expiresAt = clock.instant().plus(properties.emailOtpValidity());
    codes.save(new LinkCode(hash(userId, address, code), userId, ChannelType.EMAIL, expiresAt));
    otpSender.send(to, code, properties.emailOtpValidity());
    return new EmailVerificationStarted(channel.id(), false, expiresAt);
  }

  /**
   * Checks the code the user typed. Every call counts as a guess, right or wrong; after the last
   * allowed wrong guess the code is dead and even the right one is refused.
   */
  @Transactional
  public EmailVerificationResult confirmEmail(UUID userId, String typedCode) {
    Optional<LinkCode> pending = pendingEmailCode(userId);
    Optional<Channel> channel = channels.findByUserIdAndType(userId, ChannelType.EMAIL);
    if (pending.isEmpty() || channel.isEmpty()) {
      return EmailVerificationResult.NO_PENDING_CODE;
    }
    LinkCode code = pending.get();
    if (code.isExpired(clock.instant())) {
      codes.delete(code);
      return EmailVerificationResult.EXPIRED;
    }
    if (code.getAttempts() >= properties.emailOtpMaxAttempts()) {
      return EmailVerificationResult.TOO_MANY_ATTEMPTS;
    }
    code.recordAttempt();
    String typed = typedCode == null ? "" : typedCode.strip();
    // The hash covers the address, so a code never verifies an address it was not sent to.
    String expected = hash(userId, channel.get().getAddress(), typed);
    if (MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII),
        code.getCode().getBytes(StandardCharsets.US_ASCII))) {
      codes.delete(code);
      channel.get().markVerified(clock.instant());
      return EmailVerificationResult.VERIFIED;
    }
    return code.getAttempts() >= properties.emailOtpMaxAttempts()
        ? EmailVerificationResult.TOO_MANY_ATTEMPTS
        : EmailVerificationResult.WRONG_CODE;
  }

  /**
   * A new {@code /link} code for the user, replacing any earlier one. Limiting how often a user may
   * ask is the caller's job.
   *
   * @throws SubscriberNotFoundException if the user does not exist
   */
  @Transactional
  public DiscordLinkCode createDiscordLinkCode(UUID userId) {
    subscribers.get(userId);
    codes.deleteByUserIdAndChannelType(userId, ChannelType.DISCORD);
    StringBuilder code = new StringBuilder(LINK_CODE_LENGTH);
    for (int i = 0; i < LINK_CODE_LENGTH; i++) {
      code.append(LINK_ALPHABET[random.nextInt(LINK_ALPHABET.length)]);
    }
    Instant expiresAt = clock.instant().plus(properties.discordLinkCodeValidity());
    codes.save(new LinkCode(discordHash(code.toString()), userId, ChannelType.DISCORD, expiresAt));
    return new DiscordLinkCode(code.toString(), expiresAt);
  }

  /**
   * Ties a Discord account to the NeoBuddy account that asked for the code: the Discord user id
   * becomes the (encrypted) address of the user's verified Discord channel. A code works once.
   *
   * @param typedCode as typed; case, spaces and dashes do not matter
   * @param discordUserId the Discord user who ran {@code /link}, as Discord reports it
   */
  @Transactional
  public DiscordLinkResult linkDiscord(String typedCode, String discordUserId) {
    if (discordUserId == null || !DISCORD_USER_ID.matcher(discordUserId).matches()) {
      throw new IllegalArgumentException("Not a Discord user id");
    }
    String normalized =
        typedCode == null ? "" : typedCode.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    Optional<LinkCode> found =
        normalized.length() == LINK_CODE_LENGTH
            ? codes
                .findCodeForUpdate(discordHash(normalized))
                .filter(c -> c.getChannelType() == ChannelType.DISCORD)
            : Optional.empty();
    if (found.isEmpty()) {
      return new DiscordLinkResult(DiscordLinkStatus.INVALID_CODE, null);
    }
    LinkCode code = found.get();
    codes.delete(code);
    if (code.isExpired(clock.instant())) {
      return new DiscordLinkResult(DiscordLinkStatus.EXPIRED, null);
    }
    UUID userId = code.getUserId();
    Optional<ChannelTarget> owner = directory.findByAddress(ChannelType.DISCORD, discordUserId);
    if (owner.isPresent() && !owner.get().userId().equals(userId)) {
      return new DiscordLinkResult(DiscordLinkStatus.LINKED_TO_ANOTHER_ACCOUNT, null);
    }
    SubscriberView.ChannelView channel =
        subscribers.addChannel(userId, ChannelType.DISCORD, new ChannelAddress(discordUserId));
    subscribers.markVerified(userId, channel.id());
    return new DiscordLinkResult(DiscordLinkStatus.LINKED, userId);
  }

  /** The user's current code, locked so that parallel guesses cannot dodge the attempt limit. */
  private Optional<LinkCode> pendingEmailCode(UUID userId) {
    List<LinkCode> found = codes.findForUpdate(userId, ChannelType.EMAIL);
    Optional<LinkCode> latest = found.stream().max(Comparator.comparing(LinkCode::getExpiresAt));
    // Two requests racing can leave an older code behind; only the newest counts.
    found.stream().filter(code -> code != latest.orElseThrow()).forEach(codes::delete);
    return latest;
  }

  /** A link code is looked up by its hash, so the hash must not depend on who uses it. */
  static String discordHash(String normalizedCode) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(
              sha256.digest(("discord\n" + normalizedCode).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required", e);
    }
  }

  /** "nb-" and 128 random bits in lower-case base32: 26 characters, no padding. */
  String newNtfyTopic() {
    byte[] bytes = new byte[NTFY_TOPIC_RANDOM_BYTES];
    random.nextBytes(bytes);
    StringBuilder topic = new StringBuilder(NTFY_TOPIC_PREFIX);
    int buffer = 0;
    int bits = 0;
    for (byte b : bytes) {
      buffer = (buffer << 8) | (b & 0xff);
      bits += 8;
      while (bits >= 5) {
        topic.append(BASE32[(buffer >> (bits - 5)) & 31]);
        bits -= 5;
      }
    }
    if (bits > 0) {
      topic.append(BASE32[(buffer << (5 - bits)) & 31]);
    }
    return topic.toString();
  }

  /**
   * What is stored instead of the code. The user id keeps two users' equal codes apart (the hash is
   * the table's primary key) and the address ties the code to where it was sent.
   */
  static String hash(UUID userId, String address, String code) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      String input = userId + "\n" + address.toLowerCase(Locale.ROOT) + "\n" + code;
      return HexFormat.of().formatHex(sha256.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required", e);
    }
  }
}
