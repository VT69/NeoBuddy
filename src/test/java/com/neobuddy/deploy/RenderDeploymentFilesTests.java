package com.neobuddy.deploy;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The Render Blueprint and Dockerfile, read as files: no secret in the Blueprint, every variable
 * the render profile needs declared, and an image that runs unprivileged with the tuned JVM.
 */
class RenderDeploymentFilesTests {

  private static final Path BLUEPRINT = Path.of("render.yaml");
  private static final Path DOCKERFILE = Path.of("Dockerfile");
  private static final Path DOCKERIGNORE = Path.of(".dockerignore");
  private static final Pattern REQUIRED_PLACEHOLDER = Pattern.compile("\\$\\{([A-Z0-9_]+)}");

  @SuppressWarnings("unchecked")
  private static Map<String, Object> service() throws IOException {
    Map<String, Object> blueprint = new Yaml().load(Files.readString(BLUEPRINT, UTF_8));
    List<Map<String, Object>> services = (List<Map<String, Object>>) blueprint.get("services");
    assertThat(services).hasSize(1);
    return services.getFirst();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> envVars() throws IOException {
    return (List<Map<String, Object>>) service().get("envVars");
  }

  @Test
  void theBlueprintIsOneFreeDockerWebServiceCheckedOnTheHealthEndpoint() throws IOException {
    assertThat(service())
        .containsEntry("type", "web")
        .containsEntry("runtime", "docker")
        .containsEntry("plan", "free")
        .containsEntry("dockerfilePath", "./Dockerfile")
        .containsEntry("healthCheckPath", "/actuator/health");
  }

  @Test
  void onlyTheProfileHasAValueEverythingElseIsEnteredOnRender() throws IOException {
    for (Map<String, Object> variable : envVars()) {
      if ("SPRING_PROFILES_ACTIVE".equals(variable.get("key"))) {
        assertThat(variable).containsEntry("value", "render");
      } else {
        assertThat(variable)
            .as("%s must be sync: false with no value", variable.get("key"))
            .containsEntry("sync", false)
            .doesNotContainKey("value");
      }
    }
  }

  @Test
  void everyVariableTheRenderProfileCannotStartWithoutIsDeclared() throws IOException {
    Set<String> declared =
        envVars().stream().map(v -> (String) v.get("key")).collect(Collectors.toSet());
    // Placeholders without a default in the shared configuration: startup fails without them.
    Matcher required =
        REQUIRED_PLACEHOLDER.matcher(
            Files.readString(Path.of("src/main/resources/application.yml"), UTF_8));
    while (required.find()) {
      assertThat(declared).contains(required.group(1));
    }
    assertThat(declared)
        .contains(
            "NEOBUDDY_ENCRYPTION_KEY",
            "NEOBUDDY_BREVO_API_KEY",
            "NEOBUDDY_BREVO_FROM",
            "NEOBUDDY_BOOTSTRAP_SOURCE_REFRESH_TOKEN");
  }

  @Test
  void theImageRunsAsAnUnprivilegedUserOnAJavaTwentyOneJre() throws IOException {
    String dockerfile = Files.readString(DOCKERFILE, UTF_8);
    List<String> stages = dockerfile.lines().filter(line -> line.startsWith("FROM ")).toList();

    assertThat(stages).hasSize(2);
    assertThat(stages.getLast()).startsWith("FROM eclipse-temurin:21-jre");
    assertThat(dockerfile).containsPattern("(?m)^USER [1-9][0-9]*:[1-9][0-9]*$");
  }

  @Test
  void theJvmIsTunedForFiveHundredMegabytesAndUsesTheTrainedClassArchive() throws IOException {
    String dockerfile = Files.readString(DOCKERFILE, UTF_8);

    assertThat(dockerfile)
        .contains("-XX:+UseSerialGC")
        .contains("-XX:MaxRAMPercentage=")
        .contains("-XX:TieredStopAtLevel=1")
        .contains("-XX:+ExitOnOutOfMemoryError")
        .contains("-XX:ArchiveClassesAtExit=/app/app.jsa")
        .contains("\"-XX:SharedArchiveFile=/app/app.jsa\"");
  }

  @Test
  void theTrainingRunAtBuildTimeReachesNoDatabase() throws IOException {
    String dockerfile = Files.readString(DOCKERFILE, UTF_8);

    assertThat(dockerfile)
        .contains("SPRING_FLYWAY_ENABLED=false")
        .contains("SPRING_JPA_HIBERNATE_DDL_AUTO=none")
        .contains("NEOBUDDY_INGESTION_ENABLED=false")
        .contains("NEOBUDDY_DELIVERY_ENABLED=false")
        .contains("-Dspring.context.exit=onRefresh");
  }

  @Test
  void theBuildContextHoldsOnlyWhatTheBuildNeeds() throws IOException {
    List<String> lines = Files.readAllLines(DOCKERIGNORE, UTF_8);

    assertThat(lines).contains("*").doesNotContain("!.", "!deploy/", "!infra/", "!src/test/");
  }
}
