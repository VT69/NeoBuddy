package com.neobuddy.processing.extract;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Safety limits for extraction ({@code neobuddy.extraction.*}).
 *
 * @param maxAttachmentSize larger attachments are skipped
 * @param excelStreamingThreshold .xlsx files above this size use the streaming reader
 * @param timeout time allowed for one file
 * @param maxConcurrent files extracted at the same time
 */
@ConfigurationProperties(prefix = "neobuddy.extraction")
public record ExtractionProperties(
    @DefaultValue("25MB") DataSize maxAttachmentSize,
    @DefaultValue("5MB") DataSize excelStreamingThreshold,
    @DefaultValue("30s") Duration timeout,
    @DefaultValue("2") int maxConcurrent) {

  public ExtractionProperties {
    if (maxAttachmentSize.toBytes() <= 0 || maxAttachmentSize.toBytes() > Integer.MAX_VALUE - 16) {
      throw new IllegalArgumentException("neobuddy.extraction.max-attachment-size is out of range");
    }
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("neobuddy.extraction.timeout must be positive");
    }
    if (maxConcurrent < 1) {
      throw new IllegalArgumentException("neobuddy.extraction.max-concurrent must be at least 1");
    }
  }
}
