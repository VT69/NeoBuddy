package com.neobuddy.subscriber.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

  Optional<AppUser> findByGoogleSub(String googleSub);

  /**
   * @param email must already be lower-case; emails are stored that way
   */
  Optional<AppUser> findByEmail(String email);
}
