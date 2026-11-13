package com.neobuddy.subscriber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeRepository;
import com.neobuddy.subscriber.ChannelVerificationService.EmailVerificationStarted;
import com.neobuddy.subscriber.ChannelVerificationService.NtfyChannel;
import com.neobuddy.subscriber.SubscriberView.ChannelView;
import com.neobuddy.testsupport.DatabaseTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@DatabaseTest
class ChannelVerificationServiceTests {

  /** Stands in for the email channel and remembers the codes it was asked to send. */
  static class RecordingOtpSender implements EmailOtpSender {

    record Sent(String to, String code, Duration validFor) {}

    final List<Sent> sent = new ArrayList<>();
    boolean failing;

    @Override
    public void send(ChannelAddress to, String code, Duration validFor) {
      if (failing) {
        throw new VerificationNotSentException("SES MessageRejectedException");
      }
      sent.add(new Sent(to.value(), code, validFor));
    }

    String lastCode() {
      return sent.get(sent.size() - 1).code();
    }
  }

  @TestConfiguration
  static class RecordingOtpSenderConfig {

    @Bean
    @Primary
    RecordingOtpSender recordingOtpSender() {
      return new RecordingOtpSender();
    }
  }

  @Autowired private ChannelVerificationService verification;
  @Autowired private SubscriberService subscribers;
  @Autowired private ChannelDirectory directory;
  @Autowired private CollegeRepository colleges;
  @Autowired private RecordingOtpSender otpSender;
  @Autowired private JdbcTemplate jdbc;

  private UUID userId;

  @BeforeEach
  void aStudent() {
    otpSender.sent.clear();
    otpSender.failing = false;
    College college = colleges.save(TestColleges.pilot());
    userId =
        subscribers
            .registerFromOidc(
                new OidcClaims(
                    "sub-" + UUID.randomUUID(),
                    TestColleges.studentEmail(college),
                    true,
                    "Tara Target",
                    null))
            .id();
  }

  private ChannelView channel(ChannelType type) {
    return subscribers.get(userId).channels().stream()
        .filter(channel -> channel.type() == type)
        .findFirst()
        .orElseThrow();
  }

  private List<Map<String, Object>> storedCodes() {
    return jdbc.queryForList("select * from link_code where user_id = ?", userId);
  }

  private static String wrong(String code) {
    return code.equals("000000") ? "000001" : "000000";
  }

  // ---- Push ----------------------------------------------------------------------------------

  @Test
  void ntfyTopicIsThePrefixAnd128RandomBitsInBase32() {
    Set<String> topics = new HashSet<>();
    for (int i = 0; i < 200; i++) {
      String topic = verification.newNtfyTopic();
      // 128 bits in base32 take 26 characters; ntfy allows 64 of [-_A-Za-z0-9].
      assertThat(topic).matches("nb-[a-z2-7]{26}");
      topics.add(topic);
    }
    assertThat(topics).hasSize(200);
  }

  @Test
  void aPushChannelIsVerifiedAsSoonAsItExists() {
    NtfyChannel created = verification.createNtfyChannel(userId);

    assertThat(created.topic()).matches("nb-[a-z2-7]{26}");
    assertThat(created.toString()).doesNotContain(created.topic());
    ChannelView channel = channel(ChannelType.NTFY);
    assertThat(channel.id()).isEqualTo(created.channelId());
    assertThat(channel.verified()).isTrue();
    assertThat(directory.target(created.channelId()))
        .hasValueSatisfying(target -> {
          assertThat(target.type()).isEqualTo(ChannelType.NTFY);
          assertThat(target.userId()).isEqualTo(userId);
          assertThat(target.address().value()).isEqualTo(created.topic());
        });
  }

  @Test
  void creatingThePushChannelAgainReplacesTheTopic() {
    NtfyChannel first = verification.createNtfyChannel(userId);
    NtfyChannel second = verification.createNtfyChannel(userId);

    assertThat(second.channelId()).isEqualTo(first.channelId());
    assertThat(second.topic()).isNotEqualTo(first.topic());
    assertThat(directory.target(first.channelId()).orElseThrow().address().value())
        .isEqualTo(second.topic());
    assertThat(channel(ChannelType.NTFY).verified()).isTrue();
  }

  // ---- Email ---------------------------------------------------------------------------------

  @Test
  void startingSendsASixDigitCodeAndStoresOnlyItsHash() {
    EmailVerificationStarted started =
        verification.startEmailVerification(userId, " tara@personal.example ");

    assertThat(started.alreadyVerified()).isFalse();
    assertThat(started.expiresAt())
        .isCloseTo(Instant.now().plus(Duration.ofMinutes(10)), within(1, ChronoUnit.MINUTES));
    assertThat(otpSender.sent)
        .singleElement()
        .satisfies(sent -> {
          assertThat(sent.to()).isEqualTo("tara@personal.example");
          assertThat(sent.code()).matches("\\d{6}");
          assertThat(sent.validFor()).isEqualTo(Duration.ofMinutes(10));
        });
    assertThat(storedCodes())
        .singleElement()
        .satisfies(row -> {
          assertThat((String) row.get("code")).matches("[0-9a-f]{64}");
          assertThat((String) row.get("code")).doesNotContain(otpSender.lastCode());
          assertThat(row).containsEntry("channel_type", "EMAIL").containsEntry("attempts", 0);
        });
    assertThat(channel(ChannelType.EMAIL).verified()).isFalse();
    assertThat(directory.deliverable(userId)).isEmpty();
  }

  @Test
  void theRightCodeVerifiesTheChannel() {
    verification.startEmailVerification(userId, "tara@personal.example");

    assertThat(verification.confirmEmail(userId, " " + otpSender.lastCode() + " "))
        .isEqualTo(EmailVerificationResult.VERIFIED);

    assertThat(channel(ChannelType.EMAIL).verified()).isTrue();
    assertThat(storedCodes()).isEmpty();
    assertThat(directory.deliverable(userId))
        .extracting(ChannelView::type)
        .containsExactly(ChannelType.EMAIL);
    // The code is spent.
    assertThat(verification.confirmEmail(userId, otpSender.lastCode()))
        .isEqualTo(EmailVerificationResult.NO_PENDING_CODE);
  }

  @Test
  void aWrongCodeCountsAndTheRightOneStillWorksOnTheFifthTry() {
    verification.startEmailVerification(userId, "tara@personal.example");
    String code = otpSender.lastCode();

    for (int i = 0; i < 4; i++) {
      assertThat(verification.confirmEmail(userId, wrong(code)))
          .isEqualTo(EmailVerificationResult.WRONG_CODE);
    }
    assertThat(storedCodes())
        .singleElement()
        .satisfies(row -> assertThat(row).containsEntry("attempts", 4));
    assertThat(channel(ChannelType.EMAIL).verified()).isFalse();

    assertThat(verification.confirmEmail(userId, code)).isEqualTo(EmailVerificationResult.VERIFIED);
  }

  @Test
  void afterFiveWrongCodesEvenTheRightOneIsRefused() {
    verification.startEmailVerification(userId, "tara@personal.example");
    String code = otpSender.lastCode();

    for (int i = 0; i < 4; i++) {
      verification.confirmEmail(userId, wrong(code));
    }
    assertThat(verification.confirmEmail(userId, wrong(code)))
        .isEqualTo(EmailVerificationResult.TOO_MANY_ATTEMPTS);

    assertThat(verification.confirmEmail(userId, code))
        .isEqualTo(EmailVerificationResult.TOO_MANY_ATTEMPTS);
    assertThat(channel(ChannelType.EMAIL).verified()).isFalse();
    assertThat(storedCodes())
        .singleElement()
        .satisfies(row -> assertThat(row).containsEntry("attempts", 5));
  }

  @Test
  void aCodeExpiresAfterItsValidity() {
    verification.startEmailVerification(userId, "tara@personal.example");
    jdbc.update(
        "update link_code set expires_at = now() - interval '1 second' where user_id = ?", userId);

    assertThat(verification.confirmEmail(userId, otpSender.lastCode()))
        .isEqualTo(EmailVerificationResult.EXPIRED);

    assertThat(channel(ChannelType.EMAIL).verified()).isFalse();
    assertThat(storedCodes()).isEmpty();
  }

  @Test
  void confirmingWithoutAPendingCodeSaysSo() {
    assertThat(verification.confirmEmail(userId, "123456"))
        .isEqualTo(EmailVerificationResult.NO_PENDING_CODE);
  }

  @Test
  void aNewCodeReplacesTheOldOneAndResetsTheAttempts() {
    verification.startEmailVerification(userId, "tara@personal.example");
    String first = otpSender.lastCode();
    for (int i = 0; i < 5; i++) {
      verification.confirmEmail(userId, wrong(first));
    }

    verification.startEmailVerification(userId, "tara@personal.example");
    String second = otpSender.lastCode();

    assertThat(storedCodes()).hasSize(1);
    if (!first.equals(second)) {
      assertThat(verification.confirmEmail(userId, first))
          .isEqualTo(EmailVerificationResult.WRONG_CODE);
    }
    assertThat(verification.confirmEmail(userId, second))
        .isEqualTo(EmailVerificationResult.VERIFIED);
  }

  @Test
  void aCodeNeverVerifiesAnAddressItWasNotSentTo() {
    verification.startEmailVerification(userId, "tara@personal.example");
    String code = otpSender.lastCode();
    // The address changes behind the code's back.
    subscribers.addChannel(
        userId, ChannelType.EMAIL, new ChannelAddress("someone.else@victim.example"));

    assertThat(verification.confirmEmail(userId, code))
        .isEqualTo(EmailVerificationResult.WRONG_CODE);
    assertThat(channel(ChannelType.EMAIL).verified()).isFalse();
  }

  @Test
  void twoUsersCanHoldCodesAtTheSameTime() {
    College college = colleges.save(TestColleges.pilot());
    UUID other =
        subscribers
            .registerFromOidc(
                new OidcClaims(
                    "sub-" + UUID.randomUUID(),
                    TestColleges.studentEmail(college),
                    true,
                    "Other",
                    null))
            .id();
    verification.startEmailVerification(userId, "shared@personal.example");
    String mine = otpSender.lastCode();
    verification.startEmailVerification(other, "shared@personal.example");
    String theirs = otpSender.lastCode();

    // Even an identical code hashes differently per user, so the primary key never collides.
    assertThat(ChannelVerificationService.hash(userId, "shared@personal.example", "123456"))
        .isNotEqualTo(ChannelVerificationService.hash(other, "shared@personal.example", "123456"));
    assertThat(verification.confirmEmail(other, theirs))
        .isEqualTo(EmailVerificationResult.VERIFIED);
    assertThat(verification.confirmEmail(userId, mine)).isEqualTo(EmailVerificationResult.VERIFIED);
  }

  @Test
  void anAddressThatIsAlreadyVerifiedGetsNoSecondCode() {
    verification.startEmailVerification(userId, "tara@personal.example");
    verification.confirmEmail(userId, otpSender.lastCode());
    otpSender.sent.clear();

    EmailVerificationStarted again =
        verification.startEmailVerification(userId, "tara@personal.example");

    assertThat(again.alreadyVerified()).isTrue();
    assertThat(again.expiresAt()).isNull();
    assertThat(otpSender.sent).isEmpty();
    assertThat(storedCodes()).isEmpty();
  }

  @Test
  void changingTheAddressNeedsANewVerification() {
    verification.startEmailVerification(userId, "tara@personal.example");
    verification.confirmEmail(userId, otpSender.lastCode());

    EmailVerificationStarted changed =
        verification.startEmailVerification(userId, "tara@new.example");

    assertThat(changed.alreadyVerified()).isFalse();
    assertThat(channel(ChannelType.EMAIL).verified()).isFalse();
    assertThat(verification.confirmEmail(userId, otpSender.lastCode()))
        .isEqualTo(EmailVerificationResult.VERIFIED);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "  ", "tara", "tara@", "@personal.example", "tara@personal", "a b@c.d"})
  void rejectsWhatIsNotAnEmailAddress(String address) {
    assertThatThrownBy(() -> verification.startEmailVerification(userId, address))
        .isInstanceOf(InvalidChannelAddressException.class);
    assertThat(otpSender.sent).isEmpty();
    assertThat(subscribers.get(userId).channels()).isEmpty();
  }

  @Test
  void ifTheEmailCannotBeSentNothingIsSaved() {
    otpSender.failing = true;

    assertThatThrownBy(() -> verification.startEmailVerification(userId, "tara@personal.example"))
        .isInstanceOf(VerificationNotSentException.class);

    assertThat(storedCodes()).isEmpty();
    assertThat(subscribers.get(userId).channels()).isEmpty();
  }

  @Test
  void anUnknownUserIsRejected() {
    assertThatThrownBy(() -> verification.createNtfyChannel(UUID.randomUUID()))
        .isInstanceOf(SubscriberNotFoundException.class);
    assertThatThrownBy(
            () -> verification.startEmailVerification(UUID.randomUUID(), "tara@personal.example"))
        .isInstanceOf(SubscriberNotFoundException.class);
  }
}
