package com.neobuddy.notification;

import java.util.List;
import java.util.UUID;

/** A student's alerts, newest first. */
public interface AlertHistory {

  /**
   * @param page zero-based
   * @param size entries per page
   */
  List<AlertSummary> recent(UUID userId, int page, int size);
}
