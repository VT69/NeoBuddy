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
