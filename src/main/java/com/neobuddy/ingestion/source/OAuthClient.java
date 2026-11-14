package com.neobuddy.ingestion.source;

/**
 * The Google OAuth client that issued a source inbox's refresh token. A refresh token can only be
 * refreshed with the client that issued it.
 */
public enum OAuthClient {
  /** "Desktop app" client, used by {@code ./gradlew authorizeSource} and the bootstrap. */
  DESKTOP,
  /** "Web application" client, used by the admin console's invite link. */
  WEB
}
