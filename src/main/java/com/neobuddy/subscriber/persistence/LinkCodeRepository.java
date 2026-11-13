package com.neobuddy.subscriber.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LinkCodeRepository extends JpaRepository<LinkCode, String> {

  List<LinkCode> findByUserId(UUID userId);
}
