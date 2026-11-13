package com.neobuddy.subscriber.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neobuddy.processing.IdentifierSource;
import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeRepository;
import com.neobuddy.shared.college.CollegeStatus;
import com.neobuddy.shared.crypto.FieldEncryptor;
import com.neobuddy.subscriber.ChannelType;
import com.neobuddy.subscriber.IdentifierAlreadyClaimedException;
import com.neobuddy.subscriber.IdentifierType;
import com.neobuddy.subscriber.OidcClaims;
import com.neobuddy.subscriber.SubscriberService;
import com.neobuddy.subscriber.SubscriberView;
import com.neobuddy.subscriber.SubscriberView.ChannelView;
import com.neobuddy.subscriber.SubscriberView.IdentifierView;
import com.neobuddy.subscriber.TestColleges;
import com.neobuddy.subscriber.UserRole;
import com.neobuddy.subscriber.UserStatus;
import com.neobuddy.subscriber.bootstrap.BootstrapProperties.AdminSeed;
import com.neobuddy.subscriber.bootstrap.BootstrapProperties.CollegeSeed;
import com.neobuddy.testsupport.DatabaseTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DatabaseTest
class BootstrapTests {

  @Autowired private Bootstrap bootstrap;
  @Autowired private SubscriberService subscribers;
  @Autowired private CollegeRepository colleges;
  @Autowired private IdentifierSource identifierSource;
  @Autowired private FieldEncryptor encryptor;
  @Autowired private JdbcTemplate jdbc;

  private final String slug = "boot-" + UUID.randomUUID().toString().substring(0, 8);
  private final String domain = slug + ".example";
  private final String adminEmail = "admin@" + domain;
  private final String neoId = TestColleges.neoId();

  @Test
  void seedsTheCollegeAndItsAdmin() {
    Bootstrap.Report report = bootstrap.apply(fullConfiguration());

    assertThat(report).isEqualTo(new Bootstrap.Report(true, true, true));

    College college = colleges.findBySlug(slug).orElseThrow();
    assertThat(college.getName()).isEqualTo("Bootstrap College");
    assertThat(college.getAllowedEmailDomains()).containsExactly(domain, "alumni." + domain);
    assertThat(college.getSenderAllowlist())
        .containsExactly("placements@" + domain, "@cell." + domain);
    assertThat(college.getNeoIdPattern()).isEqualTo(College.DEFAULT_NEO_ID_PATTERN);
    assertThat(college.getStatus()).isEqualTo(CollegeStatus.PILOT);

    SubscriberView admin = subscribers.findByEmail(adminEmail).orElseThrow();
    assertThat(admin.collegeId()).isEqualTo(college.getId());
    assertThat(admin.role()).isEqualTo(UserRole.ADMIN);
    assertThat(admin.status()).isEqualTo(UserStatus.ACTIVE);
    assertThat(admin.consentedAt()).isNotNull();
    assertThat(admin.identifiers())
        .extracting(IdentifierView::type, IdentifierView::value)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(IdentifierType.NEO_ID, neoId),
            org.assertj.core.groups.Tuple.tuple(IdentifierType.REG_NO, "21BCE1234"));
    assertThat(admin.channels())
        .extracting(ChannelView::type, ChannelView::verified, ChannelView::enabled)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(ChannelType.NTFY, true, true),
            org.assertj.core.groups.Tuple.tuple(ChannelType.EMAIL, true, true));
    assertThat(channelAddress(admin.id(), "NTFY")).isEqualTo("topic-" + slug);
    assertThat(channelAddress(admin.id(), "EMAIL")).isEqualTo("personal@mail.example");

    // The admin is matchable straight away.
    assertThat(identifierSource.loadAll())
        .anyMatch(i -> i.userId().equals(admin.id()) && i.normalizedValue().equals(neoId));
  }

  @Test
  void runningItTwiceChangesNothing() {
    bootstrap.apply(fullConfiguration());
    List<Map<String, Object>> before = snapshot();

    Bootstrap.Report second = bootstrap.apply(fullConfiguration());
    Bootstrap.Report third = bootstrap.apply(fullConfiguration());

    assertThat(second).isEqualTo(new Bootstrap.Report(true, false, false)).isEqualTo(third);
    // Same rows, same ids, same timestamps, and the very same encrypted bytes.
    assertThat(snapshot()).isEqualTo(before);
    assertThat(before).hasSize(1 + 1 + 2 + 2);
  }

  @Test
  void changedConfigurationUpdatesInPlace() {
    bootstrap.apply(fullConfiguration());
    SubscriberView before = subscribers.findByEmail(adminEmail).orElseThrow();
    String newNeoId = TestColleges.neoId();

    bootstrap.apply(
        new BootstrapProperties(
            new CollegeSeed(
                slug, "Renamed College", List.of(domain), List.of("new@" + domain), null),
            new AdminSeed(adminEmail, newNeoId, null, "topic-changed", null)));

    College college = colleges.findBySlug(slug).orElseThrow();
    assertThat(college.getName()).isEqualTo("Renamed College");
    assertThat(college.getAllowedEmailDomains()).containsExactly(domain);
    assertThat(college.getSenderAllowlist()).containsExactly("new@" + domain);
    SubscriberView after = subscribers.findByEmail(adminEmail).orElseThrow();
    assertThat(after.id()).isEqualTo(before.id());
    assertThat(after.consentedAt()).isEqualTo(before.consentedAt());
    assertThat(after.identifiers())
        .filteredOn(i -> i.type() == IdentifierType.NEO_ID)
        .extracting(IdentifierView::value)
        .containsExactly(newNeoId);
    // Optional values that are no longer configured are left alone, not deleted.
    assertThat(after.identifiers())
        .extracting(IdentifierView::type)
        .contains(IdentifierType.REG_NO);
    assertThat(channelAddress(after.id(), "NTFY")).isEqualTo("topic-changed");
    assertThat(after.channels()).allMatch(ChannelView::verified);
  }

  @Test
  void theAdminsFirstGoogleSignInTakesOverTheSeededAccount() {
    bootstrap.apply(fullConfiguration());
    SubscriberView seeded = subscribers.findByEmail(adminEmail).orElseThrow();

    SubscriberView signedIn =
        subscribers.registerFromOidc(
            new OidcClaims(
                "google-sub-" + UUID.randomUUID(), adminEmail, true, "The Admin", domain));

    assertThat(signedIn.id()).isEqualTo(seeded.id());
    assertThat(signedIn.role()).isEqualTo(UserRole.ADMIN);
    assertThat(signedIn.displayName()).isEqualTo("The Admin");
    assertThat(signedIn.identifiers()).hasSize(2);
    // And bootstrapping again afterwards still changes nothing.
    assertThat(bootstrap.apply(fullConfiguration()))
        .isEqualTo(new Bootstrap.Report(true, false, false));
  }

  @Test
  void anAdminIsOptional() {
    Bootstrap.Report report =
        bootstrap.apply(
            new BootstrapProperties(
                new CollegeSeed(slug, null, List.of(domain), List.of(), null), null));

    assertThat(report).isEqualTo(new Bootstrap.Report(true, true, false));
    assertThat(colleges.findBySlug(slug))
        .hasValueSatisfying(c -> assertThat(c.getName()).isEqualTo(slug));
    assertThat(subscribers.findByEmail(adminEmail)).isEmpty();
  }

  @Test
  void withoutASlugNothingIsSeeded() {
    long collegesBefore = colleges.count();

    assertThat(bootstrap.apply(new BootstrapProperties(null, null)))
        .isEqualTo(new Bootstrap.Report(false, false, false));
    assertThat(
            bootstrap.apply(
                new BootstrapProperties(
                    new CollegeSeed(" ", "Name", List.of(domain), List.of(), null),
                    new AdminSeed(adminEmail, neoId, null, null, null))))
        .isEqualTo(new Bootstrap.Report(false, false, false));

    assertThat(colleges.count()).isEqualTo(collegesBefore);
    assertThat(subscribers.findByEmail(adminEmail)).isEmpty();
  }

  @Test
  void aConfigurationThatCannotBeAppliedFailsAndLeavesNothingBehind() {
    // Admin email on a domain the college does not own.
    assertThatThrownBy(() ->
            bootstrap.apply(
                new BootstrapProperties(
                    new CollegeSeed(slug, null, List.of(domain), List.of(), null),
                    new AdminSeed("admin@elsewhere.example", neoId, null, null, null))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("neobuddy.bootstrap.admin.email")
        .hasMessageNotContaining("elsewhere");
    assertThat(colleges.findBySlug(slug)).as("the whole run rolled back").isEmpty();

    assertThatThrownBy(() ->
            bootstrap.apply(
                new BootstrapProperties(
                    new CollegeSeed(slug, null, List.of(), List.of(), null), null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("allowed-email-domains");
    assertThatThrownBy(() ->
            bootstrap.apply(
                new BootstrapProperties(
                    new CollegeSeed(slug, null, List.of(domain), List.of(), "([unclosed"), null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("neo-id-pattern");
    assertThatThrownBy(() ->
            bootstrap.apply(
                new BootstrapProperties(
                    new CollegeSeed(slug, null, List.of(domain), List.of(), null),
                    new AdminSeed(adminEmail, " ", null, null, null))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("neo-id is required");
    assertThat(colleges.findBySlug(slug)).isEmpty();
  }

  @Test
  void aNeoIdAlreadyHeldBySomeoneElseStopsTheBootstrap() {
    bootstrap.apply(
        new BootstrapProperties(
            new CollegeSeed(slug, null, List.of(domain), List.of(), null), null));
    SubscriberView student =
        subscribers.registerFromOidc(
            new OidcClaims("sub-" + UUID.randomUUID(), "student@" + domain, true, "S", null));
    subscribers.addIdentifier(student.id(), IdentifierType.NEO_ID, neoId);

    assertThatThrownBy(() -> bootstrap.apply(fullConfiguration()))
        .isInstanceOf(IdentifierAlreadyClaimedException.class);

    assertThat(subscribers.findByEmail(adminEmail)).as("the admin was rolled back").isEmpty();
    assertThat(subscribers.get(student.id()).identifiers()).hasSize(1);
  }

  @Test
  void propertiesNeverPrintTheAdminsDetails() {
    assertThat(fullConfiguration().toString())
        .doesNotContain(adminEmail)
        .doesNotContain(neoId)
        .doesNotContain("topic-")
        .doesNotContain("personal@");
  }

  private BootstrapProperties fullConfiguration() {
    return new BootstrapProperties(
        new CollegeSeed(
            slug,
            " Bootstrap College ",
            List.of(domain.toUpperCase(), " alumni." + domain, "", domain),
            List.of("Placements@" + domain, "@cell." + domain),
            " "),
        new AdminSeed(
            " Admin@" + domain.toUpperCase() + " ",
            neoId.substring(0, 4).toLowerCase() + " " + neoId.substring(4),
            "21-bce-1234",
            "topic-" + slug,
            "personal@mail.example"));
  }

  private String channelAddress(UUID userId, String type) {
    byte[] stored =
        jdbc.queryForObject(
            "select address_enc from channel where user_id = ? and type = ?",
            byte[].class,
            userId,
            type);
    return encryptor.decrypt(stored);
  }

  /** Every row the bootstrap owns, column for column, including the encrypted bytes. */
  private List<Map<String, Object>> snapshot() {
    UUID collegeId = colleges.findBySlug(slug).orElseThrow().getId();
    String users = "select id from app_user where college_id = ?";
    List<Map<String, Object>> rows = new java.util.ArrayList<>();
    rows.addAll(
        jdbc.queryForList(
            "select id, slug, name, allowed_email_domains::text, sender_allowlist::text,"
                + " neo_id_pattern, status, created_at from college where id = ?",
            collegeId));
    rows.addAll(
        jdbc.queryForList(
            "select id, google_sub, email::text, display_name, role, status, consented_at,"
                + " created_at from app_user where college_id = ? order by id",
            collegeId));
    rows.addAll(
        jdbc.queryForList(
            "select id, user_id, type, normalized_value, created_at from identifier"
                + " where college_id = ? order by id",
            collegeId));
    rows.addAll(
        jdbc.queryForList(
            "select id, user_id, type, encode(address_enc, 'hex') as address_enc, verified_at,"
                + " enabled from channel where user_id in ("
                + users
                + ") order by id",
            collegeId));
    return rows;
  }
}
