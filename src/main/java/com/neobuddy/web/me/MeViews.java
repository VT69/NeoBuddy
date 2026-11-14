package com.neobuddy.web.me;

import com.neobuddy.notification.AlertSummary;
import com.neobuddy.notification.DeliveryStatus;
import com.neobuddy.subscriber.ChannelType;
import com.neobuddy.subscriber.IdentifierType;
import com.neobuddy.subscriber.UserRole;
import com.neobuddy.subscriber.UserStatus;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** What {@code /api/v1/me} returns, and what the pages render. Only ever the caller's own data. */
public final class MeViews {

  static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH).withZone(IST);

  private MeViews() {}

  public record Me(
      UUID id,
      String email,
      String displayName,
      UserRole role,
      UserStatus status,
      Instant consentedAt,
      List<Identifier> identifiers,
      List<Channel> channels) {

    public boolean hasNeoId() {
      return identifiers.stream().anyMatch(i -> i.type() == IdentifierType.NEO_ID);
    }

    public Identifier identifier(IdentifierType type) {
      return identifiers.stream().filter(i -> i.type() == type).findFirst().orElse(null);
    }

    public Channel channel(ChannelType type) {
      return channels.stream().filter(c -> c.type() == type).findFirst().orElse(null);
    }

    /** True while at least one channel is verified and switched on. */
    public boolean receivesAlerts() {
      return channels.stream().anyMatch(c -> c.verified() && c.enabled());
    }
  }

  public record Identifier(UUID id, IdentifierType type, String value) {

    public String label() {
      return labelOf(type);
    }
  }

  /**
   * @param address the email address or ntfy topic; the caller's own
   */
  public record Channel(
      UUID id, ChannelType type, boolean verified, boolean enabled, String address) {

    public String label() {
      return switch (type) {
        case NTFY -> "Push (ntfy)";
        case EMAIL -> "Email";
        case DISCORD -> "Discord";
      };
    }

    public String state() {
      if (!verified) {
        return "Not verified";
      }
      return enabled ? "On" : "Paused";
    }
  }

  /**
   * @param subscribeUrl the topic's address on the ntfy server
   * @param qrSvg a QR code of {@code subscribeUrl}, as inline SVG
   */
  public record NtfySetup(UUID channelId, String topic, String subscribeUrl, String qrSvg) {

    @Override
    public String toString() {
      return "NtfySetup[channelId=" + channelId + "]";
    }
  }

  public record EmailVerificationStarted(
      UUID channelId, boolean alreadyVerified, Instant expiresAt) {}

  public record TestAlertQueued(UUID deliveryId, String message) {}

  public record Alerts(int page, int size, List<Alert> items) {}

  /** One alert of the history, formatted for a person. */
  public record Alert(
      UUID matchId,
      String eventType,
      String title,
      String company,
      String subject,
      String snippet,
      Instant receivedAt,
      String receivedAtIst,
      boolean test,
      List<Delivery> deliveries) {

    static Alert of(AlertSummary summary) {
      String about =
          summary.company() != null && !summary.company().isBlank()
              ? summary.company()
              : (summary.subject() == null ? "a placement email" : summary.subject());
      String title =
          summary.test()
              ? "Test alert"
              : switch (summary.eventType()) {
                case SHORTLIST -> "Shortlisted: " + about;
                case OFFER -> "Offer: " + about;
                case OTHER ->
                    "Mentioned: " + (summary.subject() == null ? about : summary.subject());
              };
      return new Alert(
          summary.matchId(),
          summary.eventType().name(),
          title,
          summary.company(),
          summary.subject(),
          summary.snippet(),
          summary.receivedAt(),
          WHEN.format(summary.receivedAt()) + " IST",
          summary.test(),
          summary.deliveries().stream().map(Delivery::of).toList());
    }
  }

  public record Delivery(
      ChannelType channelType, DeliveryStatus status, int attempts, Instant sentAt) {

    static Delivery of(AlertSummary.Delivery delivery) {
      return new Delivery(
          delivery.channelType(), delivery.status(), delivery.attempts(), delivery.sentAt());
    }

    public String label() {
      String channel =
          channelType == null
              ? "Removed channel"
              : switch (channelType) {
                case NTFY -> "Push";
                case EMAIL -> "Email";
                case DISCORD -> "Discord";
              };
      String state =
          switch (status) {
            case PENDING -> "sending";
            case RETRY -> "retrying";
            case SENT -> "sent";
            case DEAD -> "failed";
          };
      return channel + ": " + state;
    }

    public String css() {
      return status.name().toLowerCase(Locale.ROOT);
    }
  }

  static String labelOf(IdentifierType type) {
    return switch (type) {
      case NEO_ID -> "NeoID";
      case REG_NO -> "Registration number";
      case COLLEGE_EMAIL -> "College email";
    };
  }
}
