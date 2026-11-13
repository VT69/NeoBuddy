package com.neobuddy.shared.college;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neobuddy.testsupport.DatabaseTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@DatabaseTest
@Transactional
class CollegeRepositoryTests {

  @Autowired private CollegeRepository colleges;

  @Test
  void savesAndFindsBySlug() {
    Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    College college = college("test-college", createdAt);

    colleges.saveAndFlush(college);

    assertThat(colleges.findBySlug("test-college"))
        .hasValueSatisfying(found -> {
          assertThat(found.getId()).isEqualTo(college.getId());
          assertThat(found.getName()).isEqualTo("Test College");
          assertThat(found.getAllowedEmailDomains()).containsExactly("test.example");
          assertThat(found.getSenderAllowlist())
              .containsExactly("placements@test.example", "@cell.test.example");
          assertThat(found.getNeoIdPattern()).isEqualTo(College.DEFAULT_NEO_ID_PATTERN);
          assertThat(found.getStatus()).isEqualTo(CollegeStatus.PILOT);
          assertThat(found.getCreatedAt()).isEqualTo(createdAt);
        });
    assertThat(colleges.findBySlug("unknown")).isEmpty();
  }

  @Test
  void slugIsUnique() {
    colleges.saveAndFlush(college("dup-college", Instant.now()));

    assertThatThrownBy(() -> colleges.saveAndFlush(college("dup-college", Instant.now())))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private static College college(String slug, Instant createdAt) {
    return new College(
        UUID.randomUUID(),
        slug,
        "Test College",
        List.of("test.example"),
        List.of("placements@test.example", "@cell.test.example"),
        College.DEFAULT_NEO_ID_PATTERN,
        CollegeStatus.PILOT,
        createdAt);
  }
}
