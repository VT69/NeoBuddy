package com.neobuddy.ingestion.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neobuddy.ingestion.gmail.GmailBackedDatabaseTest;
import com.neobuddy.ingestion.source.SourceBootstrap.Report;
import com.neobuddy.ingestion.source.SourceBootstrapProperties.CollegeRef;
import com.neobuddy.ingestion.source.SourceBootstrapProperties.SourceSeed;
import com.neobuddy.ingestion.source.SourceSync.Outcome;
import com.neobuddy.shared.college.College;
import com.neobuddy.shared.crypto.FieldEncryptor;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SourceBootstrapTests extends GmailBackedDatabaseTest {

  @Autowired private SourceBootstrap bootstrap;
  @Autowired private SourceSync sync;
  @Autowired private FieldEncryptor encryptor;

  private College college;
  private String email;
  private final String token = "1//refresh-token-" + UUID.randomUUID();

  @BeforeEach
  void aBootstrapCollege() {
    college = newCollege();
    email = "inbox-" + UUID.randomUUID().toString().substring(0, 8) + "@" + domainOf(college);
  }

  @Test
  void createsTheSourceWithAnEncryptedTokenAndNoCursor() {
    Report report =
        bootstrap.apply(configuration(" " + email.toUpperCase() + " ", " " + token + " "));

    assertThat(report).isEqualTo(Report.CREATED);
    SourceInbox inbox = sources.findByEmail(email).orElseThrow();
    assertThat(inbox.getCollegeId()).isEqualTo(college.getId());
    assertThat(inbox.getStatus()).isEqualTo(SourceStatus.ACTIVE);
    assertThat(inbox.getLastHistoryId()).as("set by the first poll, to start from then").isNull();
    assertThat(inbox.getRefreshToken()).isEqualTo(token);

    byte[] stored = storedToken(inbox.getId());
    assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("refresh-token");
    assertThat(encryptor.decrypt(stored)).isEqualTo(token);
  }

  @Test
  void theFirstPollAfterBootstrapStartsFromTheInboxesCurrentPosition() {
    var account = GOOGLE.newAccount();
    bootstrap.apply(configuration(email, account.refreshToken()));
    SourceInbox inbox = sources.findByEmail(email).orElseThrow();
    long now = newHistoryId();
    account.profile(now);

    assertThat(sync.sync(inbox.getId()).outcome()).isEqualTo(Outcome.STARTED_FROM_NOW);
    assertThat(sources.findById(inbox.getId()).orElseThrow().getLastHistoryId()).isEqualTo(now);
  }

  @Test
  void runningItTwiceChangesNothing() {
    bootstrap.apply(configuration(email, token));
    Map<String, Object> before = row();

    assertThat(bootstrap.apply(configuration(email, token))).isEqualTo(Report.UNCHANGED);
    assertThat(bootstrap.apply(configuration(email.toUpperCase(), token)))
        .isEqualTo(Report.UNCHANGED);

    // The same row, including the very same encrypted bytes.
    assertThat(row()).isEqualTo(before);
    assertThat(count("mail_source", "college_id", college.getId())).isEqualTo(1);
  }

  @Test
  void aNewTokenReconnectsAnExpiredSourceAndKeepsItsCursor() {
    bootstrap.apply(configuration(email, token));
    SourceInbox inbox = sources.findByEmail(email).orElseThrow();
    writer.advanceCursor(inbox.getId(), 4242);
    writer.markAuthExpired(inbox.getId());
    assertThat(sources.findById(inbox.getId()).orElseThrow().getStatus())
        .isEqualTo(SourceStatus.AUTH_EXPIRED);

    assertThat(bootstrap.apply(configuration(email, token)))
        .as("the old, rejected token does not reactivate it")
        .isEqualTo(Report.UNCHANGED);
    assertThat(sources.findById(inbox.getId()).orElseThrow().getStatus())
        .isEqualTo(SourceStatus.AUTH_EXPIRED);

    assertThat(bootstrap.apply(configuration(email, "1//a-new-token")))
        .isEqualTo(Report.RECONNECTED);

    SourceInbox after = sources.findById(inbox.getId()).orElseThrow();
    assertThat(after.getStatus()).isEqualTo(SourceStatus.ACTIVE);
    assertThat(after.getRefreshToken()).isEqualTo("1//a-new-token");
    assertThat(after.getLastHistoryId()).isEqualTo(4242);
  }

  @Test
  void withoutAnEmailOrATokenNothingIsSeeded() {
    assertThat(bootstrap.apply(new SourceBootstrapProperties(null, null)))
        .isEqualTo(Report.NOT_CONFIGURED);
    assertThat(bootstrap.apply(configuration(email, " "))).isEqualTo(Report.NOT_CONFIGURED);
    assertThat(bootstrap.apply(configuration("", token))).isEqualTo(Report.NOT_CONFIGURED);
    assertThat(sources.findByEmail(email)).isEmpty();
  }

  @Test
  void aSourceNeedsItsCollege() {
    assertThatThrownBy(() ->
            bootstrap.apply(
                new SourceBootstrapProperties(new CollegeRef(" "), new SourceSeed(email, token))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("neobuddy.bootstrap.college.slug");
    assertThatThrownBy(() ->
            bootstrap.apply(
                new SourceBootstrapProperties(
                    new CollegeRef("no-such-college"), new SourceSeed(email, token))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(token);
    assertThat(sources.findByEmail(email)).isEmpty();
  }

  @Test
  void anInboxCannotMoveToAnotherCollege() {
    bootstrap.apply(configuration(email, token));
    College other = newCollege();

    assertThatThrownBy(() ->
            bootstrap.apply(
                new SourceBootstrapProperties(
                    new CollegeRef(other.getSlug()), new SourceSeed(email, token))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("another college")
        .hasMessageNotContaining(email);
  }

  @Test
  void propertiesNeverPrintTheToken() {
    assertThat(configuration(email, token).toString()).doesNotContain(token).doesNotContain(email);
  }

  private SourceBootstrapProperties configuration(String sourceEmail, String refreshToken) {
    return new SourceBootstrapProperties(
        new CollegeRef(college.getSlug()), new SourceSeed(sourceEmail, refreshToken));
  }

  private Map<String, Object> row() {
    return jdbc.queryForMap(
        "select id, college_id, email::text, encode(refresh_token_enc, 'hex') as token,"
            + " last_history_id, status, last_synced_at from mail_source where email = ?",
        email);
  }

  private byte[] storedToken(UUID id) {
    return jdbc.queryForObject(
        "select refresh_token_enc from mail_source where id = ?", byte[].class, id);
  }
}
