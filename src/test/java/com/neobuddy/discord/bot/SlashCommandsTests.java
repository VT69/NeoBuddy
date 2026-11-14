package com.neobuddy.discord.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neobuddy.notification.AlertHistory;
import com.neobuddy.notification.DeliveryResult;
import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeRepository;
import com.neobuddy.subscriber.ChannelDirectory;
import com.neobuddy.subscriber.ChannelType;
import com.neobuddy.subscriber.ChannelVerificationService;
import com.neobuddy.subscriber.IdentifierType;
import com.neobuddy.subscriber.OidcClaims;
import com.neobuddy.subscriber.SubscriberService;
import com.neobuddy.subscriber.SubscriberView;
import com.neobuddy.subscriber.SubscriberView.ChannelView;
import com.neobuddy.subscriber.TestColleges;
import com.neobuddy.testsupport.DatabaseTest;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The slash commands' logic against the real database: linking, codes, status and switching. */
@DatabaseTest
class SlashCommandsTests {

  @Autowired private ChannelVerificationService verification;
  @Autowired private ChannelDirectory directory;
  @Autowired private SubscriberService subscribers;
  @Autowired private AlertHistory history;
  @Autowired private CollegeRepository colleges;
  @Autowired private JdbcTemplate jdbc;

  private final DiscordDms dms = mock(DiscordDms.class);
  private SlashCommands commands;
  private College college;
  private SubscriberView student;
  private String discordUser;

  @BeforeEach
  void aStudentAndADiscordUser() {
    commands = new SlashCommands(verification, directory, subscribers, history, dms);
    when(dms.sendText(anyString(), anyString())).thenReturn(DeliveryResult.success());
    college = colleges.save(TestColleges.pilot());
    student = newStudent();
    discordUser = newDiscordUser();
  }

  private SubscriberView newStudent() {
    SubscriberView view =
        subscribers.registerFromOidc(
            new OidcClaims(
                "sub-" + UUID.randomUUID(),
                TestColleges.studentEmail(college),
                true,
                "Tara",
                null));
    subscribers.addIdentifier(view.id(), IdentifierType.NEO_ID, TestColleges.neoId());
    return subscribers.get(view.id());
  }

  private static String newDiscordUser() {
    return Long.toString(
        ThreadLocalRandom.current().nextLong(100_000_000_000_000_000L, 999_999_999_999_999_999L));
  }

  private String code(SubscriberView user) {
    String code = verification.createDiscordLinkCode(user.id()).code();
    return code.substring(0, 4) + "-" + code.substring(4);
  }

  private Optional<ChannelView> discordChannel(SubscriberView user) {
    return subscribers.get(user.id()).channels().stream()
        .filter(c -> c.type() == ChannelType.DISCORD)
        .findFirst();
  }

  // ---- /link -----------------------------------------------------------------------------------

  @Test
  void linkTiesTheDiscordAccountAsAVerifiedChannelAndConfirmsByDm() {
    String reply = commands.handle("link", discordUser, code(student).toLowerCase());

    assertThat(reply).contains("Linked").contains("Check your DMs");
    assertThat(discordChannel(student)).hasValueSatisfying(c -> assertThat(c.verified()).isTrue());
    assertThat(directory.findByAddress(ChannelType.DISCORD, discordUser))
        .hasValueSatisfying(target -> assertThat(target.userId()).isEqualTo(student.id()));
    verify(dms).sendText(eq(discordUser), anyString());
    // The Discord user id is stored encrypted, not in clear.
    assertThat(
            jdbc.queryForObject(
                "select count(*) from channel where encode(address_enc, 'escape') like ?",
                Integer.class,
                "%" + discordUser + "%"))
        .isZero();
  }

  @Test
  void whenTheDmFailsTheReplyExplainsHowToAllowDms() {
    when(dms.sendText(anyString(), anyString()))
        .thenReturn(DeliveryResult.permanent("DISCORD_DMS_CLOSED"));

    String reply = commands.handle("link", discordUser, code(student));

    assertThat(reply).contains("Linked, but we could not DM you").contains("Direct Messages");
    assertThat(discordChannel(student)).isPresent();
  }

  @Test
  void aCodeWorksOnlyOnce() {
    String code = code(student);
    commands.handle("link", discordUser, code);

    String again = commands.handle("link", newDiscordUser(), code);

    assertThat(again).contains("That code does not work");
  }

  @Test
  void anExpiredCodeIsRefusedAndUsedUp() {
    String code = code(student);
    jdbc.update(
        "update link_code set expires_at = now() - interval '1 second' where user_id = ?",
        student.id());

    assertThat(commands.handle("link", discordUser, code)).contains("expired");
    assertThat(discordChannel(student)).isEmpty();
    assertThat(commands.handle("link", discordUser, code)).contains("does not work");
  }

  @Test
  void aNewCodeReplacesTheOldOne() {
    String first = code(student);
    String second = code(student);

    assertThat(commands.handle("link", discordUser, first)).contains("does not work");
    assertThat(commands.handle("link", discordUser, second)).contains("Linked");
  }

  @Test
  void aCodeIsValidForTenMinutes() {
    var created = verification.createDiscordLinkCode(student.id());

    assertThat(java.time.Duration.between(java.time.Instant.now(), created.expiresAt()))
        .isBetween(java.time.Duration.ofMinutes(9), java.time.Duration.ofMinutes(10));
    assertThat(created.code()).matches("[A-HJ-NP-Z2-9]{8}");
    assertThat(created.toString()).doesNotContain(created.code());
    // Stored as a hash, never as the code.
    assertThat(
            jdbc.queryForList(
                "select code from link_code where user_id = ?", String.class, student.id()))
        .singleElement()
        .satisfies(
            stored -> assertThat(stored).matches("[0-9a-f]{64}").doesNotContain(created.code()));
  }

  @Test
  void aDiscordAccountLinkedToSomeoneElseIsNotTakenOver() {
    commands.handle("link", discordUser, code(student));
    SubscriberView other = newStudent();

    String reply = commands.handle("link", discordUser, code(other));

    assertThat(reply).contains("already linked to another NeoBuddy account");
    assertThat(discordChannel(other)).isEmpty();
    assertThat(directory.findByAddress(ChannelType.DISCORD, discordUser))
        .hasValueSatisfying(target -> assertThat(target.userId()).isEqualTo(student.id()));
  }

  @Test
  void linkingAgainToTheSameAccountIsFine() {
    commands.handle("link", discordUser, code(student));

    assertThat(commands.handle("link", discordUser, code(student))).contains("Linked");
    assertThat(subscribers.get(student.id()).channels()).hasSize(1);
  }

  @Test
  void aDiscordUserGetsFiveTriesPerTenMinutes() {
    for (int i = 0; i < 5; i++) {
      assertThat(commands.handle("link", discordUser, "WRONG-CODE")).contains("does not work");
    }

    String sixth = commands.handle("link", discordUser, code(student));

    assertThat(sixth).contains("Too many tries");
    assertThat(discordChannel(student)).isEmpty();
  }

  // ---- /status, /pause, /resume, /unlink ------------------------------------------------------

  @Test
  void everyOtherCommandNeedsALinkedAccount() {
    for (String command : new String[] {"status", "pause", "resume", "unlink"}) {
      assertThat(commands.handle(command, discordUser, null)).isEqualTo(SlashCommands.NOT_LINKED);
    }
    verify(dms, never()).sendText(anyString(), anyString());
  }

  @Test
  void statusShowsIdentifiersAndTheLastAlerts() {
    commands.handle("link", discordUser, code(student));

    String status = commands.handle("status", discordUser, null);

    assertThat(status)
        .contains("**Discord alerts:** on")
        .contains("NeoID `" + student.identifiers().get(0).value() + "`")
        .contains("**Last alerts:** none yet");
  }

  @Test
  void pauseAndResumeSwitchOnlyTheDiscordChannel() {
    commands.handle("link", discordUser, code(student));
    verification.createNtfyChannel(student.id());

    assertThat(commands.handle("pause", discordUser, null)).contains("paused");
    assertThat(discordChannel(student)).hasValueSatisfying(c -> assertThat(c.enabled()).isFalse());
    assertThat(subscribers.get(student.id()).channels())
        .filteredOn(c -> c.type() == ChannelType.NTFY)
        .singleElement()
        .satisfies(c -> assertThat(c.enabled()).isTrue());
    assertThat(commands.handle("status", discordUser, null)).contains("paused (/resume)");

    assertThat(commands.handle("resume", discordUser, null)).contains("back on");
    assertThat(discordChannel(student)).hasValueSatisfying(c -> assertThat(c.enabled()).isTrue());
  }

  @Test
  void unlinkRemovesTheChannelAndTheAccountCanBeLinkedAgain() {
    commands.handle("link", discordUser, code(student));

    assertThat(commands.handle("unlink", discordUser, null)).contains("Unlinked");
    assertThat(discordChannel(student)).isEmpty();
    assertThat(commands.handle("status", discordUser, null)).isEqualTo(SlashCommands.NOT_LINKED);

    SubscriberView other = newStudent();
    assertThat(commands.handle("link", discordUser, code(other))).contains("Linked");
  }
}
