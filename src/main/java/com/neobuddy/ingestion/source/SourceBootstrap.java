package com.neobuddy.ingestion.source;

import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeRepository;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes the first source inbox match the bootstrap configuration. An upsert: the same configuration
 * twice changes nothing; a new refresh token replaces the old one and reactivates an inbox that had
 * lost its authorisation.
 *
 * <p>A new inbox is stored without a cursor. The first poll sets it to the inbox's current
 * position, so ingestion starts from then and old mail is never read. Doing that in the poll rather
 * than here keeps startup independent of Google being reachable.
 */
@Service
public class SourceBootstrap {

  public enum Report {
    NOT_CONFIGURED,
    CREATED,
    RECONNECTED,
    UNCHANGED
  }

  private final SourceInboxRepository sources;
  private final CollegeRepository colleges;

  SourceBootstrap(SourceInboxRepository sources, CollegeRepository colleges) {
    this.sources = sources;
    this.colleges = colleges;
  }

  @Transactional
  public Report apply(SourceBootstrapProperties properties) {
    String email = properties.source().email();
    String refreshToken = properties.source().refreshToken();
    if (isBlank(email) || isBlank(refreshToken)) {
      return Report.NOT_CONFIGURED;
    }
    if (isBlank(properties.college().slug())) {
      throw new IllegalStateException(
          "neobuddy.bootstrap.source is set but neobuddy.bootstrap.college.slug is not");
    }
    College college =
        colleges
            .findBySlug(properties.college().slug().strip())
            .orElseThrow(
                () -> new IllegalStateException("The bootstrap college does not exist yet"));
    String address = email.strip().toLowerCase(Locale.ROOT);
    String token = refreshToken.strip();

    Optional<SourceInbox> existing = sources.findByEmail(address);
    if (existing.isEmpty()) {
      sources.save(new SourceInbox(college.getId(), address, token));
      return Report.CREATED;
    }
    SourceInbox inbox = existing.get();
    if (!inbox.getCollegeId().equals(college.getId())) {
      throw new IllegalStateException(
          "neobuddy.bootstrap.source.email is already connected to another college");
    }
    if (inbox.getRefreshToken().equals(token)) {
      return Report.UNCHANGED;
    }
    inbox.reconnect(token);
    return Report.RECONNECTED;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
