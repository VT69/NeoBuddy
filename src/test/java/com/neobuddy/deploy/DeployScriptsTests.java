package com.neobuddy.deploy;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shell scripts the production instance and the deploy workflow run, with {@code aws} and
 * {@code docker compose} replaced by stubs. The scripts target Amazon Linux and the GitHub runner
 * (GNU tools, bash, jq), so this runs on Linux only: in CI and on Linux machines.
 */
@EnabledOnOs(OS.LINUX)
class DeployScriptsTests {

  private static final Path DEPLOY = Path.of("deploy").toAbsolutePath();
  private static final Path SSM_DEPLOY = Path.of(".github/scripts/ssm-deploy.sh").toAbsolutePath();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Records every call, then acts on the subcommand like the real CLI would. */
  private static final String AWS_STUB =
      """
      #!/usr/bin/env bash
      set -euo pipefail
      printf '%s\\n' "$*" >> "$STUB_DIR/aws.calls"
      case "$1 $2" in
        "ssm get-parameters-by-path") cat "$STUB_DIR/parameters.json" ;;
        "s3 cp") cp "$3" "$STUB_DIR/uploaded"; printf '%s\\n' "$4" > "$STUB_DIR/uploaded.key" ;;
        "ssm send-command")
          for arg in "$@"; do
            case "$arg" in file://*) cp "${arg#file://}" "$STUB_DIR/sent.json" ;; esac
          done
          echo cmd-0001 ;;
        "ssm get-command-invocation")
          case "$*" in
            *"--query Status"*) cat "$STUB_DIR/status" ;;
            *StandardOutputContent*) echo "deploy: done" ;;
            *) echo "" ;;
          esac ;;
        *) echo "unexpected aws call: $*" >&2; exit 99 ;;
      esac
      """;

  @TempDir Path dir;
  private Path stubs;

  record Result(int exit, String out, String err) {}

  @BeforeAll
  static void needsBashAndJq() {
    assumeTrue(commandExists("bash") && commandExists("jq"), "bash and jq are required");
  }

  @BeforeEach
  void installStubs() throws IOException {
    stubs = Files.createDirectories(dir.resolve("stubs"));
    executable(Files.writeString(stubs.resolve("aws"), AWS_STUB));
    Files.writeString(dir.resolve("status"), "Success\n");
  }

  // ---- render-env.sh -------------------------------------------------------------------------

  private void parameters(Map<String, String> values) throws IOException {
    List<Map<String, String>> list = new ArrayList<>();
    values.forEach((name, value) ->
        list.add(Map.of("Name", "/neobuddy/prod/" + name, "Value", value, "Type", "SecureString")));
    Files.writeString(
        dir.resolve("parameters.json"), JSON.writeValueAsString(Map.of("Parameters", list)));
  }

  private static Map<String, String> required() {
    Map<String, String> values = new HashMap<>();
    values.put("DB_PASSWORD", "p$w{d}=x\"y ${HOME} $$ end\\n");
    values.put("NEOBUDDY_ENCRYPTION_KEY", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
    values.put("NEOBUDDY_DOMAIN", "neobuddy.example.com");
    values.put("NEOBUDDY_SES_FROM", "NeoBuddy <alerts@neobuddy.example.com>");
    return values;
  }

  private Result renderEnv(Path out) throws Exception {
    return run(
        Map.of("AWS_REGION", "ap-south-1"),
        DEPLOY.resolve("render-env.sh").toString(),
        out.toString());
  }

  @Test
  void rendersEveryParameterSingleQuotedIntoAFileOnlyRootCanRead() throws Exception {
    Map<String, String> values = required();
    values.put("NEOBUDDY_BOOTSTRAP_COLLEGE_NEO_ID_PATTERN", "^[A-Z0-9]{8}$");
    values.put("EMPTY_ONE", "");
    parameters(values);
    Path out = dir.resolve("etc/app.env");

    Result result = renderEnv(out);

    assertThat(result.exit()).as(result.err()).isZero();
    assertThat(Files.readAllLines(out))
        .containsExactlyInAnyOrder(
            "DB_PASSWORD='p$w{d}=x\"y ${HOME} $$ end\\n'",
            "NEOBUDDY_ENCRYPTION_KEY='AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8='",
            "NEOBUDDY_DOMAIN='neobuddy.example.com'",
            "NEOBUDDY_SES_FROM='NeoBuddy <alerts@neobuddy.example.com>'",
            "NEOBUDDY_BOOTSTRAP_COLLEGE_NEO_ID_PATTERN='^[A-Z0-9]{8}$'",
            "EMPTY_ONE=''");
    assertThat(Files.getPosixFilePermissions(out))
        .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    assertThat(Files.readString(dir.resolve("aws.calls")))
        .contains(
            "ssm get-parameters-by-path --region ap-south-1 --path /neobuddy/prod/ --with-decryption");
  }

  @Test
  void printsParameterNamesButNeverValues() throws Exception {
    parameters(required());

    Result result = renderEnv(dir.resolve("app.env"));

    assertThat(result.exit()).isZero();
    assertThat(result.out()).contains("NEOBUDDY_ENCRYPTION_KEY").contains("DB_PASSWORD");
    for (String value : required().values()) {
      assertThat(result.out() + result.err()).doesNotContain(value);
    }
  }

  @Test
  void composeTakesTheRenderedValuesLiterally() throws Exception {
    assumeTrue(run(Map.of(), "docker", "compose", "version").exit() == 0, "docker compose needed");
    Map<String, String> values = required();
    values.put("NEOBUDDY_BOOTSTRAP_COLLEGE_NEO_ID_PATTERN", "^[A-Z0-9]{8}$");
    parameters(values);
    Path etc = Files.createDirectories(dir.resolve("etc"));
    assertThat(renderEnv(etc.resolve("app.env")).exit()).isZero();
    Files.writeString(
        etc.resolve("infra.env"), "AWS_REGION=ap-south-1\nLOG_GROUP=/neobuddy/prod\n");
    Files.writeString(etc.resolve("image.env"), "NEOBUDDY_IMAGE=neobuddy:test\n");

    Result config =
        run(
            Map.of("NEOBUDDY_HOME", DEPLOY.toString(), "NEOBUDDY_ETC", etc.toString()),
            DEPLOY.resolve("compose.sh").toString(),
            "config",
            "--format",
            "json");

    assertThat(config.exit()).as(config.err()).isZero();
    JsonNode services = JSON.readTree(config.out()).path("services");
    JsonNode app = services.path("app").path("environment");
    // `compose config` prints every literal $ doubled, so it can be read back as a compose file.
    assertThat(unescape(app.path("DB_PASSWORD").asText())).isEqualTo(values.get("DB_PASSWORD"));
    assertThat(unescape(app.path("NEOBUDDY_BOOTSTRAP_COLLEGE_NEO_ID_PATTERN").asText()))
        .isEqualTo("^[A-Z0-9]{8}$");
    assertThat(app.path("NEOBUDDY_SES_FROM").asText()).isEqualTo(values.get("NEOBUDDY_SES_FROM"));
    assertThat(app.path("DB_URL").asText()).isEqualTo("jdbc:postgresql://db:5432/neobuddy");
    assertThat(unescape(services.path("db").path("environment").path("POSTGRES_PASSWORD").asText()))
        .isEqualTo(values.get("DB_PASSWORD"));
    assertThat(services.path("caddy").path("environment").path("NEOBUDDY_DOMAIN").asText())
        .isEqualTo("neobuddy.example.com");
    assertThat(services.path("app").path("image").asText()).isEqualTo("neobuddy:test");
    assertThat(services.path("app").path("logging").path("driver").asText()).isEqualTo("awslogs");
  }

  @Test
  void aMissingRequiredParameterFailsAndKeepsTheOldFile() throws Exception {
    Map<String, String> values = required();
    values.remove("NEOBUDDY_ENCRYPTION_KEY");
    parameters(values);
    Path out = Files.writeString(dir.resolve("app.env"), "OLD='kept'\n");

    Result result = renderEnv(out);

    assertThat(result.exit()).isNotZero();
    assertThat(result.err()).contains("missing").contains("NEOBUDDY_ENCRYPTION_KEY");
    assertThat(Files.readString(out)).isEqualTo("OLD='kept'\n");
    try (var leftovers = Files.list(dir)) {
      assertThat(leftovers.map(p -> p.getFileName().toString()))
          .noneMatch(n -> n.startsWith(".app.env"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"it's", "two\nlines", "carriage\rreturn"})
  void aValueComposeCouldMisreadIsRefused(String value) throws Exception {
    Map<String, String> values = required();
    values.put("NEOBUDDY_SOURCE_NOTE", value);
    parameters(values);
    Path out = dir.resolve("app.env");

    Result result = renderEnv(out);

    assertThat(result.exit()).isNotZero();
    assertThat(result.err()).contains("NEOBUDDY_SOURCE_NOTE").doesNotContain(value);
    assertThat(out).doesNotExist();
  }

  @Test
  void aNameThatIsNotAnEnvironmentVariableIsRefused() throws Exception {
    Map<String, String> values = required();
    values.put("lower-case", "x");
    parameters(values);

    Result result = renderEnv(dir.resolve("app.env"));

    assertThat(result.exit()).isNotZero();
    assertThat(result.err()).contains("lower-case");
  }

  // ---- backup.sh -----------------------------------------------------------------------------

  private Map<String, String> backupEnv(boolean dumpFails) throws IOException {
    Path home = Files.createDirectories(dir.resolve("home"));
    Path etc = Files.createDirectories(dir.resolve("etc"));
    Files.writeString(
        etc.resolve("infra.env"), "AWS_REGION=ap-south-1\nBACKUP_BUCKET=neobuddy-backups-test\n");
    // Stands in for `compose.sh exec -T db pg_dump ...`; records its arguments.
    executable(
        Files.writeString(
            home.resolve("compose.sh"),
            "#!/usr/bin/env bash\nprintf '%s\\n' \"$*\" > \"$STUB_DIR/compose.args\"\n"
                + "echo '-- PostgreSQL database dump'\necho 'CREATE TABLE college ();'\n"
                + (dumpFails ? "echo 'pg_dump: error: connection lost' >&2\nexit 1\n" : "")));
    return Map.of(
        "NEOBUDDY_HOME", home.toString(),
        "NEOBUDDY_ETC", etc.toString(),
        "TMPDIR", Files.createDirectories(dir.resolve("tmp")).toString());
  }

  @Test
  void backupUploadsAGzippedDumpUnderADatedKey() throws Exception {
    Result result = run(backupEnv(false), DEPLOY.resolve("backup.sh").toString());

    assertThat(result.exit()).as(result.err()).isZero();
    assertThat(Files.readString(dir.resolve("compose.args")).strip())
        .isEqualTo("exec -T db pg_dump --username neobuddy --dbname neobuddy --no-owner");
    assertThat(gunzip(Files.readAllBytes(dir.resolve("uploaded"))))
        .isEqualTo("-- PostgreSQL database dump\nCREATE TABLE college ();\n");
    assertThat(Files.readString(dir.resolve("uploaded.key")).strip())
        .matches(
            "s3://neobuddy-backups-test/daily/neobuddy-\\d{4}-\\d{2}-\\d{2}T\\d{6}Z\\.sql\\.gz");
    try (var leftovers = Files.list(dir.resolve("tmp"))) {
      assertThat(leftovers).isEmpty();
    }
  }

  @Test
  void aFailedDumpIsNeverUploaded() throws Exception {
    Result result = run(backupEnv(true), DEPLOY.resolve("backup.sh").toString());

    assertThat(result.exit()).isNotZero();
    assertThat(dir.resolve("uploaded")).doesNotExist();
    try (var leftovers = Files.list(dir.resolve("tmp"))) {
      assertThat(leftovers).isEmpty();
    }
  }

  // ---- deploy.sh -----------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "neobuddy",
        "123.dkr.ecr.ap-south-1.amazonaws.com/neobuddy",
        "123.dkr.ecr.ap-south-1.amazonaws.com/neobuddy:abc; rm -rf /",
        "123.dkr.ecr.ap-south-1.amazonaws.com/neobuddy:$(id)",
        "--help"
      })
  void deployRefusesAnythingButAnImageReference(String image) throws Exception {
    Path etc = Files.createDirectories(dir.resolve("etc"));

    Result result =
        run(Map.of("NEOBUDDY_ETC", etc.toString()), DEPLOY.resolve("deploy.sh").toString(), image);

    assertThat(result.exit()).isEqualTo(2);
    assertThat(result.err()).contains("not an image reference");
    assertThat(dir.resolve("aws.calls")).doesNotExist();
  }

  // ---- .github/scripts/ssm-deploy.sh ---------------------------------------------------------

  private static final String IMAGE =
      "123456789012.dkr.ecr.ap-south-1.amazonaws.com/neobuddy:0123456789abcdef0123456789abcdef01234567";

  @Test
  void ssmDeploySendsTheDeployDirectoryAndTheImageToTheInstance() throws Exception {
    Result result = run(Map.of(), SSM_DEPLOY.toString(), "i-0123456789abcdef0", IMAGE);

    assertThat(result.exit()).as(result.err()).isZero();
    assertThat(result.out()).contains("deploy: done").contains("finished: Success");
    assertThat(Files.readString(dir.resolve("aws.calls")))
        .contains(
            "ssm send-command --instance-ids i-0123456789abcdef0 --document-name AWS-RunShellScript");
    JsonNode commands = JSON.readTree(dir.resolve("sent.json").toFile()).path("commands");
    assertThat(commands.get(0).asText()).isEqualTo("set -e");
    assertThat(commands.get(3).asText()).isEqualTo("/opt/neobuddy/incoming/deploy.sh " + IMAGE);

    // Unpack the bundle the way the instance does and compare it with deploy/.
    String unpack = commands.get(2).asText();
    assertThat(unpack)
        .startsWith("echo ")
        .endsWith(" | base64 -d | tar -xzf - -C /opt/neobuddy/incoming");
    String bundle = unpack.substring("echo ".length(), unpack.indexOf(" | "));
    Path unpacked = Files.createDirectories(dir.resolve("unpacked"));
    Path tarball = Files.write(dir.resolve("bundle.tgz"), Base64.getDecoder().decode(bundle));
    assertThat(run(Map.of(), "tar", "-xzf", tarball.toString(), "-C", unpacked.toString()).exit())
        .isZero();
    for (String file :
        List.of(
            "deploy.sh",
            "render-env.sh",
            "backup.sh",
            "compose.sh",
            "docker-compose.prod.yml",
            "Caddyfile",
            "systemd/neobuddy-backup.service",
            "systemd/neobuddy-backup.timer")) {
      assertThat(unpacked.resolve(file)).hasSameBinaryContentAs(DEPLOY.resolve(file));
    }
    assertThat(Files.isExecutable(unpacked.resolve("deploy.sh"))).isTrue();
    assertThat(unpacked.resolve("README.md")).doesNotExist();
  }

  @Test
  void ssmDeployFailsWhenTheRemoteScriptFails() throws Exception {
    Files.writeString(dir.resolve("status"), "Failed\n");

    Result result = run(Map.of(), SSM_DEPLOY.toString(), "i-0123456789abcdef0", IMAGE);

    assertThat(result.exit()).isNotZero();
    assertThat(result.out()).contains("finished: Failed");
  }

  // ---- helpers -------------------------------------------------------------------------------

  private Result run(Map<String, String> env, String... command) throws Exception {
    ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile());
    Map<String, String> environment = builder.environment();
    environment.put("PATH", stubs + ":" + System.getenv("PATH"));
    environment.put("STUB_DIR", dir.toString());
    environment.putAll(env);
    Process process = builder.start();
    process.getOutputStream().close();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Thread errReader = Thread.ofVirtual().start(() -> copy(process.getErrorStream(), err));
    copy(process.getInputStream(), out);
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("timed out: " + String.join(" ", command));
    }
    errReader.join();
    return new Result(process.exitValue(), out.toString(UTF_8), err.toString(UTF_8));
  }

  private static void copy(java.io.InputStream in, ByteArrayOutputStream out) {
    try (in) {
      in.transferTo(out);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private static boolean commandExists(String name) {
    try {
      return new ProcessBuilder("sh", "-c", "command -v " + name).start().waitFor() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }

  private static Path executable(Path file) throws IOException {
    Set<PosixFilePermission> rwx = PosixFilePermissions.fromString("rwx------");
    return Files.setPosixFilePermissions(file, rwx);
  }

  private static String unescape(String composeValue) {
    return composeValue.replace("$$", "$");
  }

  private static String gunzip(byte[] bytes) throws IOException {
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
      return new String(in.readAllBytes(), UTF_8);
    }
  }
}
