package com.neobuddy.processing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobuddy.ingestion.gmail.GmailBackedDatabaseTest;
import com.neobuddy.ingestion.source.SourceInbox;
import com.neobuddy.shared.college.College;
import com.neobuddy.subscriber.IdentifierType;
import com.neobuddy.subscriber.OidcClaims;
import com.neobuddy.subscriber.SubscriberService;
import com.neobuddy.subscriber.SubscriberView;
import com.neobuddy.subscriber.TestColleges;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SnippetRetentionTests extends GmailBackedDatabaseTest {

  @Autowired private SnippetRetention retention;
  @Autowired private SubscriberService subscribers;

  @Test
  void snippetsOlderThan180DaysAreClearedAndTheMatchStays() {
    College college = newCollege();
    SourceInbox source = newUnpolledSource(college, GOOGLE.newAccount());
    SubscriberView student =
        subscribers.registerFromOidc(
            new OidcClaims(
                "sub-" + UUID.randomUUID(),
                "s-" + UUID.randomUUID().toString().substring(0, 6) + "@" + domainOf(college),
                true,
                "S",
                null));
    UUID identifier =
        subscribers.addIdentifier(student.id(), IdentifierType.NEO_ID, TestColleges.neoId()).id();
    UUID old =
        match(college, source, student.id(), identifier, Instant.now().minus(181, ChronoUnit.DAYS));
    UUID recent =
        match(college, source, student.id(), identifier, Instant.now().minus(179, ChronoUnit.DAYS));

    assertThat(retention.purge()).isGreaterThanOrEqualTo(1);

    assertThat(jdbc.queryForMap("select context_snippet from match_hit where id = ?", old))
        .containsEntry("context_snippet", null);
    assertThat(jdbc.queryForMap("select context_snippet from match_hit where id = ?", recent))
        .containsEntry("context_snippet", "row");
    assertThat(retention.purge()).isZero();
  }

  private UUID match(College college, SourceInbox source, UUID user, UUID identifier, Instant at) {
    String messageId = newMessageId();
    jdbc.update(
        "insert into processed_email (rfc_message_id, college_id, source_id, gmail_id, sender, received_at, status)"
            + " values (?, ?, ?, 'g', 'p', ?, 'PROCESSED')",
        messageId,
        college.getId(),
        source.getId(),
        Timestamp.from(at));
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into match_hit (id, user_id, email_id, identifier_id, event_type, context_snippet, created_at)"
            + " values (?, ?, ?, ?, 'SHORTLIST', 'row', ?)",
        id,
        user,
        messageId,
        identifier,
        Timestamp.from(at));
    return id;
  }
}
