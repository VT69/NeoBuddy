package com.neobuddy.subscriber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neobuddy.processing.IdentifierSource;
import com.neobuddy.processing.IdentifierSource.IndexedIdentifier;
import com.neobuddy.processing.IdentifiersChanged;
import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeRepository;
import com.neobuddy.shared.college.CollegeStatus;
import com.neobuddy.shared.crypto.FieldEncryptor;
import com.neobuddy.subscriber.RegistrationRejectedException.Reason;
import com.neobuddy.subscriber.SubscriberView.ChannelView;
import com.neobuddy.subscriber.SubscriberView.IdentifierView;
import com.neobuddy.testsupport.DatabaseTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@DatabaseTest
@RecordApplicationEvents
class SubscriberServiceTests {

  @Autowired private SubscriberService subscribers;
  @Autowired private CollegeRepository colleges;
  @Autowired private IdentifierSource identifierSource;
  @Autowired private FieldEncryptor encryptor;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ApplicationEvents events;

  private College college;
  private College otherCollege;

  @BeforeEach
  void createColleges() {
    college = colleges.save(TestColleges.pilot());
    otherCollege = colleges.save(TestColleges.college(CollegeStatus.ACTIVE));
  }

  // ---- Registration ------------------------------------------------------------------------

  @Test
  void registersAStudentIntoTheCollegeThatOwnsTheirEmailDomain() {
    String email = TestColleges.studentEmail(college);

    SubscriberView student =
        subscribers.registerFromOidc(
            new OidcClaims(
                "sub-" + UUID.randomUUID(), email.toUpperCase(), true, "Tara Target", null));

    assertThat(student.collegeId()).isEqualTo(college.getId());
    assertThat(student.email()).isEqualTo(email);
    assertThat(student.displayName()).isEqualTo("Tara Target");
    assertThat(student.role()).isEqualTo(UserRole.USER);
    assertThat(student.status()).isEqualTo(UserStatus.ACTIVE);
    assertThat(student.consentedAt()).isNotNull();
    assertThat(student.identifiers()).isEmpty();
    assertThat(student.channels()).isEmpty();
  }

  @Test
  void signingInAgainReturnsTheSameAccount() {
    OidcClaims claims = claims(college);

    SubscriberView first = subscribers.registerFromOidc(claims);
    SubscriberView second = subscribers.registerFromOidc(claims);

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(count("app_user", "email", first.email())).isEqualTo(1);
  }

  @Test
  void rejectsAnEmailThatBelongsToNoCollege() {
    assertRejected(
        new OidcClaims(
            "sub-x", "someone@nowhere-" + UUID.randomUUID() + ".example", true, "X", null),
        Reason.COLLEGE_NOT_SUPPORTED);
    assertRejected(
        new OidcClaims("sub-x", "someone@evil" + TestColleges.domainOf(college), true, "X", null),
        Reason.COLLEGE_NOT_SUPPORTED);
  }

  @Test
  void rejectsAPausedCollege() {
    College paused = colleges.save(TestColleges.college(CollegeStatus.PAUSED));

    assertRejected(claims(paused), Reason.COLLEGE_PAUSED);
  }

  @Test
  void rejectsAnUnverifiedEmail() {
    assertRejected(
        new OidcClaims("sub-x", TestColleges.studentEmail(college), false, "X", null),
        Reason.EMAIL_NOT_VERIFIED);
  }

  @Test
  void rejectsAWorkspaceDomainThatIsNotTheColleges() {
    String email = TestColleges.studentEmail(college);

    assertRejected(
        new OidcClaims("sub-x", email, true, "X", "other-workspace.example"),
        Reason.HOSTED_DOMAIN_MISMATCH);
    assertThat(
            subscribers
                .registerFromOidc(
                    new OidcClaims(
                        "sub-" + UUID.randomUUID(),
                        email,
                        true,
                        "X",
                        TestColleges.domainOf(college)))
                .collegeId())
        .isEqualTo(college.getId());
  }

  @Test
  void rejectsASecondGoogleAccountClaimingARegisteredEmail() {
    SubscriberView first = subscribers.registerFromOidc(claims(college));

    assertRejected(
        new OidcClaims("a-different-sub-" + UUID.randomUUID(), first.email(), true, "X", null),
        Reason.EMAIL_ALREADY_REGISTERED);
  }

  // ---- Identifiers -------------------------------------------------------------------------

  @Test
  void storesIdentifiersNormalised() {
    SubscriberView student = subscribers.registerFromOidc(claims(college));
    String neoId = TestColleges.neoId();
    String typed = " " + neoId.substring(0, 4).toLowerCase() + " " + neoId.substring(4) + " ";

    IdentifierView added = subscribers.addIdentifier(student.id(), IdentifierType.NEO_ID, typed);

    assertThat(added.value()).isEqualTo(neoId);
    assertThat(added.type()).isEqualTo(IdentifierType.NEO_ID);
    assertThat(subscribers.get(student.id()).identifiers()).containsExactly(added);
    assertThat(
            jdbc.queryForObject(
                "select normalized_value from identifier where id = ?", String.class, added.id()))
        .isEqualTo(neoId);
  }

  @Test
  void theSameNeoIdTwiceInOneCollegeIsRejected() {
    SubscriberView first = subscribers.registerFromOidc(claims(college));
    SubscriberView second = subscribers.registerFromOidc(claims(college));
    String neoId = TestColleges.neoId();
    subscribers.addIdentifier(first.id(), IdentifierType.NEO_ID, neoId);

    assertThatThrownBy(() ->
            subscribers.addIdentifier(second.id(), IdentifierType.NEO_ID, neoId.toLowerCase()))
        .isInstanceOf(IdentifierAlreadyClaimedException.class)
        .hasMessageNotContaining(neoId);

    assertThat(subscribers.get(second.id()).identifiers()).isEmpty();
    assertThat(count("identifier", "normalized_value", neoId)).isEqualTo(1);
  }

  @Test
  void theDatabaseItselfRefusesADuplicateClaimInOneCollege() {
    SubscriberView first = subscribers.registerFromOidc(claims(college));
    SubscriberView second = subscribers.registerFromOidc(claims(college));
    String neoId = TestColleges.neoId();
    subscribers.addIdentifier(first.id(), IdentifierType.NEO_ID, neoId);

    // What a race past the service's own check would hit.
    assertThatThrownBy(() ->
            jdbc.update(
                "insert into identifier (id, college_id, user_id, type, normalized_value)"
                    + " values (?, ?, ?, 'NEO_ID', ?)",
                UUID.randomUUID(),
                college.getId(),
                second.id(),
                neoId))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
  }

  @Test
  void theSameNeoIdInAnotherCollegeIsAllowed() {
    SubscriberView here = subscribers.registerFromOidc(claims(college));
    SubscriberView there = subscribers.registerFromOidc(claims(otherCollege));
    String neoId = TestColleges.neoId();

    IdentifierView first = subscribers.addIdentifier(here.id(), IdentifierType.NEO_ID, neoId);
    IdentifierView second = subscribers.addIdentifier(there.id(), IdentifierType.NEO_ID, neoId);

    assertThat(first.value()).isEqualTo(second.value()).isEqualTo(neoId);
    assertThat(first.id()).isNotEqualTo(second.id());
    assertThat(identifierSource.loadAll())
        .filteredOn(identifier -> identifier.normalizedValue().equals(neoId))
        .extracting(IndexedIdentifier::collegeId, IndexedIdentifier::userId)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(college.getId(), here.id()),
            org.assertj.core.groups.Tuple.tuple(otherCollege.getId(), there.id()));
  }

  @Test
  void addingTheSameIdentifierAgainIsANoOpButASecondOfThatTypeIsRefused() {
    SubscriberView student = subscribers.registerFromOidc(claims(college));
    String neoId = TestColleges.neoId();
    IdentifierView first = subscribers.addIdentifier(student.id(), IdentifierType.NEO_ID, neoId);

    IdentifierView again = subscribers.addIdentifier(student.id(), IdentifierType.NEO_ID, neoId);

    assertThat(again).isEqualTo(first);
    assertThatThrownBy(() ->
            subscribers.addIdentifier(student.id(), IdentifierType.NEO_ID, TestColleges.neoId()))
        .isInstanceOfSatisfying(
            InvalidIdentifierException.class,
            e ->
                assertThat(e.reason())
                    .isEqualTo(InvalidIdentifierException.Reason.ALREADY_HAS_ONE_OF_THIS_TYPE));
    assertThat(subscribers.get(student.id()).identifiers()).containsExactly(first);
  }

  @Test
  void validatesAgainstTheStudentsOwnCollege() {
    SubscriberView student = subscribers.registerFromOidc(claims(college));

    assertThatThrownBy(
            () -> subscribers.addIdentifier(student.id(), IdentifierType.NEO_ID, "TOOSHORT1"))
        .isInstanceOf(InvalidIdentifierException.class);
    assertThatThrownBy(() ->
            subscribers.addIdentifier(
                student.id(),
                IdentifierType.COLLEGE_EMAIL,
                TestColleges.studentEmail(otherCollege)))
        .isInstanceOfSatisfying(
            InvalidIdentifierException.class,
            e ->
                assertThat(e.reason())
                    .isEqualTo(InvalidIdentifierException.Reason.NOT_A_COLLEGE_EMAIL));

    IdentifierView email =
        subscribers.addIdentifier(
            student.id(), IdentifierType.COLLEGE_EMAIL, student.email().toUpperCase());
    IdentifierView regNo =
        subscribers.addIdentifier(student.id(), IdentifierType.REG_NO, "21-bce-1234");

    assertThat(email.value()).isEqualTo(student.email());
    assertThat(regNo.value()).isEqualTo("21BCE1234");
  }

  @Test
  void removingAnIdentifierFreesItForSomeoneElse() {
    SubscriberView first = subscribers.registerFromOidc(claims(college));
    SubscriberView second = subscribers.registerFromOidc(claims(college));
    String neoId = TestColleges.neoId();
    IdentifierView added = subscribers.addIdentifier(first.id(), IdentifierType.NEO_ID, neoId);

    subscribers.removeIdentifier(first.id(), added.id());

    assertThat(subscribers.get(first.id()).identifiers()).isEmpty();
    assertThat(subscribers.addIdentifier(second.id(), IdentifierType.NEO_ID, neoId).value())
        .isEqualTo(neoId);
  }

  @Test
  void aUserCannotRemoveSomeoneElsesIdentifier() {
    SubscriberView owner = subscribers.registerFromOidc(claims(college));
    SubscriberView intruder = subscribers.registerFromOidc(claims(college));
    IdentifierView added =
        subscribers.addIdentifier(owner.id(), IdentifierType.NEO_ID, TestColleges.neoId());

    assertThatThrownBy(() -> subscribers.removeIdentifier(intruder.id(), added.id()))
        .isInstanceOf(SubscriberNotFoundException.class);
    assertThat(subscribers.get(owner.id()).identifiers()).containsExactly(added);
  }

  @Test
  void everyChangeToTheMatchableIdentifiersPublishesIdentifiersChanged() {
    SubscriberView student = subscribers.registerFromOidc(claims(college));
    assertThat(changesFor(college)).isZero();

    IdentifierView added =
        subscribers.addIdentifier(student.id(), IdentifierType.NEO_ID, TestColleges.neoId());
    assertThat(changesFor(college)).isEqualTo(1);

    subscribers.pause(student.id());
    assertThat(changesFor(college)).isEqualTo(2);
    subscribers.pause(student.id());
    assertThat(changesFor(college)).as("pausing twice changes nothing").isEqualTo(2);

    subscribers.resume(student.id());
    assertThat(changesFor(college)).isEqualTo(3);

    subscribers.removeIdentifier(student.id(), added.id());
    assertThat(changesFor(college)).isEqualTo(4);

    subscribers.deleteAccount(student.id());
    assertThat(changesFor(college)).isEqualTo(5);
    assertThat(changesFor(otherCollege)).isZero();
  }

  // ---- The IdentifierSource port -----------------------------------------------------------

  @Test
  void theIdentifierSourceListsActiveUsersOnly() {
    SubscriberView student = subscribers.registerFromOidc(claims(college));
    String neoId = TestColleges.neoId();
    IdentifierView added = subscribers.addIdentifier(student.id(), IdentifierType.NEO_ID, neoId);
    IndexedIdentifier expected =
        new IndexedIdentifier(college.getId(), student.id(), added.id(), neoId);

    assertThat(identifierSource.loadAll()).contains(expected);

    subscribers.pause(student.id());
    assertThat(subscribers.get(student.id()).status()).isEqualTo(UserStatus.PAUSED);
    assertThat(identifierSource.loadAll()).doesNotContain(expected);

    subscribers.resume(student.id());
    assertThat(identifierSource.loadAll()).contains(expected);
  }

  // ---- Channels ----------------------------------------------------------------------------

  @Test
  void aNewChannelIsUnverifiedUntilMarked() {
    SubscriberView student = subscribers.registerFromOidc(claims(college));

    ChannelView added =
        subscribers.addChannel(student.id(), ChannelType.NTFY, new ChannelAddress("topic-abc"));

    assertThat(added).isEqualTo(new ChannelView(added.id(), ChannelType.NTFY, false, true));

    ChannelView verified = subscribers.markVerified(student.id(), added.id());

    assertThat(verified.verified()).isTrue();
    assertThat(subscribers.get(student.id()).channels()).containsExactly(verified);
  }

  @Test
  void channelAddressesAreEncryptedAtRest() {
    SubscriberView student = subscribers.registerFromOidc(claims(college));
    String address = "tara.personal-" + UUID.randomUUID() + "@mail.example";

    ChannelView added =
        subscribers.addChannel(student.id(), ChannelType.EMAIL, new ChannelAddress(address));

    byte[] stored =
        jdbc.queryForObject(
            "select address_enc from channel where id = ?", byte[].class, added.id());
    assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("tara.personal");
    assertThat(stored[0]).isEqualTo((byte) 1);
    assertThat(stored).hasSize(1 + 12 + address.length() + 16);
    assertThat(encryptor.decrypt(stored)).isEqualTo(address);
  }

  @Test
  void changingTheAddressResetsVerificationButRepeatingItDoesNot() {
    SubscriberView student = subscribers.registerFromOidc(claims(college));
    ChannelView added =
        subscribers.addChannel(student.id(), ChannelType.NTFY, new ChannelAddress("topic-one"));
    subscribers.markVerified(student.id(), added.id());
    byte[] before = storedAddress(added.id());

    ChannelView same =
        subscribers.addChannel(student.id(), ChannelType.NTFY, new ChannelAddress(" topic-one "));

    assertThat(same.id()).isEqualTo(added.id());
    assertThat(same.verified()).isTrue();
    assertThat(storedAddress(added.id()))
        .as("an unchanged address is not re-encrypted")
        .isEqualTo(before);

    ChannelView changed =
        subscribers.addChannel(student.id(), ChannelType.NTFY, new ChannelAddress("topic-two"));

    assertThat(changed.id()).isEqualTo(added.id());
    assertThat(changed.verified()).isFalse();
    assertThat(encryptor.decrypt(storedAddress(added.id()))).isEqualTo("topic-two");
    assertThat(count("channel", "user_id", student.id())).isEqualTo(1);
  }

  @Test
  void aUserCannotVerifySomeoneElsesChannel() {
    SubscriberView owner = subscribers.registerFromOidc(claims(college));
    SubscriberView intruder = subscribers.registerFromOidc(claims(college));
    ChannelView channel =
        subscribers.addChannel(owner.id(), ChannelType.NTFY, new ChannelAddress("topic-x"));

    assertThatThrownBy(() -> subscribers.markVerified(intruder.id(), channel.id()))
        .isInstanceOf(SubscriberNotFoundException.class);
    assertThat(subscribers.get(owner.id()).channels().get(0).verified()).isFalse();
  }

  // ---- Deletion ----------------------------------------------------------------------------

  @Test
  void deleteAccountCascadesToEverythingTheUserOwns() {
    SubscriberView student = subscribers.registerFromOidc(claims(college));
    SubscriberView bystander = subscribers.registerFromOidc(claims(college));
    IdentifierView identifier =
        subscribers.addIdentifier(student.id(), IdentifierType.NEO_ID, TestColleges.neoId());
    IdentifierView bystanderIdentifier =
        subscribers.addIdentifier(bystander.id(), IdentifierType.NEO_ID, TestColleges.neoId());
    ChannelView channel =
        subscribers.addChannel(student.id(), ChannelType.NTFY, new ChannelAddress("topic-del"));
    ChannelView bystanderChannel =
        subscribers.addChannel(bystander.id(), ChannelType.NTFY, new ChannelAddress("topic-keep"));

    String messageId = "<" + UUID.randomUUID() + "@mail.example>";
    UUID sourceId = UUID.randomUUID();
    jdbc.update(
        "insert into mail_source (id, college_id, email, refresh_token_enc, status)"
            + " values (?, ?, ?, ?, 'ACTIVE')",
        sourceId,
        college.getId(),
        "inbox-" + sourceId + "@" + TestColleges.domainOf(college),
        new byte[] {1});
    jdbc.update(
        "insert into processed_email (rfc_message_id, college_id, source_id, gmail_id, sender,"
            + " received_at, status) values (?, ?, ?, 'g1', 'placements@x.example', now(), 'PROCESSED')",
        messageId,
        college.getId(),
        sourceId);
    UUID matchId = insertMatch(student.id(), messageId, identifier.id());
    UUID bystanderMatchId = insertMatch(bystander.id(), messageId, bystanderIdentifier.id());
    insertDelivery(matchId, channel.id());
    insertDelivery(bystanderMatchId, bystanderChannel.id());
    jdbc.update(
        "insert into link_code (code, user_id, channel_type, expires_at)"
            + " values (?, ?, 'DISCORD', now() + interval '10 minutes')",
        "code-" + UUID.randomUUID(),
        student.id());

    subscribers.deleteAccount(student.id());

    assertThat(count("app_user", "id", student.id())).isZero();
    assertThat(count("identifier", "user_id", student.id())).isZero();
    assertThat(count("channel", "user_id", student.id())).isZero();
    assertThat(count("link_code", "user_id", student.id())).isZero();
    assertThat(count("match_hit", "user_id", student.id())).isZero();
    assertThat(count("delivery", "match_id", matchId)).isZero();
    assertThatThrownBy(() -> subscribers.get(student.id()))
        .isInstanceOf(SubscriberNotFoundException.class);

    // Nobody else's data is touched, and the email record itself stays.
    assertThat(count("app_user", "id", bystander.id())).isEqualTo(1);
    assertThat(count("identifier", "user_id", bystander.id())).isEqualTo(1);
    assertThat(count("channel", "user_id", bystander.id())).isEqualTo(1);
    assertThat(count("match_hit", "user_id", bystander.id())).isEqualTo(1);
    assertThat(count("delivery", "match_id", bystanderMatchId)).isEqualTo(1);
    assertThat(count("processed_email", "rfc_message_id", messageId)).isEqualTo(1);
  }

  @Test
  void aDeletedAccountsIdentifiersCanBeClaimedAgain() {
    SubscriberView first = subscribers.registerFromOidc(claims(college));
    String neoId = TestColleges.neoId();
    subscribers.addIdentifier(first.id(), IdentifierType.NEO_ID, neoId);

    subscribers.deleteAccount(first.id());
    SubscriberView second = subscribers.registerFromOidc(claims(college));

    assertThat(subscribers.addIdentifier(second.id(), IdentifierType.NEO_ID, neoId).value())
        .isEqualTo(neoId);
  }

  @Test
  void operationsOnAnUnknownUserFailCleanly() {
    UUID nobody = UUID.randomUUID();

    assertThatThrownBy(() -> subscribers.get(nobody))
        .isInstanceOf(SubscriberNotFoundException.class);
    assertThatThrownBy(() -> subscribers.addIdentifier(nobody, IdentifierType.NEO_ID, "N1B7J5V6"))
        .isInstanceOf(SubscriberNotFoundException.class);
    assertThatThrownBy(() -> subscribers.deleteAccount(nobody))
        .isInstanceOf(SubscriberNotFoundException.class);
  }

  // ---- Helpers -----------------------------------------------------------------------------

  private static OidcClaims claims(College college) {
    return new OidcClaims(
        "sub-" + UUID.randomUUID(), TestColleges.studentEmail(college), true, "Test Student", null);
  }

  private void assertRejected(OidcClaims claims, Reason reason) {
    assertThatThrownBy(() -> subscribers.registerFromOidc(claims))
        .isInstanceOfSatisfying(
            RegistrationRejectedException.class, e -> assertThat(e.reason()).isEqualTo(reason))
        .hasMessageNotContaining("@");
  }

  private long changesFor(College changed) {
    return events.stream(IdentifiersChanged.class)
        .filter(event -> event.collegeId().equals(changed.getId()))
        .count();
  }

  private int count(String table, String column, Object value) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
  }

  private byte[] storedAddress(UUID channelId) {
    return jdbc.queryForObject(
        "select address_enc from channel where id = ?", byte[].class, channelId);
  }

  private UUID insertMatch(UUID userId, String messageId, UUID identifierId) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into match_hit (id, user_id, email_id, identifier_id, event_type)"
            + " values (?, ?, ?, ?, 'SHORTLIST')",
        id,
        userId,
        messageId,
        identifierId);
    return id;
  }

  private void insertDelivery(UUID matchId, UUID channelId) {
    jdbc.update(
        "insert into delivery (id, match_id, channel_id) values (?, ?, ?)",
        UUID.randomUUID(),
        matchId,
        channelId);
  }

  // ---- Sign-in without registering -------------------------------------------------------------

  @Test
  void signInFindsNoAccountForANewStudentAndCreatesNothing() {
    String email = TestColleges.studentEmail(college);

    assertThat(
            subscribers.signIn(
                new OidcClaims("sub-" + UUID.randomUUID(), email, true, "New", null)))
        .isEmpty();
    assertThat(subscribers.findByEmail(email)).isEmpty();
  }

  @Test
  void signInFindsTheAccountOfAKnownGoogleIdentity() {
    String sub = "sub-" + UUID.randomUUID();
    SubscriberView created =
        subscribers.registerFromOidc(
            new OidcClaims(sub, TestColleges.studentEmail(college), true, "Tara", null));

    assertThat(subscribers.signIn(new OidcClaims(sub, created.email(), true, "Tara", null)))
        .hasValueSatisfying(found -> assertThat(found.id()).isEqualTo(created.id()));
  }

  @Test
  void signInRefusesWhatRegistrationRefuses() {
    assertThatThrownBy(
            () -> subscribers.signIn(new OidcClaims("s", "someone@gmail.com", true, "X", null)))
        .isInstanceOfSatisfying(
            RegistrationRejectedException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.COLLEGE_NOT_SUPPORTED));
  }

  @Test
  void aChannelCanBeSwitchedOffAndOnByItsOwnerOnly() {
    SubscriberView student =
        subscribers.registerFromOidc(
            new OidcClaims(
                "sub-" + UUID.randomUUID(), TestColleges.studentEmail(college), true, "T", null));
    ChannelView channel =
        subscribers.addChannel(student.id(), ChannelType.NTFY, new ChannelAddress("nb-topic"));

    assertThat(subscribers.setChannelEnabled(student.id(), channel.id(), false).enabled())
        .isFalse();
    assertThat(subscribers.setChannelEnabled(student.id(), channel.id(), true).enabled()).isTrue();
    assertThatThrownBy(() -> subscribers.setChannelEnabled(UUID.randomUUID(), channel.id(), false))
        .isInstanceOf(SubscriberNotFoundException.class);
  }
}
