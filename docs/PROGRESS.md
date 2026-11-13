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

## T4 · Classification and matching (2026-11-13)

Status: **done, with one caveat on performance.** `./gradlew build --rerun-tasks` is green against
the local `neobuddy_test` database: 322 tests, 0 failed, 0 skipped, including the 8 golden tests,
the performance tests and `ModularityTests`. Branch `t4-matching`, cut from `t3-csv-tokenizer`.

Caveat: the performance tests bound matching and a CSV end-to-end run, **not** a 50,000-row
.xlsx end to end. That took 16.5 s on this machine on a cold JVM, almost all of it Apache POI
reading the workbook. See "Performance" below.

### What was built

- `ingestion.RawEmail` (new) and `ingestion.AttachmentRef` (moved from `processing.extract`).
- Processing public API (`com.neobuddy.processing`): `ProcessingService`, `ProcessingResult`
  (+ `ExtractionSummary`), `EventType`, `Classification`, `MatchHit`, `MatchesFound`,
  `IdentifiersChanged`, and the ports `IdentifierSource`, `ProcessingResultSink`,
  `AttachmentSource`.
- `processing.classify`: `EmailClassifier`, `KeywordEmailClassifier`, `CompanyNameResolver`,
  `ClassifierProperties` (`neobuddy.classifier.rules`, `.known-recruiters`,
  `.colleges.<slug>` overrides). Starting keyword lists are in `application.yml`.
- `processing.match`: `IdentifierIndex`, `InMemoryIdentifierIndex` (college -> token -> refs in
  an `AtomicReference`, rebuilt on `IdentifiersChanged` after commit and every
  `neobuddy.matching.index-refresh`, default 5 min), `IdentifierRef`, `Matcher`.
- `ProcessingService.process(college, email, attachments)`: subject + body + attachment chunks ->
  classify -> match -> `ProcessingResultSink.save` (if a sink exists) -> publish `MatchesFound`
  (only when there are hits).
- Tests: golden scenarios a) to h) in `ProcessingServiceGoldenTests` run the real extractors, the
  rules shipped in `application.yml`, the real tokenizer, index and matcher, with fakes only at
  the ports. Plus unit tests for classifier, resolver, index, matcher and service, a Spring wiring
  test, and the performance tests.

### Decisions

- **`RawEmail` and `AttachmentRef` live in ingestion's base package.** That package is the
  module's public API; ingestion produces these and processing consumes them, so the dependency
  runs processing -> ingestion, the same direction as the `EmailIngested` event will. Leaving
  `AttachmentRef` in processing would have made `RawEmail` point back and created a cycle.
- **`IdentifiersChanged` lives in processing, not subscriber.** Subscriber must depend on
  processing to implement `IdentifierSource`; defining the event in subscriber would make the
  two modules depend on each other.
- **Two interfaces differ from the sketch in ARCHITECTURE.md**, because of per-college scoping:
  `EmailClassifier.classify(collegeSlug, email)` and `IdentifierIndex.lookup(collegeId, token)`.
- **Classification order: offer in subject, shortlist in subject, offer in body, shortlist in
  body, else OTHER.** OFFER outranks SHORTLIST at the same level, and the subject outranks the
  body, because shortlist mails often mention the offer on the table in their body.
- **Keywords match whole words only** ("offer" does not match "offering"), so variants are listed
  explicitly. The lists are a starting set and need tuning on real mail.
- **A per-college override replaces only the keyword lists it names**; the rest inherit.
  College recruiters are added to the global list.
- **Emails of type OTHER are still matched**, and their hits carry `OTHER`. Dropping them would
  turn every misclassification into a silent miss. Whether OTHER hits notify is a T7 decision.
- **The subject line is matched too**, as its own chunk.
- **One hit per user, for the first chunk they appear in** (subject, body, then attachments in
  order). If two of their identifiers are in the same chunk, the lower identifier id wins, so
  the result does not depend on hash order.
- **Snippet = the matched chunk only**, cut to 300 characters with an ellipsis.
- **The index refresh is not behind ShedLock**: each instance has its own copy to refresh.
- **Optional ports are injected as `Optional<...>`**, so the app starts before T5 (no
  `IdentifierSource`: index empty, one WARN) and before T6 (no sink: nothing stored).
- `toString()` of `RawEmail`, `MatchHit` and `IndexedIdentifier` omits content and identifiers.

### Performance (this machine)

| Measurement | Time |
| --- | --- |
| Matching 50,000 row chunks x 10,000 identifiers (asserted < 5 s) | 1.0 s |
| End to end, 50,000-row CSV attachment x 10,000 identifiers (asserted < 5 s) | 2.2 s |
| End to end, 50,000-row .xlsx x 10,000 identifiers (not asserted) | 16.5 s cold |
| of which: POI opening + reading 200,000 cells, in-memory mode | 4 to 11 s |
| of which: same with excel-streaming-reader | 3.5 to 8 s |
| of which: tokenizing all rows | 0.4 to 1.6 s |

Both readers were tried with inline and shared strings; the cost is in POI, not in NeoBuddy code.
An 11 s read is inside the 30 s extraction timeout but not by a wide margin on slow hardware.

### Follow-ups

- **Faster .xlsx reading**, if large sheets are common: read sheets with POI's SAX event API
  (`XSSFReader` + `XSSFSheetXMLHandler`) instead of building cell objects. This would replace
  both the in-memory path and excel-streaming-reader for .xlsx, so it changes a library choice
  named in ARCHITECTURE.md and needs a decision.
- T5: subscriber implements `IdentifierSource` (active users only) and publishes
  `IdentifiersChanged`. T6: implement `ProcessingResultSink` and `AttachmentSource`, add an
  attachment id to `AttachmentRef` for fetching, and decide the transaction boundary around
  save + `MatchesFound`.
- Identifier shapes the tokenizer cannot match: shorter than 4 characters, longer than 20 in a
  cell, or containing characters other than letters, digits, spaces and hyphens (for example a
  registration number written `21/BCE/1234`). Worth checking against real identifier formats in T5.
- A chunk that names several students (a prose line, a PDF line) becomes the snippet for each of
  them, so a snippet can contain another student's name or id. Inherent to line-based chunks.
- Classifier keywords and company patterns are untested against real placement mail.

## T5 · Subscriber module, field encryption and v1 bootstrap (2026-11-13)

Status: **done.** `./gradlew build` is green against the local `neobuddy_test` database: 400
tests, 0 failed, 0 skipped, including `ModularityTests`. Started locally twice with bootstrap
variables (fake values, against `neobuddy_test`): the first start logged "college created, admin
created", the second "already present"; without `NEOBUDDY_ENCRYPTION_KEY` the app refuses to
start. Branch `t5-subscriber`, cut from `t4-matching`. No schema change.

T6 (Gmail ingestion) was sent in the same message and has **not** been started, per CLAUDE.md.

### What was built

- `shared.crypto` (named interface `crypto`): `FieldEncryptor` (AES-256-GCM, fresh 96-bit IV per
  value, layout `[key id][IV][ciphertext+tag]`, key id bound as associated data, key from
  `NEOBUDDY_ENCRYPTION_KEY`), `EncryptedStringConverter` (JPA), `EncryptionProperties`.
- `shared.time`: one `Clock` bean.
- `shared.college`: `College.updateSettings`, `College.acceptsSubscribers`,
  `CollegeRepository.findByAllowedEmailDomain`.
- Subscriber public API (`com.neobuddy.subscriber`): `SubscriberService`, `SubscriberView`,
  `OidcClaims`, `IdentifierType`, `ChannelType`, `UserRole`, `UserStatus`, `ChannelAddress`,
  `NeoId`, `IdentifierNormalizer`, and the exceptions.
- `subscriber.persistence` (internal): `AppUser`, `Identifier`, `Channel`, `LinkCode`, their
  repositories, and `SubscriberIdentifierSource`, the implementation of processing's
  `IdentifierSource` port (active users only).
- `subscriber.bootstrap` (internal): `BootstrapProperties` (`neobuddy.bootstrap.*`), `Bootstrap`
  (the upsert) and `BootstrapRunner` (an `ApplicationRunner`, highest precedence).
- Tests: encryption (every flipped bit, truncation, wrong key, unknown key id, bad keys),
  normaliser and validation, the service against PostgreSQL (duplicate NeoID in one college
  rejected, allowed in another, cascade on delete to identifiers, channels, link codes, match_hit
  and delivery, address encrypted at rest), bootstrap (twice changes nothing, down to the
  encrypted bytes), and the T4 wiring test now runs through the real subscriber module.

### Decisions

- **No schema change.** `app_user.google_sub` is NOT NULL, but the bootstrap admin has never
  signed in, so the account is created with the placeholder `pending:<email>`. The first Google
  sign-in with that verified email takes the account over. Any other second Google account
  claiming a registered email is rejected.
- **Emails are stored lower-case.** `citext` compares case-insensitively against literals but
  not reliably against bound parameters, so lookups never depend on it.
- **One identifier per type per user.** Adding the same value again is a no-op; a different
  value of the same type is refused until the old one is removed. This limits squatting on other
  students' identifiers, which the design cannot otherwise prevent.
- **Registration number rule: 4 to 20 letters and digits after normalisation**, because that is
  all the tokenizer can find. `21/BCE/1234` is rejected at entry rather than silently never
  matching. NeoIDs must match the college pattern and the same rule.
- **College email = exact domain match** on the part after the last `@`; `evilcollege.edu` and
  `mail.college.edu` do not pass for `college.edu`.
- **Sign-in rule:** email verified, its domain on an ACTIVE or PILOT college, and if Google sends
  an `hd` claim it must be one of that college's domains. A missing `hd` is accepted.
- **`registerFromOidc` records consent as of the call**, so the T9 sign-in page must show the
  consent notice before calling it.
- **A paused user is left out of the index**, which stops matching as well as alerts; they will
  have no match history for the paused period.
- **A changed channel address is unverified again**; repeating the same address changes nothing
  and does not re-encrypt.
- **Bootstrap fails the startup** on a configuration it cannot apply (admin email off the college
  domains, NeoID held by someone else), and rolls the whole run back. Optional values that are
  removed from the configuration later are left in the database, not deleted.
- **No fallback encryption key in the `local` profile.** A known key in the repo would end up
  encrypting real data the first time someone points `local` at it. Tests use an obviously fake
  key in `src/test/resources/application-test.yml`.
- **Key rotation is prepared, not built:** the leading key-id byte is checked on decrypt, but
  there is one key and no re-encryption tool yet.

### Follow-ups

- T6: `mail_source.refresh_token_enc` can use `EncryptedStringConverter`; the source bootstrap
  should run after `BootstrapRunner` and look the college up by slug.
- T7 needs a way to read verified, enabled channel addresses for delivery; `SubscriberView`
  deliberately leaves addresses out.
- `LinkCode` has an entity and repository only; issuing, hashing and checking codes belongs to
  the channel-verification tasks.
- Back up `NEOBUDDY_ENCRYPTION_KEY` separately from the database; a restore needs both.
- The verification run left a `verify-college` row in `neobuddy_test`; the next test run wipes it.

## T6 · Gmail ingestion (2026-11-13)

Status: **built and tested against a simulated Gmail; not yet run against the real one.**
`./gradlew build` is green against the local `neobuddy_test` database: 480 tests, 0 failed, 0
skipped, including `ModularityTests`. The app starts locally with the poller on. The task's own
"done when" (a real email to the real inbox producing a `match_hit` row within one poll) needs
Vaibhav's Google credentials and has **not** been done; the steps are in the README. Branch
`t6-ingestion`, cut from `t5-subscriber`. No schema change.

### What was built

- Ingestion public API (`com.neobuddy.ingestion`): `EmailIngested`, `SourceAuthExpired`,
  `MailReader` + `LoadedMail` (how processing fetches a message), `ProcessedEmails` (how
  processing records the outcome).
- `ingestion.source` (internal): `SourceInbox` entity for `mail_source` (refresh token through
  `EncryptedStringConverter`), the `MailSource` port and its types, `SenderAllowlist`,
  `ProcessedEmailStore` (`insert ... on conflict (rfc_message_id) do nothing`), `IngestionWriter`
  (the transactions), `SourceSync` (one poll of one source), `MailPoller` (`@Scheduled` +
  `@SchedulerLock`), `SourceBootstrap` + runner for `neobuddy.bootstrap.source.*`.
- `ingestion.gmail` (internal): `GmailMailSource` (history paging, metadata and full fetch,
  recursive MIME walk, streamed attachment download, stale-cursor and `invalid_grant` handling,
  Resilience4j retry on 429, 5xx and read timeouts), `GmailMailSourceFactory`, `GmailProperties`.
- `ingestion.tools.SourceAuthorizer` and the Gradle task `authorizeSource`: the loopback OAuth
  flow with PKCE and state, written against the JDK only; prints the refresh token once.
- `processing.pipeline` (internal): `EmailIngestedListener` (`@ApplicationModuleListener`) and
  `StoredProcessingResults`, the `ProcessingResultSink` that writes `match_hit`.
- Tests: the Gmail adapter against WireMock (21), the poller against WireMock and PostgreSQL
  (12), the whole path from a poll to `match_hit` with a synthetic .xlsx (7), source bootstrap
  (8), the authoriser with a simulated browser (6), sender allowlist (26).

### Decisions

- **`EmailIngested` carries references, not the email.** Spring Modulith stores the event until
  its listener finishes; storing bodies or attachments there would break the rule that sheet
  content is never persisted. The listener fetches the message through `MailReader`. A test
  checks the stored event contains no content. This differs from the wording "load and publish":
  the poller loads only headers, the full message is loaded when it is processed.
- **Headers first.** The poller fetches headers (`format=metadata`) to filter and dedupe, so mail
  from other senders is never downloaded.
- **The sink lives in processing, and `processed_email` stays ingestion's.** Ingestion cannot
  implement a processing port (that would be a module cycle), so the sink is in processing and
  updates `processed_email` only through ingestion's `ProcessedEmails` API.
- **A new source has no cursor; the first poll sets it to "now".** The brief asked for this at
  bootstrap via `users.getProfile`; doing it in the first poll gives the same result ("ingestion
  starts from now", up to one poll interval later) without making startup depend on Google.
- **`AttachmentRef` is unchanged.** Attachments are tied to their Gmail attachment ids by
  instance inside `LoadedMessage`, so no id had to be added to the public record.
- **A part with a filename is an attachment**, inline or not; small ones arrive inside the
  message and are read without a second request.
- **Cursor per page:** mid-way it advances to the highest history id on the page, on the last
  page to the mailbox's current id, always after the page's transaction has committed, and never
  backwards.
- **Failure handling in the listener:** the work runs in an inner transaction; on any failure it
  rolls back and the email is marked FAILED with the exception class name only. A FAILED email
  is not retried automatically.
- **`republish-outstanding-events-on-restart` is on**, so an email recorded but not yet processed
  when the app stops is processed at the next start. Reprocessing is idempotent
  (`unique (user_id, email_id)`), which a test replays.
- **Matches for a user or identifier deleted mid-flight are skipped**, not allowed to fail the
  whole email.
- **A message without a Message-ID** is recorded under `<gmail-ID@neobuddy.invalid>`, so it is
  still deduped within its inbox.
- **Drafts are skipped; spam and trash are not filtered.**
- **Each source fails alone.** Found while checking the local start: one source row whose token
  did not decrypt stopped the whole poll with a stack trace. Sources are now listed by id and
  polled one by one; an unexpected failure is logged with its class only.
- **The authoriser uses no extra library.** Google's `google-oauth-client-jetty` is not named in
  ARCHITECTURE.md, so the loopback flow is about 200 lines on the JDK's HTTP server and client.
- **Found by tests:** the attachment stream did not handle JSON escapes (`=` written as
  `=`); and the authoriser closed its local server before a refused or denied callback had
  been answered in the browser.

### Not verified

- **Real Gmail.** Everything Gmail-related ran against WireMock stubs written from the API
  documentation. Real responses may differ in details; the first real run is the real test.
- **`./gradlew authorizeSource` against Google.** It was run only without credentials (prints
  the instruction and exits) and against a simulated Google in tests.
- Testcontainers mode and CI remain unexercised (see T1).

### Follow-ups

- **`processed_email` is keyed by `rfc_message_id` alone.** If two colleges' inboxes receive the
  same email, only the first college processes it. Fine for one college; before a second one the
  key should include `college_id`, which is a schema change to agree first.
- FAILED emails need the admin reprocess endpoint (T11); transient Gmail failures during
  processing currently end as FAILED rather than being retried.
- The From header is not proof of origin; checking Gmail's authentication results would harden
  the sender filter.
- `SourceAuthExpired` and `MatchesFound` have no listeners yet (T7, T10).
- The Gmail fetch in the listener runs inside a database transaction and holds a connection for
  its duration; acceptable at this volume.
- The check runs left `verify-t6` colleges and sources in `neobuddy_test`; the next test run
  wipes them.

## T7 · Notification core: outbox, dispatcher, ntfy push and SES email (2026-11-13)

Status: **built and tested against a simulated ntfy and a mocked SES; not yet run against the
real ones.** `./gradlew build` is green against a local `neobuddy_test` database: 587 tests, 0
failed, 0 skipped, including `ModularityTests`. The app was started locally and its scheduled
dispatcher delivered a hand-queued alert to a stand-in ntfy server. The task's own "done when" (a
real email producing a phone push and an SES email within 3 minutes) needs Vaibhav's ntfy topic,
SES identity and Gmail credentials and has **not** been done; the steps are in the README under
"Alerts: push and email". No schema change.

### What was built

- Notification public API (`com.neobuddy.notification`): `NotificationChannel`, `Alert`,
  `DeliveryResult` (SUCCESS / RETRYABLE / PERMANENT with a content-free reason), and the
  `DeliveryDead` event.
- `notification.delivery` (internal): `DeliveryService` (listens to `MatchesFound`, one
  `delivery` row per verified, enabled channel), `DeliveryStore` (plain SQL), `DeliveryDispatcher`
  (claim, send, record), `DeliveryDispatchJob` (`@Scheduled` every 10 s + ShedLock),
  `DeliveryProperties` (`neobuddy.delivery.*`).
- `notification.channel` (internal): `AlertRenderer`, `NtfyPushChannel` (Spring `RestClient`),
  `SesEmailChannel` (AWS SDK v2 `SesV2Client`, text + HTML), `LoggingEmailChannel`, `OtpMailer`,
  a Resilience4j rate limiter per channel, `ChannelProperties` (`neobuddy.channels.*`).
- Subscriber: `ChannelVerificationService` (ntfy topics, email one-time codes), `ChannelDirectory`
  (the verified, enabled channels and their decrypted addresses, for delivery), the
  `EmailOtpSender` port that notification implements.
- Read APIs the dispatcher needed: `processing.StoredMatches` and `ProcessedEmails.find`.
- New dependencies, both named in ARCHITECTURE.md: `software.amazon.awssdk:sesv2` 2.59.0 (without
  the unused Netty client) and `resilience4j-ratelimiter`.
- Tests (107 new): dispatcher state machine against WireMock ntfy and a mocked SES client (18),
  outbox fan-out (8), backoff (5), ntfy channel (23), SES channel (15), renderer (12), channel
  verification (23), and the whole path from a Gmail poll to exactly one ntfy POST (3).

### Decisions

- **`DeliveryService` is a plain, synchronous `@EventListener` on `MatchesFound`.** Processing
  publishes the event inside the transaction that writes `match_hit`, so the deliveries are
  written in that same transaction: both commit or neither does. A delivery cannot exist for a
  rolled-back match, and a crash cannot leave a committed match without deliveries. An
  after-commit listener would open that gap and would also store the event, with every matched
  student's row, in the event registry. The rows are created from the stored matches, not from
  the event, and the foreign key is the last guard. The price: a failure while queueing fails the
  whole email (it is marked FAILED) instead of keeping matches nobody is told about.
- **Claiming is `SELECT ... FOR UPDATE SKIP LOCKED` plus a lease.** The claim transaction also
  pushes `next_attempt_at` 5 minutes ahead (`claim-timeout`) and commits, so alerts are sent
  without a database transaction held open, and a row whose instance died comes back by itself.
  A batch that runs past half the lease puts its remaining rows back. Removing the lease makes
  the concurrency test fail, which is how the test was checked.
- **Delivery is at-least-once in one narrow case:** the process dies after a channel accepted the
  alert and before SENT is recorded. Everything else is exactly-once
  (`unique (match_id, channel_id)`, the claim, ShedLock on top).
- **Backoff follows the task: 6 attempts, waits of 1, 2, 4, 8, 16 minutes.** ARCHITECTURE.md
  lists a sixth wait of 32 minutes, which six attempts never reach; the document was left as is.
- **`DeliveryDead` is published in the transaction that marks the row DEAD**, so an after-commit
  listener (the Discord admin alert, T10) will see it. It carries ids, the channel type and a
  reason code only.
- **Failure reasons never contain content or addresses**: `HTTP 503`, `SES
  MessageRejectedException`, `IO ResourceAccessException`. Exception messages are dropped because
  ntfy's contain the topic and SES's the recipient.
- **A sending rate limit waits instead of failing.** Each channel has a limiter (ntfy 2 per
  second, SES 1 per second, the sandbox limit); a send waits up to 10 s for its turn, so a big
  shortlist is paced rather than burning retry attempts.
- **SES: only a fault of the message is permanent** (`MessageRejected`, `BadRequest`,
  `NotFound`). Throttling, 5xx, network and credential problems, and refusals about the account
  (403, sending paused) are retried, so a wrong key can be fixed within the retry window. The
  first version made 403 permanent; the local run without credentials showed every email going
  DEAD at once, which is why it changed.
- **ntfy follows the task literally:** 429 and 5xx retry, any other non-2xx is DEAD. A title with
  non-ASCII characters is sent RFC 2047 encoded. An address that is not a valid topic name is
  refused before it can become part of a URL.
- **`neobuddy.channels.email.provider` chooses the email channel** (`ses` or `log`), not the
  profile alone: `local` defaults to `log`, and `NEOBUDDY_EMAIL_PROVIDER=ses` turns real email on
  locally, which the "done when" needs. With `ses`, a missing `NEOBUDDY_SES_FROM` stops startup.
- **Channel types nothing can send to yet (Discord) get no delivery rows**, rather than rows that
  would end DEAD.
- **A channel switched off or unverified after queueing is not sent to**; the delivery ends DEAD
  with `CHANNEL_UNAVAILABLE`.
- **Email codes are stored as SHA-256 of user id + address + code.** The user id keeps two users'
  equal codes from colliding on `link_code`'s primary key; the address means a code cannot
  verify an address it was not sent to. Every confirmation counts as an attempt under a row
  lock; after the fifth wrong one even the right code is refused.
- **A push channel is verified as soon as it is created**, because the server invents the topic.
  Creating it again replaces the topic.
- **Module boundaries:** notification reads matches, emails and channels through new read APIs
  of their owning modules instead of joining their tables. `ChannelVerificationService` stays in
  subscriber and sends its email through a port, since subscriber cannot depend on notification.
  These are additions to public interfaces (`StoredMatches`, `ProcessedEmails.find`,
  `ChannelDirectory`); nothing existing changed.

### Not verified

- **Real ntfy.** ntfy.sh is not reachable from the build environment. In particular, that ntfy
  decodes an RFC 2047 `Title` header is taken from its documentation, not observed.
- **Real SES.** The request shape and error mapping were tested against a mocked client only.
- **The 3-minute end-to-end check** with real Gmail, phone and inbox.
- Testcontainers mode and CI remain unexercised (see T1).

### Follow-ups

- **ntfy.sh's free tier** allows roughly 250 messages a day per sender IP; a batch-wide shortlist
  will exceed it. Before opening to the batch: a self-hosted ntfy or a paid plan.
- **The student's row leaves NeoBuddy** in the push body (to ntfy) and the email (to SES). That is
  the product, but the consent notice in T9 should say so.
- `DeliveryDead` has no listener yet (admin alert on Discord, T10); DEAD rows are visible only in
  the table until the admin console (T11). No metrics yet (T11).
- With the `log` provider a one-time code is neither sent nor logged, so email verification
  cannot be completed locally without SES; T9 has to decide how to develop against it.
- Rate limiting of code requests per user and IP belongs to the T9 endpoints.
- A replayed email queues a delivery for a channel the student verified after the first run;
  acceptable for restarts, worth a thought for the admin reprocess endpoint (T11).
- Expired `link_code` rows are only removed when their user asks again; a periodic purge can join
  the 180-day snippet purge.
