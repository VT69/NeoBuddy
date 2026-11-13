package com.neobuddy.notification.channel;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/**
 * Picks the email channel: Amazon SES unless {@code neobuddy.channels.email.provider} is {@code
 * log}. AWS credentials come from the SDK's default chain (environment, profile, instance role) and
 * are only looked up when the first email is sent.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChannelProperties.class)
class ChannelConfig {

  private static final String PREFIX = "neobuddy.channels.email";

  @Bean
  @ConditionalOnProperty(
      prefix = PREFIX,
      name = "provider",
      havingValue = "ses",
      matchIfMissing = true)
  SesV2Client sesClient(ChannelProperties properties) {
    return SesV2Client.builder().region(Region.of(properties.ses().region())).build();
  }

  @Bean
  @ConditionalOnProperty(
      prefix = PREFIX,
      name = "provider",
      havingValue = "ses",
      matchIfMissing = true)
  SesEmailChannel sesEmailChannel(
      SesV2Client ses, ChannelProperties properties, AlertRenderer renderer) {
    return new SesEmailChannel(ses, properties.ses(), renderer);
  }

  @Bean
  @ConditionalOnProperty(prefix = PREFIX, name = "provider", havingValue = "log")
  LoggingEmailChannel loggingEmailChannel() {
    return new LoggingEmailChannel();
  }
}
