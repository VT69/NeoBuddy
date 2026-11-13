package com.neobuddy.shared.college;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollegeRepository extends JpaRepository<College, UUID> {

  Optional<College> findBySlug(String slug);
}
