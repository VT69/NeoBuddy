package com.neobuddy.ingestion.source;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Polling settings ({@code neobuddy.ingestion.*}).
 *
 * @param enabled false switches the scheduled poller off (tests poll by hand)
 * @param pollInterval pause between polls
 * @param fallbackWindowDays how many days of mail to list when a history cursor is stale
 * @param fallbackBatchSize how many of those messages are recorded per transaction
 */
@ConfigurationProperties(prefix = "neobuddy.ingestion")
public record IngestionProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("PT2M") Duration pollInterval,
    @DefaultValue("7") int fallbackWindowDays,
    @DefaultValue("100") int fallbackBatchSize) {}
