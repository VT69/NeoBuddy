package com.neobuddy.shared.observability;

import org.slf4j.MDC;

/**
 * Puts the correlation fields of the JSON logs into the MDC for the duration of a block:
 *
 * <pre>
 * try (LogContext ignored = LogContext.of(rfcMessageId, collegeSlug)) { ... }
 * </pre>
 *
 * Only ids go here: the RFC Message-ID and the college slug. Values already present are restored
 * when the block ends, so nested blocks are safe.
 */
public final class LogContext implements AutoCloseable {

  public static final String RFC_MESSAGE_ID = "rfcMessageId";
  public static final String COLLEGE_SLUG = "collegeSlug";

  /**
   * Marks a log line as an operational event that CloudWatch counts with a metric filter, e.g.
   * {@code {"event":"DeliveryDead"}}. Set it around the one line with {@code MDC.putCloseable}.
   */
  public static final String EVENT = "event";

  private final String previousMessageId;
  private final String previousSlug;

  private LogContext(String rfcMessageId, String collegeSlug) {
    this.previousMessageId = MDC.get(RFC_MESSAGE_ID);
    this.previousSlug = MDC.get(COLLEGE_SLUG);
    put(RFC_MESSAGE_ID, rfcMessageId);
    put(COLLEGE_SLUG, collegeSlug);
  }

  /**
   * @param rfcMessageId may be null, then the field is left as it was
   * @param collegeSlug may be null, then the field is left as it was
   */
  public static LogContext of(String rfcMessageId, String collegeSlug) {
    return new LogContext(rfcMessageId, collegeSlug);
  }

  public static LogContext college(String collegeSlug) {
    return new LogContext(null, collegeSlug);
  }

  @Override
  public void close() {
    restore(RFC_MESSAGE_ID, previousMessageId);
    restore(COLLEGE_SLUG, previousSlug);
  }

  private static void put(String key, String value) {
    if (value != null) {
      MDC.put(key, value);
    }
  }

  private static void restore(String key, String value) {
    if (value == null) {
      MDC.remove(key);
    } else {
      MDC.put(key, value);
    }
  }
}
