package com.neobuddy.ingestion.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The part of the v1 seed that ingestion owns ({@code neobuddy.bootstrap.source.*}), plus the slug
 * of the college it belongs to.
 *
 * @param college the bootstrap college; only its slug is read here
 * @param source the first source inbox
 */
@ConfigurationProperties(prefix = "neobuddy.bootstrap")
public record SourceBootstrapProperties(CollegeRef college, SourceSeed source) {

  public SourceBootstrapProperties {
    college = college == null ? new CollegeRef(null) : college;
    source = source == null ? new SourceSeed(null, null) : source;
  }

  public record CollegeRef(String slug) {}

  /**
   * @param email the inbox's address
   * @param refreshToken its refresh token, as printed by {@code ./gradlew authorizeSource}
   */
  public record SourceSeed(String email, String refreshToken) {

    /** Never prints the token. */
    @Override
    public String toString() {
      return "SourceSeed[hidden]";
    }
  }
}
