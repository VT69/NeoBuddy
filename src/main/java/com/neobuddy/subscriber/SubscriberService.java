package com.neobuddy.subscriber;

import com.neobuddy.processing.IdentifiersChanged;
import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeRepository;
import com.neobuddy.subscriber.RegistrationRejectedException.Reason;
import com.neobuddy.subscriber.SubscriberView.ChannelView;
import com.neobuddy.subscriber.SubscriberView.IdentifierView;
import com.neobuddy.subscriber.persistence.AppUser;
import com.neobuddy.subscriber.persistence.AppUserRepository;
import com.neobuddy.subscriber.persistence.Channel;
import com.neobuddy.subscriber.persistence.ChannelRepository;
import com.neobuddy.subscriber.persistence.Identifier;
import com.neobuddy.subscriber.persistence.IdentifierRepository;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The subscriber module's public API: accounts, the identifiers a student is listed by, and the
 * channels alerts go to. Everything is scoped to the user's college.
 *
 * <p>Whenever the set of identifiers that should be matched changes (added, removed, paused,
 * resumed, account deleted) it publishes {@link IdentifiersChanged}, and the match index rebuilds
 * once the change has committed.
 */
@Service
public class SubscriberService {

  private final AppUserRepository users;
  private final IdentifierRepository identifiers;
  private final ChannelRepository channels;
  private final CollegeRepository colleges;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public SubscriberService(
      AppUserRepository users,
      IdentifierRepository identifiers,
      ChannelRepository channels,
      CollegeRepository colleges,
      ApplicationEventPublisher events,
      Clock clock) {
    this.users = users;
    this.identifiers = identifiers;
    this.channels = channels;
    this.colleges = colleges;
    this.events = events;
    this.clock = clock;
  }

  /**
   * Finds or creates the account for a Google sign-in. The college is the one whose sign-in domains
   * include the email's domain; nobody else can register.
   *
   * <p>Creating the account records consent as of now, so the caller must have shown the consent
   * notice before the user reaches this point.
   *
   * @throws RegistrationRejectedException if the email is unverified, belongs to no onboarded or to
   *     a paused college, or is already tied to a different Google account
   */
  @Transactional
  public SubscriberView registerFromOidc(OidcClaims claims) {
    if (!claims.emailVerified() || isBlank(claims.email()) || isBlank(claims.subject())) {
      throw new RegistrationRejectedException(Reason.EMAIL_NOT_VERIFIED);
    }
    String email = claims.email().strip().toLowerCase(Locale.ROOT);
    College college = collegeFor(email, claims.hostedDomain());

    Optional<AppUser> bySubject = users.findByGoogleSub(claims.subject());
    if (bySubject.isPresent()) {
      return view(bySubject.get());
    }
    Optional<AppUser> byEmail = users.findByEmail(email);
    if (byEmail.isPresent()) {
      AppUser existing = byEmail.get();
      if (!existing.awaitsFirstSignIn()) {
        throw new RegistrationRejectedException(Reason.EMAIL_ALREADY_REGISTERED);
      }
      // An account created ahead of its owner's first sign-in (the bootstrap admin).
      existing.linkGoogleAccount(claims.subject());
      if (existing.getDisplayName() == null) {
        existing.rename(claims.name());
      }
      return view(existing);
    }
    AppUser created =
        users.save(
            new AppUser(
                college.getId(),
                claims.subject(),
                email,
                claims.name(),
                UserRole.USER,
                clock.instant()));
    return view(created);
  }

  @Transactional(readOnly = true)
  public SubscriberView get(UUID userId) {
    return view(requireUser(userId));
  }

  @Transactional(readOnly = true)
  public Optional<SubscriberView> findByEmail(String email) {
    if (isBlank(email)) {
      return Optional.empty();
    }
    return users.findByEmail(email.strip().toLowerCase(Locale.ROOT)).map(this::view);
  }

  /**
   * Adds an identifier after normalising and validating it for the user's college. A user has at
   * most one identifier of each type. Adding the value they already have is a no-op.
   *
   * @throws InvalidIdentifierException if it breaks the college's rules or the user already has a
   *     different identifier of this type
   * @throws IdentifierAlreadyClaimedException if another account in the college holds it
   */
  @Transactional
  public IdentifierView addIdentifier(UUID userId, IdentifierType type, String rawValue) {
    AppUser user = requireUser(userId);
    College college = requireCollege(user);
    String value = IdentifierNormalizer.normalizeAndValidate(college, type, rawValue);

    Optional<Identifier> ownOfType = identifiers.findByUserIdAndType(userId, type);
    if (ownOfType.isPresent()) {
      if (ownOfType.get().getNormalizedValue().equals(value)) {
        return view(ownOfType.get());
      }
      throw new InvalidIdentifierException(
          InvalidIdentifierException.Reason.ALREADY_HAS_ONE_OF_THIS_TYPE);
    }
    if (identifiers
        .findByCollegeIdAndTypeAndNormalizedValue(college.getId(), type, value)
        .isPresent()) {
      throw new IdentifierAlreadyClaimedException();
    }
    Identifier saved;
    try {
      saved =
          identifiers.saveAndFlush(
              new Identifier(college.getId(), userId, type, value, clock.instant()));
    } catch (DataIntegrityViolationException e) {
      // Lost a race with another account claiming the same value; the unique key decided.
      throw new IdentifierAlreadyClaimedException();
    }
    events.publishEvent(new IdentifiersChanged(college.getId()));
    return view(saved);
  }

  @Transactional
  public void removeIdentifier(UUID userId, UUID identifierId) {
    Identifier identifier =
        identifiers
            .findByIdAndUserId(identifierId, userId)
            .orElseThrow(() -> new SubscriberNotFoundException("Identifier"));
    identifiers.delete(identifier);
    identifiers.flush();
    events.publishEvent(new IdentifiersChanged(identifier.getCollegeId()));
  }

  /**
   * Sets the user's channel of this type. A new channel, or a changed address, is unverified and
   * gets no alerts until {@link #markVerified}. Setting the same address again changes nothing.
   */
  @Transactional
  public ChannelView addChannel(UUID userId, ChannelType type, ChannelAddress address) {
    requireUser(userId);
    Optional<Channel> existing = channels.findByUserIdAndType(userId, type);
    if (existing.isPresent()) {
      existing.get().changeAddress(address.value());
      return view(existing.get());
    }
    return view(channels.save(new Channel(userId, type, address.value())));
  }

  /** Records that the user proved the channel is theirs. Proving it is the caller's job. */
  @Transactional
  public ChannelView markVerified(UUID userId, UUID channelId) {
    Channel channel =
        channels
            .findByIdAndUserId(channelId, userId)
            .orElseThrow(() -> new SubscriberNotFoundException("Channel"));
    channel.markVerified(clock.instant());
    return view(channel);
  }

  /** Stops matching and alerts for the user without deleting anything. */
  @Transactional
  public void pause(UUID userId) {
    changeStatus(userId, UserStatus.PAUSED);
  }

  @Transactional
  public void resume(UUID userId) {
    changeStatus(userId, UserStatus.ACTIVE);
  }

  /**
   * Deletes the account and, through the database's cascades, everything that hangs off it:
   * identifiers, channels, link codes, matches and deliveries.
   */
  @Transactional
  public void deleteAccount(UUID userId) {
    AppUser user = requireUser(userId);
    users.delete(user);
    users.flush();
    events.publishEvent(new IdentifiersChanged(user.getCollegeId()));
  }

  private void changeStatus(UUID userId, UserStatus status) {
    AppUser user = requireUser(userId);
    if (user.getStatus() != status) {
      user.changeStatus(status);
      events.publishEvent(new IdentifiersChanged(user.getCollegeId()));
    }
  }

  private College collegeFor(String email, String hostedDomain) {
    String domain = IdentifierNormalizer.domainOf(email);
    List<College> candidates =
        domain.isEmpty() ? List.of() : colleges.findByAllowedEmailDomain(domain);
    if (candidates.isEmpty()) {
      throw new RegistrationRejectedException(Reason.COLLEGE_NOT_SUPPORTED);
    }
    College college =
        candidates.stream()
            .filter(College::acceptsSubscribers)
            .findFirst()
            .orElseThrow(() -> new RegistrationRejectedException(Reason.COLLEGE_PAUSED));
    if (!isBlank(hostedDomain)
        && college.getAllowedEmailDomains().stream()
            .noneMatch(allowed -> allowed.strip().equalsIgnoreCase(hostedDomain.strip()))) {
      throw new RegistrationRejectedException(Reason.HOSTED_DOMAIN_MISMATCH);
    }
    return college;
  }

  private AppUser requireUser(UUID userId) {
    return users.findById(userId).orElseThrow(() -> new SubscriberNotFoundException("User"));
  }

  private College requireCollege(AppUser user) {
    return colleges
        .findById(user.getCollegeId())
        .orElseThrow(() -> new IllegalStateException("User without a college"));
  }

  private SubscriberView view(AppUser user) {
    return new SubscriberView(
        user.getId(),
        user.getCollegeId(),
        user.getEmail(),
        user.getDisplayName(),
        user.getRole(),
        user.getStatus(),
        user.getConsentedAt(),
        identifiers.findByUserIdOrderByCreatedAt(user.getId()).stream()
            .map(SubscriberService::view)
            .toList(),
        channels.findByUserIdOrderByType(user.getId()).stream()
            .map(SubscriberService::view)
            .toList());
  }

  private static IdentifierView view(Identifier identifier) {
    return new IdentifierView(
        identifier.getId(), identifier.getType(), identifier.getNormalizedValue());
  }

  private static ChannelView view(Channel channel) {
    return new ChannelView(
        channel.getId(), channel.getType(), channel.isVerified(), channel.isEnabled());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
