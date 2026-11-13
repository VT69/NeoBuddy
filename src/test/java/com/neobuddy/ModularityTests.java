package com.neobuddy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModularityTests {

  private final ApplicationModules modules = ApplicationModules.of(NeoBuddyApplication.class);

  @Test
  void modulesRespectTheirBoundaries() {
    modules.verify();
  }

  @Test
  void allDesignedModulesExist() {
    assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
        .containsExactlyInAnyOrder(
            "ingestion", "processing", "subscriber", "notification", "web", "discord", "shared");
  }

  @Test
  void sharedIsRegisteredAsSharedModule() {
    assertThat(modules.getSharedModules())
        .extracting(module -> module.getIdentifier().toString())
        .containsExactly("shared");
  }

  /** Writes module diagrams and canvases to build/spring-modulith-docs. */
  @Test
  void writesDocumentation() {
    new Documenter(modules).writeDocumentation();
  }
}
