# NeoBuddy progress log

## T1 · Project scaffold (2026-11-13)

Status: **built, not fully verified.** Compile, format check, jar and `ModularityTests` are green
locally. The database-backed tests, app startup and CI have **not** been run yet, because the dev
machine has no working Docker and the repo has no remote. See "Verification" below.

### What was built

- Single-module Gradle (Kotlin DSL) Spring Boot app, group `com.neobuddy`, Java 21 toolchain,
  Gradle wrapper 9.9.1 (distribution checksum pinned).
- Versions in `gradle/libs.versions.toml`: Spring Boot 4.1.2, Spring Modulith 2.1.2,
  ShedLock 7.11.0, Spotless 8.11.0, google-java-format 2.0.0. Everything else (Flyway, PostgreSQL
  driver, Testcontainers 2.x, JUnit, Hibernate) is managed by the pinned Spring Boot BOM.
- Module packages `ingestion`, `processing`, `subscriber`, `notification`, `web`, `discord`,
  `shared`, each with `package-info.java` (`@ApplicationModule`). `shared` is registered as a
  shared module via `@Modulithic(sharedModules = "shared")`.
- `College` entity, `CollegeStatus`, `CollegeRepository` in `shared.college`, exposed as the
  named interface `college`.
- Flyway `V1__init.sql`: the Data model schema verbatim plus `shedlock`.
  `V2__modulith_event_publication.sql`: Spring Modulith's `event_publication` table.
- `SchedulingConfig` (`@EnableScheduling`, ShedLock JDBC lock provider using DB time).
- `SecurityConfig`: `/actuator/health/**` is public, everything else denied until T9.
- `application.yml` (datasource only from `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`, virtual
  threads on, `ddl-auto=validate`, only `health` exposed) and `application-local.yml`.
- `docker-compose.yml` (Postgres 16, bound to 127.0.0.1), `.github/workflows/ci.yml`, `README.md`,
  `.gitignore`, `.gitattributes`.
- Tests: `ModularityTests` (verify, module list, shared module, Documenter to
  `build/spring-modulith-docs`), `FlywayMigrationTests` and `CollegeRepositoryTests`
  (`@SpringBootTest` on Testcontainers Postgres 16).
- Housekeeping: `Architecture.md` moved to `docs/ARCHITECTURE.md` and `CLAUDE.md.txt` renamed to
  `CLAUDE.md`, the paths `CLAUDE.md` itself refers to.

### Decisions

- **`event_publication` via Flyway, not Modulith/Hibernate schema creation.** With the JPA event
  registry the table is a JPA entity, so the only automatic option is Hibernate `ddl-auto`, which
  would force `update`/`create` for the whole app. Flyway keeps one owner for every table, lets
  Hibernate stay on `validate` (so entity/schema drift fails at startup), and makes future
  Modulith schema changes explicit, reviewed migrations. It is a separate `V2` so `V1` stays
  exactly the Data model plus `shedlock`. The archive table is omitted because the archive entity
  is only registered when `completion-mode=archive`.
- **Spring Boot 4.1 / Modulith 2.1 / Testcontainers 2** are the latest stable lines. Boot 4 uses
  renamed starters (`starter-webmvc`, `starter-security-oauth2-client`, `starter-flyway`).
- **No dependency-management plugin**; BOMs are imported with Gradle's native `platform()`.
- **JDK provisioning:** the foojay toolchain resolver (settings plugin, not in ARCHITECTURE.md's
  list; build tooling only) and `gradle/gradle-daemon-jvm.properties` let `./gradlew` run from any
  JDK 8+ and download JDK 21 itself. Needed here because the machine has JDK 8/23/24/25 but no 21.
- **`College` lives in `shared`** because every module is scoped by college.
- **Local profile has fallback DB credentials** (`neobuddy`/`neobuddy`) that match the Compose
  container only. Other profiles have no defaults and fail fast if the env vars are missing.
- **DB tests fail without Docker rather than skip**, so a missing Docker can never produce a
  falsely green build.
- **Empty `InMemoryUserDetailsManager` bean** stops Spring Boot generating and logging a default
  password.

### Verification

| Check | Result |
| --- | --- |
| `./gradlew build -x test` (compile, Spotless check, boot jar) | Pass |
| `ModularityTests` (4 tests) | Pass |
| `FlywayMigrationTests`, `CollegeRepositoryTests` (5 tests) | **Not run**: no Docker daemon locally |
| App start with `local` profile, `/actuator/health` UP | **Not run**: no local Postgres |
| CI | **Not run**: no commits or GitHub remote yet |

### Follow-ups

- Repair or reinstall Docker Desktop (its WSL disk `ext4.vhdx` is missing), then run
  `./gradlew build` and the local run from the README to finish T1's done criteria. Things first
  exercised there: Hibernate validation of `College` (`text[]` columns) and of `V2` against
  Modulith's entity.
- Create the GitHub repo and push so CI runs. `gradlew` must be committed with the executable
  bit (`git add --chmod=+x gradlew`) or the workflow fails on Linux.
- The branch is `master` but tooling expects `main`; rename before the first push if wanted.
- `Claude Code playbook.md` is still in the repo root; move to `docs/` if it should be tracked.
- T5+: `NeoBuddyProperties`, `FieldEncryptor`, `Clock` in `shared`; remaining entities per task.

## T1 follow-up · Database tests without Docker (2026-11-13)

Status: **built, database path still unverified.** The 19 tests that need no database pass. The
5 database tests and the local app start have not passed yet: at the time of writing nothing was
listening on `localhost:5432` and Docker was not available.

### What was built

- `testsupport.DatabaseTest`: one meta-annotation (`@SpringBootTest`, `test` profile,
  `TestDatabaseInitializer`) used by every database test. Replaces `PostgresTestConfiguration`.
- `testsupport.TestDatabaseInitializer`: before the context starts, picks the database once per
  test run. With `NEOBUDDY_TEST_DB_URL` (+ `_USER`, `_PASSWORD`) it runs Flyway clean + migrate
  against that PostgreSQL; otherwise it starts one shared Testcontainers Postgres 16.
- `testsupport.ExternalTestDatabase`: reads the variables and refuses any database whose name
  does not end in `_test` (checked on the database name only, not host or URL parameters).
  Covered by `ExternalTestDatabaseTests` (15 cases).
- `build.gradle.kts`: `NEOBUDDY_TEST_DB_URL` is a test-task input, so switching modes re-runs tests.
- README and CLAUDE.md Commands describe both ways to run and test. `ci.yml` is unchanged and
  does not set the variable, so CI stays on Testcontainers.

### Decisions

- **Clean stays disabled in the application.** `spring.flyway.clean-disabled` is never set; only
  the test initializer builds a Flyway with clean enabled, and only when the `test` profile is
  active and the name guard passes.
- **Testcontainers mode no longer uses `@ServiceConnection`**; both modes set
  `spring.datasource.*` from the same initializer so there is one code path.
- **Missing user or password with the URL set is an error**, not a silent fallback to Docker.

### Verification

| Check | Result |
| --- | --- |
| `./gradlew build -x test` | Pass |
| `ModularityTests` (4) + `ExternalTestDatabaseTests` (15) | Pass |
| Guard: `NEOBUDDY_TEST_DB_URL=.../neobuddy` | Refused: "Refusing to clean database 'neobuddy'" |
| `./gradlew build --rerun-tasks` with `.../neobuddy_test` (local PostgreSQL 18.7) | Pass: 24 tests, 0 failures (Flyway 3, Modularity 4, College 2, guard 15) |
| App start with `local` profile (boot jar, `PORT=8081`) | Pass: Flyway applied V1 and V2 to `neobuddy`, started, `/actuator/health` UP with `db` UP; `/` returns 403 |

Update, later the same day: PostgreSQL 18.7 was installed locally with the `neobuddy` role and
the `neobuddy` and `neobuddy_test` databases, and the last two checks were rerun and pass. The
status line at the top of this section is superseded: the external-database path is verified.

### Follow-ups

- Port 8080 on the dev machine is taken by IIS, so run locally with `PORT=8081` (documented in
  the README).
- Testcontainers mode has still never run anywhere (no Docker locally, CI not triggered yet);
  the first CI run on GitHub is its first real test.
- Local PostgreSQL is 18 while production and CI target 16; nothing in the schema is
  version-specific, but CI on 16 stays the reference.
