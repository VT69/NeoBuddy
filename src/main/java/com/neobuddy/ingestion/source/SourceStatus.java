package com.neobuddy.ingestion.source;

public enum SourceStatus {
  /** Polled every cycle. */
  ACTIVE,
  /** Google rejected the refresh token; waits to be reconnected. */
  AUTH_EXPIRED,
  /** Switched off by an admin. */
  DISABLED
}
