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

## T2 · Extraction engine (2026-11-13)

Status: **done.** `./gradlew build --rerun-tasks` is green against the local `neobuddy_test`
database: 202 tests, 165 passed, 0 failed, 37 skipped (the T3 tests, disabled on purpose).
Branch `t2-extraction`, cut from `t1-scaffold`.

### What was built (`com.neobuddy.processing.extract`)

- Types: `ContentExtractor`, `TextChunk(source, location, text)`, `DetectedType`,
  `AttachmentRef(filename, declaredMimeType, sizeBytes)`, `ExtractionException`,
  `ExtractionResult` (EXTRACTED / SKIPPED_TOO_LARGE / SKIPPED_UNSUPPORTED / FAILED).
- `TypeDetector`: Tika core for magic bytes; filename and declared MIME type are hints only.
  Office containers are told apart by content (ZIP central directory entry names, OLE2 workbook
  stream), so an .xlsx under any name is XLSX and a plain zip named .xlsx is UNKNOWN.
- `ExtractorRegistry`: entry point. Size cap, detection, extractor choice, WARN on skip or failure
  with the sanitised filename only. `extractBody(text, html)` prefers the HTML part.
- `SafeExtractionRunner`: one worker thread per file, bounded by a semaphore (default 2), 30 s
  timeout, interrupts on timeout, turns any exception or error into `ExtractionException`.
- Extractors: `ExcelExtractor` (xlsx + xls, streaming reader above the threshold, all sheets,
  `Sheet1!R42`), `PdfExtractor` (`p3:L17`), `DocxExtractor` (`P4`, `T2!R5`), `HtmlBodyExtractor`
  (`T2!R5` rows, `B7` blocks), `PlainTextExtractor` (`L12`).
- `ExtractionProperties` (`neobuddy.extraction.*`): max-attachment-size 25MB,
  excel-streaming-threshold 5MB, timeout 30s, max-concurrent 2.
- Dependencies, all named in ARCHITECTURE.md: Tika core 4.2.0, POI 6.0.0,
  excel-streaming-reader 6.0.0, PDFBox 3.0.8, jsoup 1.24.1, Commons CSV 1.14.1 (for T3).
- Fixtures: `SyntheticFiles` builds every test file in memory (2-sheet xlsx with numeric reg-no,
  xls, PDF table, docx table, HTML email, plus empty, truncated, zip-bomb and noise variants).
  All names and IDs are invented; no fixture file is checked in.

### T3 hand-off (for Vaibhav)

- `processing.extract.CsvExtractor` and `processing.match.Tokenizer`: both methods throw
  `UnsupportedOperationException`; the full contract is in each class Javadoc.
- `CsvExtractorTests` (16) and `TokenizerTests` (21) are complete and `@Disabled("T3 - Vaibhav
  implements")`.
- To finish T3: implement the methods, add `@Component` to `CsvExtractor` (it is deliberately not
  a bean yet, so CSV attachments are currently skipped as unsupported), remove both `@Disabled`,
  and update two tests that pin the interim state: `ExtractorRegistryTests
  .csvIsSkippedUntilT3RegistersItsExtractor` and `ExtractionWiringTests
  .everyExtractorExceptTheT3CsvStubIsRegistered`.
- **The T3 tests have never been executed**, because there is nothing to run them against. They
  were reviewed by hand against the contract. If one looks wrong, suspect the test too.
- Contract choices worth a look before coding: "alphanumeric" is ASCII only; the three tokenizer
  passes are independent and unioned (so words inside an email address are also tokens); CSV row
  numbers count blank lines; blank cells are dropped from the joined text.

### Decisions

- **Files are read fully into memory (at most the 25 MB cap + 1 byte)** before detection. This
  enforces the real size, lets detection and extraction share the bytes, and keeps the timed task
  free of network I/O.
- **Long numbers are written out in full.** POI renders a 12-digit General cell as Excel does,
  `2.12346E+11`, which would destroy a registration number; numeric cells that come out in
  scientific notation are replaced by the plain digits. Found by a test.
- **Binary formats can never be chosen by name.** Random bytes named `.xlsx` were first detected
  as XLSX from the name; now the name and declared type may only choose between plain text, CSV
  and HTML. Found by a test.
- **HTML layout tables are walked into, not emitted.** A `<tr>` that contains another table is
  layout; emitting it would create one chunk holding every student in the email, and that chunk
  could end up as someone else's match snippet.
- **Row text drops blank cells**, for every tabular format, so chunks are compact and consistent.
- **Zero-byte attachment = EXTRACTED with no chunks** at the registry; the binary extractors
  themselves reject zero bytes.
- **`TextChunk.toString()` omits the text**, so a chunk can never leak into a log by accident.
- **Timeouts cannot kill a thread.** A parser that ignores the interrupt keeps its slot until it
  returns; other callers give up after the same timeout instead of queueing forever.
- **Formulas are never evaluated**; the last saved value is used. `ZipSecureFile` is untouched,
  and a test proves a crafted zip bomb is rejected.

### Follow-ups

- T4 must call `ExtractorRegistry` and reconcile `AttachmentRef` here with the one sketched for
  `RawEmail` in ARCHITECTURE.md (ingestion), probably by moving it to a shared place.
- Not handled yet: password-protected files (reported as FAILED or skipped), scanned PDFs (no
  chunks), legacy `.doc`, text encodings other than UTF-8/UTF-16-with-BOM, a cap on the number of
  chunks per file, text boxes and headers in .docx.
- Testcontainers mode and CI are still unexercised (see T1 follow-ups).

## T3 · CsvExtractor + Tokenizer (2026-11-13)

Status: **done.** `./gradlew build --rerun-tasks` is green against the local `neobuddy_test`
database: 223 tests, 223 passed, 0 failed, 0 skipped. Branch `t3-csv-tokenizer`, cut from
`t2-extraction`. Implemented by Claude Code at Vaibhav's request (the playbook had this as his).

### What was built

- `CsvExtractor` (now a `@Component`): Apache Commons CSV with the RFC 4180 format; comma or
  semicolon chosen from the first non-blank line, counting only outside quotes; BOM dropped by
  `TextSupport.decode`; blank rows emit nothing but keep their row number (`R12`).
- `Tokenizer` (now a `@Component`): a precompiled, possessive email pattern; a precompiled split
  on non-alphanumerics for words of 4+; and a regex-free single pass for the per-cell rule
  (spaces and hyphens removed, 4 to 20 ASCII alphanumerics). Returns a `Set<String>`.
- `@Disabled` removed from `CsvExtractorTests` and `TokenizerTests`. Every test written in T2
  passed unchanged against the implementation, except the 50,000-row test (see below).
- Interim tests updated: the registry now extracts CSV (`csvAttachmentsAreExtractedRowByRow`,
  `aMalformedCsvFailsAloneAndLogsNoContent`), and the wiring test expects six extractors.
- `TextSupport.clean` / `joinCells` no longer compile a regular expression per cell
  (`String.replaceAll` did); they are one pass over the characters. This speeds up every
  tabular extractor, not only CSV.

### Performance of the 50,000-row file (this machine, about 1.9 MB)

- Warm: roughly 0.1 to 0.2 s, about the cost of Commons CSV parsing alone.
- First call in a fresh JVM: 0.55 to 0.9 s. Commons CSV parsing alone is 0.2 to 0.35 s cold, so
  this is JIT warm-up, not extractor code.
- Before the `TextSupport` change the first call took about 1.0 s and warm calls about 0.2 s.
- The test therefore runs one untimed warm-up extraction and holds the second to 1 second. This
  is the only T2-written test whose body changed (it had a 10 s limit and no warm-up).

### Decisions

- **Row number = Commons CSV record number.** RFC 4180 format keeps blank lines as records, so
  no separate line counting is needed and a quoted multi-line field is still one row.
- **Delimiter detection scans only the first non-blank line**, by hand, so it costs nothing on a
  large file and ignores delimiters inside quotes.
- **Malformed CSV (unclosed quote) fails the file** with "Could not read the CSV"; the Commons
  CSV message is kept only as the exception cause and is never logged.
- **Cell rule strips literal spaces and hyphens only**, as the contract says; a tab inside a
  cell means no cell token (the word pass still tokenizes it).

### Follow-ups

- T4 wires `Tokenizer` into `Matcher` and calls `ExtractorRegistry`.
- If cold-start latency ever matters, the options are a warm-up at startup or a hand-written
  CSV reader; neither is needed for a poller that runs every 2 minutes.
