package com.neobuddy.subscriber.bootstrap;

import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeRepository;
import com.neobuddy.shared.college.CollegeStatus;
import com.neobuddy.subscriber.ChannelAddress;
import com.neobuddy.subscriber.ChannelType;
import com.neobuddy.subscriber.IdentifierNormalizer;
import com.neobuddy.subscriber.IdentifierType;
import com.neobuddy.subscriber.SubscriberService;
import com.neobuddy.subscriber.SubscriberView;
import com.neobuddy.subscriber.SubscriberView.ChannelView;
import com.neobuddy.subscriber.SubscriberView.IdentifierView;
import com.neobuddy.subscriber.UserRole;
import com.neobuddy.subscriber.bootstrap.BootstrapProperties.AdminSeed;
import com.neobuddy.subscriber.bootstrap.BootstrapProperties.CollegeSeed;
import com.neobuddy.subscriber.persistence.AppUser;
import com.neobuddy.subscriber.persistence.AppUserRepository;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes the database match the bootstrap configuration: the college, its admin, the admin's
 * identifiers and pre-verified channels. An upsert: running it again with the same configuration
 * changes nothing, and running it with changed values updates what changed.
 *
 * <p>A configuration that cannot be applied (admin email off the college's domains, a NeoID held by
 * someone else) throws, which stops the application from starting half configured.
 */
@Service
public class Bootstrap {

  /** What one run did, for the startup log. Holds no personal data. */
  public record Report(boolean configured, boolean collegeCreated, boolean adminCreated) {

    static final Report NOT_CONFIGURED = new Report(false, false, false);
  }

  private final CollegeRepository colleges;
  private final AppUserRepository users;
  private final SubscriberService subscribers;
  private final Clock clock;

  public Bootstrap(
      CollegeRepository colleges,
      AppUserRepository users,
      SubscriberService subscribers,
      Clock clock) {
    this.colleges = colleges;
    this.users = users;
    this.subscribers = subscribers;
    this.clock = clock;
  }

  @Transactional
  public Report apply(BootstrapProperties properties) {
    CollegeSeed seed = properties.college();
    if (isBlank(seed.slug())) {
      return Report.NOT_CONFIGURED;
    }
    Optional<College> existing = colleges.findBySlug(seed.slug().strip());
    College college = existing.map(c -> update(c, seed)).orElseGet(() -> create(seed));

    AdminSeed admin = properties.admin();
    boolean adminCreated = !isBlank(admin.email()) && seedAdmin(college, admin);
    return new Report(true, existing.isEmpty(), adminCreated);
  }

  private College create(CollegeSeed seed) {
    return colleges.save(
        new College(
            UUID.randomUUID(),
            seed.slug().strip(),
            nameOf(seed),
            domainsOf(seed),
            sendersOf(seed),
            patternOf(seed),
            CollegeStatus.PILOT,
            clock.instant()));
  }

  private College update(College college, CollegeSeed seed) {
    boolean same =
        college.getName().equals(nameOf(seed))
            && college.getAllowedEmailDomains().equals(domainsOf(seed))
            && college.getSenderAllowlist().equals(sendersOf(seed))
            && college.getNeoIdPattern().equals(patternOf(seed));
    if (!same) {
      college.updateSettings(nameOf(seed), domainsOf(seed), sendersOf(seed), patternOf(seed));
    }
    return college;
  }

  /**
   * @return true if the admin account was created by this run
   */
  private boolean seedAdmin(College college, AdminSeed seed) {
    String email = seed.email().strip().toLowerCase(Locale.ROOT);
    if (!IdentifierNormalizer.isCollegeEmail(college, email)) {
      throw new IllegalStateException(
          "neobuddy.bootstrap.admin.email is not on one of the college's allowed email domains");
    }
    if (isBlank(seed.neoId())) {
      throw new IllegalStateException("neobuddy.bootstrap.admin.neo-id is required");
    }
    Optional<AppUser> existing = users.findByEmail(email);
    AppUser user =
        existing.orElseGet(() ->
            users.save(
                new AppUser(
                    college.getId(),
                    AppUser.pendingSubFor(email),
                    email,
                    null,
                    UserRole.ADMIN,
                    clock.instant())));
    if (!user.getCollegeId().equals(college.getId())) {
      throw new IllegalStateException(
          "neobuddy.bootstrap.admin.email already belongs to an account of another college");
    }
    if (user.getRole() != UserRole.ADMIN) {
      user.changeRole(UserRole.ADMIN);
    }
    users.flush();

    setIdentifier(college, user.getId(), IdentifierType.NEO_ID, seed.neoId());
    if (!isBlank(seed.regNo())) {
      setIdentifier(college, user.getId(), IdentifierType.REG_NO, seed.regNo());
    }
    if (!isBlank(seed.ntfyTopic())) {
      setVerifiedChannel(user.getId(), ChannelType.NTFY, seed.ntfyTopic());
    }
    if (!isBlank(seed.personalEmail())) {
      setVerifiedChannel(user.getId(), ChannelType.EMAIL, seed.personalEmail());
    }
    return existing.isEmpty();
  }

  /** Adds the identifier, replacing a different one of the same type the admin already has. */
  private void setIdentifier(College college, UUID userId, IdentifierType type, String raw) {
    String wanted = IdentifierNormalizer.normalizeAndValidate(college, type, raw);
    SubscriberView current = subscribers.get(userId);
    for (IdentifierView identifier : current.identifiers()) {
      if (identifier.type() == type) {
        if (identifier.value().equals(wanted)) {
          return;
        }
        subscribers.removeIdentifier(userId, identifier.id());
      }
    }
    subscribers.addIdentifier(userId, type, wanted);
  }

  /** The admin vouches for these addresses in the configuration, so they start verified. */
  private void setVerifiedChannel(UUID userId, ChannelType type, String address) {
    ChannelView channel = subscribers.addChannel(userId, type, new ChannelAddress(address));
    if (!channel.verified()) {
      subscribers.markVerified(userId, channel.id());
    }
  }

  private static String nameOf(CollegeSeed seed) {
    return isBlank(seed.name()) ? seed.slug().strip() : seed.name().strip();
  }

  private static List<String> domainsOf(CollegeSeed seed) {
    List<String> domains = normalized(seed.allowedEmailDomains());
    if (domains.isEmpty()) {
      throw new IllegalStateException(
          "neobuddy.bootstrap.college.allowed-email-domains needs at least one domain");
    }
    return domains;
  }

  private static List<String> sendersOf(CollegeSeed seed) {
    return normalized(seed.senderAllowlist());
  }

  private static String patternOf(CollegeSeed seed) {
    String pattern =
        isBlank(seed.neoIdPattern()) ? College.DEFAULT_NEO_ID_PATTERN : seed.neoIdPattern().strip();
    try {
      Pattern.compile(pattern);
    } catch (PatternSyntaxException e) {
      throw new IllegalStateException(
          "neobuddy.bootstrap.college.neo-id-pattern is not a valid regular expression");
    }
    return pattern;
  }

  private static List<String> normalized(List<String> values) {
    return values.stream()
        .filter(value -> !isBlank(value))
        .map(value -> value.strip().toLowerCase(Locale.ROOT))
        .distinct()
        .toList();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
