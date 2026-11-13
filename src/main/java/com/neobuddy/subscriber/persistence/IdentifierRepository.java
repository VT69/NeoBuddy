package com.neobuddy.subscriber.persistence;

import com.neobuddy.subscriber.IdentifierType;
import com.neobuddy.subscriber.UserStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every finder that looks up a value is scoped by college. */
public interface IdentifierRepository extends JpaRepository<Identifier, UUID> {

  List<Identifier> findByUserIdOrderByCreatedAt(UUID userId);

  Optional<Identifier> findByIdAndUserId(UUID id, UUID userId);

  Optional<Identifier> findByUserIdAndType(UUID userId, IdentifierType type);

  Optional<Identifier> findByCollegeIdAndTypeAndNormalizedValue(
      UUID collegeId, IdentifierType type, String normalizedValue);

  /** The identifiers of every user with this status, across all colleges, for the match index. */
  @Query(
      "select i from Identifier i where exists"
          + " (select 1 from AppUser u where u.id = i.userId and u.status = :status)")
  List<Identifier> findAllOfUsersWithStatus(@Param("status") UserStatus status);
}
