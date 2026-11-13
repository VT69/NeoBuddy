package com.neobuddy.subscriber.persistence;

import com.neobuddy.processing.IdentifierSource;
import com.neobuddy.subscriber.UserStatus;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The subscriber side of processing's {@link IdentifierSource} port: the identifiers of every
 * active user. Paused users are left out, which is what stops their alerts.
 */
@Component
class SubscriberIdentifierSource implements IdentifierSource {

  private final IdentifierRepository identifiers;

  SubscriberIdentifierSource(IdentifierRepository identifiers) {
    this.identifiers = identifiers;
  }

  @Override
  @Transactional(readOnly = true)
  public List<IndexedIdentifier> loadAll() {
    return identifiers.findAllOfUsersWithStatus(UserStatus.ACTIVE).stream()
        .map(identifier ->
            new IndexedIdentifier(
                identifier.getCollegeId(),
                identifier.getUserId(),
                identifier.getId(),
                identifier.getNormalizedValue()))
        .toList();
  }
}
