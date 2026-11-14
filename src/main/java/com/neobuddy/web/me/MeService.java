package com.neobuddy.web.me;

import com.neobuddy.notification.AlertHistory;
import com.neobuddy.notification.TestAlerts;
import com.neobuddy.subscriber.ChannelAddress;
import com.neobuddy.subscriber.ChannelDirectory;
import com.neobuddy.subscriber.ChannelType;
import com.neobuddy.subscriber.ChannelVerificationService;
import com.neobuddy.subscriber.EmailVerificationResult;
import com.neobuddy.subscriber.IdentifierType;
import com.neobuddy.subscriber.SubscriberService;
import com.neobuddy.subscriber.SubscriberView;
import com.neobuddy.web.me.MeViews.Alert;
import com.neobuddy.web.me.MeViews.Alerts;
import com.neobuddy.web.me.MeViews.Channel;
import com.neobuddy.web.me.MeViews.EmailVerificationStarted;
import com.neobuddy.web.me.MeViews.Identifier;
import com.neobuddy.web.me.MeViews.Me;
import com.neobuddy.web.me.MeViews.NtfySetup;
import com.neobuddy.web.me.MeViews.TestAlertQueued;
import com.neobuddy.web.support.EmailCodeRejectedException;
import com.neobuddy.web.support.QrCodes;
import com.neobuddy.web.support.RateLimiter;
import com.neobuddy.web.support.RateLimiter.Limit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Everything a signed-in student can do with their own account; the JSON API, the htmx fragments
 * and the pages all go through here, so they behave the same. Every method takes the caller's user
 * id from the session, never from the request.
 */
@Service
public class MeService {

  public static final int ALERT_PAGE_SIZE = 20;

  private final SubscriberService subscribers;
  private final ChannelDirectory channels;
  private final ChannelVerificationService verification;
  private final TestAlerts testAlerts;
  private final AlertHistory history;
  private final RateLimiter rateLimiter;
  private final String ntfyBaseUrl;

  MeService(
      SubscriberService subscribers,
      ChannelDirectory channels,
      ChannelVerificationService verification,
      TestAlerts testAlerts,
      AlertHistory history,
      RateLimiter rateLimiter,
      @Value("${neobuddy.channels.ntfy.base-url:https://ntfy.sh}") String ntfyBaseUrl) {
    this.subscribers = subscribers;
    this.channels = channels;
    this.verification = verification;
    this.testAlerts = testAlerts;
    this.history = history;
    this.rateLimiter = rateLimiter;
    this.ntfyBaseUrl = ntfyBaseUrl.strip().replaceAll("/+$", "");
  }

  public Me me(UUID userId) {
    SubscriberView view = subscribers.get(userId);
    return new Me(
        view.id(),
        view.email(),
        view.displayName(),
        view.role(),
        view.status(),
        view.consentedAt(),
        view.identifiers().stream().map(i -> new Identifier(i.id(), i.type(), i.value())).toList(),
        view.channels().stream()
            .map(c ->
                new Channel(
                    c.id(),
                    c.type(),
                    c.verified(),
                    c.enabled(),
                    channels.addressOf(userId, c.id()).map(ChannelAddress::value).orElse(null)))
            .toList());
  }

  public Identifier addIdentifier(UUID userId, IdentifierType type, String value) {
    SubscriberView.IdentifierView added = subscribers.addIdentifier(userId, type, value);
    return new Identifier(added.id(), added.type(), added.value());
  }

  public void removeIdentifier(UUID userId, UUID identifierId) {
    subscribers.removeIdentifier(userId, identifierId);
  }

  /** Saves the address and emails it a code, at most 3 times an hour per user. */
  public EmailVerificationStarted startEmailVerification(UUID userId, String address) {
    rateLimiter.consume(Limit.OTP_SEND, userId.toString());
    ChannelVerificationService.EmailVerificationStarted started =
        verification.startEmailVerification(userId, address);
    return new EmailVerificationStarted(
        started.channelId(), started.alreadyVerified(), started.expiresAt());
  }

  /**
   * @throws EmailCodeRejectedException unless the code verified the channel
   */
  public void confirmEmail(UUID userId, String code) {
    EmailVerificationResult result = verification.confirmEmail(userId, code);
    if (result != EmailVerificationResult.VERIFIED) {
      throw new EmailCodeRejectedException(result);
    }
  }

  /** A new push topic; replaces any earlier one. */
  public NtfySetup createNtfyChannel(UUID userId) {
    ChannelVerificationService.NtfyChannel created = verification.createNtfyChannel(userId);
    return ntfySetup(created.channelId(), created.topic());
  }

  /** How to subscribe to the user's existing topic again, e.g. on a new phone. */
  public NtfySetup ntfySetup(UUID channelId, String topic) {
    String url = ntfyBaseUrl + "/" + topic;
    return new NtfySetup(channelId, topic, url, QrCodes.svg(url, "QR code for your ntfy topic"));
  }

  public Channel setChannelEnabled(UUID userId, UUID channelId, boolean enabled) {
    subscribers.setChannelEnabled(userId, channelId, enabled);
    return me(userId).channels().stream()
        .filter(c -> c.id().equals(channelId))
        .findFirst()
        .orElseThrow();
  }

  /** Switches every channel off (or on). Matches are still recorded, so the history stays whole. */
  public void setAllChannelsEnabled(UUID userId, boolean enabled) {
    subscribers
        .get(userId)
        .channels()
        .forEach(c -> subscribers.setChannelEnabled(userId, c.id(), enabled));
  }

  /** At most 5 test alerts an hour per user. */
  public TestAlertQueued sendTestAlert(UUID userId, UUID channelId) {
    rateLimiter.consume(Limit.TEST_ALERT, userId.toString());
    UUID deliveryId = testAlerts.send(userId, channelId);
    return new TestAlertQueued(
        deliveryId, "Test alert on its way. It should arrive within a minute.");
  }

  public Alerts alerts(UUID userId, int page) {
    int safePage = Math.max(page, 0);
    return new Alerts(
        safePage,
        ALERT_PAGE_SIZE,
        history.recent(userId, safePage, ALERT_PAGE_SIZE).stream().map(Alert::of).toList());
  }

  public void deleteAccount(UUID userId) {
    subscribers.deleteAccount(userId);
  }

  /** The push channel's setup, if the user has a push channel. */
  public NtfySetup existingNtfySetup(Me me) {
    Channel push = me.channel(ChannelType.NTFY);
    return push == null || push.address() == null ? null : ntfySetup(push.id(), push.address());
  }
}
