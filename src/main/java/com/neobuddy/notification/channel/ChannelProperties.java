package com.neobuddy.notification.channel;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Outbound channel settings ({@code neobuddy.channels.*}). */
@ConfigurationProperties(prefix = "neobuddy.channels")
public record ChannelProperties(
    @DefaultValue Ntfy ntfy,
    @DefaultValue Email email,
    @DefaultValue Ses ses,
    @DefaultValue Brevo brevo) {

  /**
   * @param baseUrl the ntfy server; alerts are POSTed to {@code {baseUrl}/{topic}}
   * @param connectTimeout connect timeout per request
   * @param readTimeout how long to wait for ntfy's answer
   * @param rateLimit how fast alerts may be published
   */
  public record Ntfy(
      @DefaultValue("https://ntfy.sh") String baseUrl,
      @DefaultValue("5s") Duration connectTimeout,
      @DefaultValue("10s") Duration readTimeout,
      @DefaultValue RateLimit rateLimit) {}

  /**
   * @param provider {@code ses} sends through Amazon SES (the AWS deployment); {@code brevo}
   *     through Brevo's HTTPS API (the Render deployment); {@code log} only logs that an email
   *     would have been sent (the local profile's default)
   */
  public record Email(@DefaultValue("ses") String provider) {}

  /**
   * @param from the sender, an address or domain verified in SES; may be {@code Name <address>}
   * @param region the AWS region of the SES identity
   * @param rateLimit how fast emails may be sent
   */
  public record Ses(
      String from, @DefaultValue("ap-south-1") String region, @DefaultValue RateLimit rateLimit) {}

  /**
   * Brevo's transactional email API, used where SMTP is blocked (Render's free tier).
   *
   * @param apiKey the Brevo API key ({@code xkeysib-...}); a secret
   * @param from the sender, a single sender verified in Brevo; may be {@code Name <address>}
   * @param baseUrl Brevo's API; tests point it at a local server
   * @param connectTimeout connect timeout per request
   * @param readTimeout how long to wait for Brevo's answer
   * @param rateLimit how fast emails may be sent
   */
  public record Brevo(
      String apiKey,
      String from,
      @DefaultValue("https://api.brevo.com") String baseUrl,
      @DefaultValue("5s") Duration connectTimeout,
      @DefaultValue("10s") Duration readTimeout,
      @DefaultValue RateLimit rateLimit) {

    /** Never prints the API key. */
    @Override
    public String toString() {
      return "Brevo[from=" + from + ", baseUrl=" + baseUrl + "]";
    }
  }

  /**
   * At most {@code limitForPeriod} sends per {@code refreshPeriod}. A send that finds the limit
   * used up waits up to {@code timeout} for the next period.
   */
  public record RateLimit(
      @DefaultValue("1") int limitForPeriod,
      @DefaultValue("PT1S") Duration refreshPeriod,
      @DefaultValue("PT10S") Duration timeout) {}
}
