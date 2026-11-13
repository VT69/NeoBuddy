package com.neobuddy.subscriber;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Channel verification settings ({@code neobuddy.verification.*}).
 *
 * @param emailOtpValidity how long an emailed one-time code works
 * @param emailOtpMaxAttempts how many guesses a code allows before it stops working
 */
@ConfigurationProperties(prefix = "neobuddy.verification")
public record VerificationProperties(
    @DefaultValue("PT10M") Duration emailOtpValidity, @DefaultValue("5") int emailOtpMaxAttempts) {}
