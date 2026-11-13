package com.neobuddy.shared.college;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CollegeRepository extends JpaRepository<College, UUID> {

  Optional<College> findBySlug(String slug);

  /** Colleges whose sign-in domains include this one. Domains are stored lower-case. */
  @Query(
      value = "select * from college c where lower(:domain) = any (c.allowed_email_domains)",
      nativeQuery = true)
  List<College> findByAllowedEmailDomain(@Param("domain") String domain);
}
