# Claude Code build playbook

Thirteen tasks take NeoBuddy from an empty repo to launch at your college. T0–T7 make it work for you, T8 runs it 24/7 on AWS, and T9–T12 open it to your batch. Run each task in its own fresh Claude Code session, in order.

## Task tracker

| # | Task | Owner | Outcome | Status |
| --- | --- | --- | --- | --- |
| T0 | Repo, CLAUDE.md, cloud accounts | You | Claude Code has the context it needs | Not started |
| T1 | Project scaffold | Claude Code | Runnable skeleton, schema, CI | Not started |
| T2 | Extraction engine | Claude Code | Email + attachments become text chunks | Not started |
| T3 | CsvExtractor + Tokenizer | You | Your own code in the core engine | Not started |
| T4 | Classification and matching | Claude Code | Email to matches, golden-tested | Not started |
| T5 | Subscribers, encryption, bootstrap | Claude Code | Your college and you seeded | Not started |
| T6 | Gmail ingestion | Claude Code | Your inbox feeds the pipeline | Not started |
| T7 | Delivery, push and email | Claude Code | **Works for you locally** | Not started |
| T8 | AWS infra and deployment | Claude Code | **Runs 24/7 for you** | Not started |
| T9 | Web sign-in and onboarding | Claude Code | Classmates can sign up | Not started |
| T10 | Discord bot | Claude Code | Alerts by DM, plus a community server | Not started |
| T11 | Admin console and observability | Claude Code | Run many colleges from a browser | Not started |
| T12 | Launch hardening | Claude Code + you | **Open to your batch** | Not started |

**Assumed** (change `CLAUDE.md` before T1 if you disagree): Java 21, Gradle Kotlin DSL, Thymeleaf + htmx for the UI, AWS ap-south-1.

## How to run every task

1. Branch: `git switch -c t1-scaffold`.
2. Start fresh: run `claude` in the repo root, or `/clear` in an open session. One task per session keeps the context clean.
3. Enter plan mode: press Shift+Tab until the status bar shows plan mode, or start with `claude --permission-mode plan` ([docs](https://code.claude.com/docs/en/common-workflows.md)). Paste the task prompt.
4. Review the plan as the human in the loop: check classes, module boundaries and tests against the design, and push back before approving.
5. Let it implement. Read the diff and run `./gradlew build` yourself.
6. Learning step: ask "Explain the three most important design decisions in this task and what could break."
7. Commit, open a PR, wait for green CI, merge, and set Status to Done.

## T0 · Setup (you)

- [ ] Create a private GitHub repo `neobuddy`, clone it, and install Claude Code.
- [ ] Export the Architecture tab as Markdown to `docs/ARCHITECTURE.md`, create an empty `docs/PROGRESS.md`, add the `CLAUDE.md` below, and commit.
- [ ] Google Cloud project `neobuddy-ingest`: enable the Gmail API, create an OAuth client of type Desktop app, set the consent screen to External and publish it to production. In Testing mode, refresh tokens expire after 7 days. You will click through an "unverified app" warning once when you authorise your own inbox.
- [ ] AWS account: MFA on root, an admin IAM user for Terraform, and a monthly budget alert.
- [ ] Install the ntfy app on your phone.
- [ ] Buy a domain for the site and the SES sender (needed by T8).
- [ ] Collect your college email domain, the placement-cell sender addresses, the NeoID format, and 3–5 real shortlists to study their layout. Keep the shortlists out of the repo; test fixtures stay synthetic.

**CLAUDE.md** (repo root; keep it short, since Claude Code reads it every session)

```markdown
# NeoBuddy

Alerts students when their NeoID appears in placement shortlist or offer emails.
Design (source of truth): docs/ARCHITECTURE.md. Progress log: docs/PROGRESS.md.

## Stack
Java 21, Spring Boot (latest stable), Spring Modulith, Gradle Kotlin DSL, PostgreSQL + Flyway,
Thymeleaf + htmx, JUnit 5 + Testcontainers, AWS (EC2, SES, SSM, S3) via Terraform.

## Commands
- Build + all tests: ./gradlew build
- Run locally: docker compose up -d db && ./gradlew bootRun --args='--spring.profiles.active=local'
- Format: ./gradlew spotlessApply

## Rules
- Modules: ingestion, processing, subscriber, notification, web, discord, shared. Cross-module access
  only through a module's public API or events; ModularityTests must pass.
- Multi-tenant: tenant data carries college_id; every query, index and match is scoped by college.
- Never log email bodies, attachment content, other students' identifiers, tokens or secrets.
  Use the RFC Message-ID as the correlation id.
- Secrets come from env / SSM only. Never commit secrets, real shortlists or real student data.
- Schema changes only via new Flyway migrations; never edit an applied migration.
- Constructor injection, records for DTOs and events, @ConfigurationProperties for config.
- Every change ships with tests. ./gradlew build must pass before a task is called done.
- Ask before: adding a dependency not named in docs/ARCHITECTURE.md, changing the schema beyond
  the task, changing a public interface, or creating paid cloud resources.

## Finishing a task
Update docs/PROGRESS.md (what was built, decisions taken, follow-ups) and propose a
Conventional Commit message. Do not start the next task.
```

## T1 · Project scaffold

Depends on T0. Done when `./gradlew build` passes locally and in CI and the app starts with the local profile.

```markdown
Task T1 - Project scaffold.
Read CLAUDE.md and docs/ARCHITECTURE.md first (Low-level design, Data model, Tech stack).

Goal: an empty but runnable NeoBuddy service with the full module skeleton, the database schema
and CI, so later tasks only add behaviour.

Build:
1. Single-module Gradle (Kotlin DSL) Spring Boot app, Java 21 toolchain, group com.neobuddy.
   Use the latest stable Spring Boot and a compatible Spring Modulith BOM. Pin every version in
   gradle/libs.versions.toml.
2. Dependencies now: web, data-jpa, validation, security, oauth2-client, actuator, thymeleaf,
   flyway + postgresql driver, spring-modulith (core, jpa, events), shedlock (spring + jdbc provider),
   testcontainers (postgresql, junit-jupiter), spotless with google-java-format.
3. Packages com.neobuddy.{ingestion, processing, subscriber, notification, web, discord, shared},
   each with package-info.java. Mark shared as a shared module.
4. Flyway V1__init.sql: exactly the schema in the Data model section (college table and college_id
   columns included), plus the shedlock table. For Spring Modulith's event_publication table, pick
   either a Flyway migration or Modulith's schema initialisation and explain the choice.
5. JPA entity + repository for College only; other entities come in later tasks.
6. docker-compose.yml with Postgres 16 for local dev. application.yml + application-local.yml.
   Secrets only via environment variables.
7. Tests: ModularityTests (ApplicationModules.verify() plus Documenter output to build/),
   and a @SpringBootTest on Testcontainers Postgres asserting Flyway applied V1.
8. .github/workflows/ci.yml: JDK 21, Gradle cache, ./gradlew build on push and pull_request.
9. README.md: two lines on what NeoBuddy is, how to run locally, how to run tests.

Out of scope: business logic, UI, Gmail, notification channels.
Done when: ./gradlew build is green locally and in CI; with the local profile the app starts and
/actuator/health is UP.
Finish: update docs/PROGRESS.md and propose a commit message.
```

## T2 · Extraction engine

Depends on T1. Done when every extractor has happy-path, empty, corrupt and oversized tests, and the build is green with the T3 tests disabled.

```markdown
Task T2 - Extraction engine (processing module).
Read CLAUDE.md and docs/ARCHITECTURE.md (Low-level design: ContentExtractor, TextChunk,
ExtractorRegistry; Non-functional design: Attachment safety).

Goal: turn an email body and its attachments into TextChunks (one per spreadsheet row or text
line), with no Gmail involved.

Build in com.neobuddy.processing.extract:
1. ContentExtractor interface, TextChunk(source, location, text) record, DetectedType,
   AttachmentRef(filename, declaredMimeType, sizeBytes), ExtractionException.
2. TypeDetector using Apache Tika core (magic bytes + filename). Never trust the extension alone.
3. ExtractorRegistry: injected List<ContentExtractor>, picks the one that supports the detected
   type; unknown types are skipped with a WARN that logs the filename only.
4. Extractors:
   - ExcelExtractor: .xlsx and .xls via WorkbookFactory; excel-streaming-reader for .xlsx over
     5 MB; DataFormatter so numeric registration numbers keep their displayed text; all sheets;
     one chunk per non-empty row, cells joined with " | ", location "Sheet1!R42".
   - PdfExtractor: PDFBox, one chunk per line, location "p3:L17".
   - DocxExtractor: POI XWPF, paragraphs and table rows.
   - HtmlBodyExtractor: jsoup; each <tr> is one chunk, other text one chunk per block element.
   - PlainTextExtractor: one chunk per line.
5. Safety: max attachment size from config (default 25 MB), skipped with WARN above it; keep POI's
   ZipSecureFile defaults; SafeExtractionRunner runs each file on a bounded executor with a 30 s
   timeout so one bad file fails alone.
6. Hand-off for T3 (Vaibhav writes these himself, so do NOT implement them):
   - CsvExtractor (processing.extract) and Tokenizer (processing.match) as classes whose methods
     throw UnsupportedOperationException, with full javadoc contracts.
   - Complete JUnit tests for both, annotated @Disabled("T3 - Vaibhav implements").
   - Tokenizer contract: (a) email addresses extracted whole and lower-cased; (b) text split on any
     non-alphanumeric character into upper-cased tokens of length >= 4; (c) additionally, each
     " | "-separated cell with spaces and hyphens removed, upper-cased, emitted if it is 4-20
     alphanumerics (so "n1b7 j5v6" yields N1B7J5V6). Output is a Set; order irrelevant.
   - CsvExtractor cases: quoted fields containing commas, UTF-8 BOM, semicolon delimiter detection,
     blank lines, CRLF, a 50,000-row file; one chunk per row joined with " | ", location "R12".
7. Fixtures: generate synthetic files in a test helper (fake names, fake NeoIDs such as Z9X8C7V6):
   a 2-sheet .xlsx with a header row and a numeric reg-no column, an .xls, a PDF with a table,
   a .docx with a table, an HTML email with a table. No real student data anywhere.

Out of scope: matching, classification, Gmail.
Done when: each extractor has tests for happy path, empty file, corrupt file and oversized file;
./gradlew build is green with the T3 tests disabled.
Finish: update docs/PROGRESS.md and propose a commit message.
```

## T3 · CsvExtractor + Tokenizer (you)

Depends on T2. You write this code yourself; Claude Code only answers concept questions and reviews. Done when the formerly disabled tests pass and the review findings are fixed.

- [ ] Remove the `@Disabled` annotations and read the javadoc contracts and tests first.
- [ ] Implement `CsvExtractor` with Apache Commons CSV: detect comma vs semicolon from the first line, strip the BOM, skip blank rows.
- [ ] Implement `Tokenizer`: a precompiled email regex, a split on non-alphanumerics, and the per-cell normalisation rule. Return a `Set<String>`.
- [ ] Make the 50,000-row test pass in well under a second; avoid regex compilation inside loops.
- [ ] Allowed: asking Claude Code "how does CSVFormat detect..." or "why is this test failing". Not allowed: asking it to write the code.

Review prompt, once your tests are green:

```markdown
Task T3 review. I implemented CsvExtractor and Tokenizer myself. Do NOT rewrite or edit them.
Review them like a senior engineer:
1. Correctness against the javadoc contracts and tests.
2. Edge cases the tests miss; propose new test cases as test names + one-line descriptions.
3. Performance on a 50,000-row CSV and a 3,000-row shortlist chunk set.
4. Readability and naming.
Return a numbered list: severity (high/medium/low), the problem, and a hint toward the fix,
not the fixed code. I will fix them and come back for a second pass.
```

## T4 · Classification and matching

Depends on T3. Done when all eight golden cases pass and a 50,000-row sheet against 10,000 identifiers processes in under 5 seconds.

```markdown
Task T4 - Classification and matching (processing module).
Read CLAUDE.md and docs/ARCHITECTURE.md (Core flows B steps 4-7; Low-level design: classes table,
Key interfaces, Matching rules).

Goal: a pure, Gmail-free ProcessingService that turns (college, RawEmail, attachments) into a
classification plus matches, proven by golden tests.

Build:
1. RawEmail record. Place it where Spring Modulith allows both ingestion and processing to use it
   (e.g. ingestion's public API package) and explain the choice.
2. EventType, Classification, EmailClassifier and KeywordEmailClassifier. Rules come from YAML
   (neobuddy.classifier.rules) with optional per-college overrides; OFFER outranks SHORTLIST.
3. CompanyNameResolver: subject patterns such as "<Company> - Shortlist" and "Shortlisted
   candidates for <Company>", a configurable known-recruiter list, fallback to the cleaned subject.
4. IdentifierIndex + InMemoryIdentifierIndex: Map<collegeId, Map<token, Set<IdentifierRef>>>,
   swapped atomically on rebuild (AtomicReference). Loads through an IdentifierSource port that the
   subscriber module implements in T5 (use a fake in tests). Rebuilds on IdentifiersChanged and
   every 5 minutes.
5. Matcher: Tokenizer (T3) -> index lookup scoped to the email's college -> MatchHit(userId,
   identifierId, eventType, contextSnippet). The snippet is ONLY the matched chunk, max 300 chars.
   At most one hit per user per email.
6. ProcessingService: chunks (T2 registry) -> classify -> match -> ProcessingResult; publishes
   MatchesFound(collegeId, rfcMessageId, classification, hits). Persistence comes in T6 through a
   ProcessingResultSink port.
7. Golden tests with synthetic emails:
   a) .xlsx shortlist, 500 rows, target NeoID once -> 1 hit with that row as snippet
   b) inline HTML table shortlist -> 1 hit
   c) "Congratulations" offer mail listing names and NeoIDs in the body -> OFFER + hit
   d) unrelated mail -> no hit, type OTHER
   e) NeoID typed as "N1B7 J5V6" in a cell -> hit
   f) near miss "N1B7J5V6X" -> NO hit
   g) same user listed by NeoID and by reg no -> exactly 1 hit
   h) same NeoID registered in another college -> no cross-college hit
8. Performance test: 50,000-row sheet x 10,000 indexed identifiers under 5 s on CI (generous bound).

Out of scope: database persistence, Gmail, notifications.
Done when: golden and performance tests pass; ./gradlew build is green.
Finish: update docs/PROGRESS.md and propose a commit message.
```

## T5 · Subscribers, encryption, bootstrap

Depends on T4. Done when the bootstrap seeds your college and you idempotently, and the constraint and cascade tests pass.

```markdown
Task T5 - Subscriber module, field encryption and v1 bootstrap.
Read CLAUDE.md and docs/ARCHITECTURE.md (Data model; Non-functional design: Security, Privacy;
Low-level design: subscriber rows; Deployment: Configuration).

Goal: users, identifiers and channels persisted per college, encryption at rest in place, and a
bootstrap that seeds my college and me so NeoBuddy works for me before any UI exists.

Build:
1. shared.crypto.FieldEncryptor: AES-256-GCM, random 96-bit IV per value, 32-byte key (base64)
   from env NEOBUDDY_ENCRYPTION_KEY, a leading key-id byte for future rotation; a JPA
   AttributeConverter for encrypted columns. Unit tests incl. tampered ciphertext and wrong key.
2. Entities + repositories: AppUser, Identifier, Channel, LinkCode. Value types: IdentifierType,
   ChannelType, ChannelAddress, NeoId.
3. IdentifierNormalizer (trim, upper-case, strip spaces and hyphens; emails lower-cased) and
   per-college validation: NEO_ID against college.neo_id_pattern; COLLEGE_EMAIL must end with one
   of college.allowed_email_domains.
4. SubscriberService (module public API): registerFromOidc(claims) resolving the college by email
   domain (reject if none or not ACTIVE/PILOT), addIdentifier (IdentifierAlreadyClaimedException
   on conflict), removeIdentifier, addChannel (unverified), markVerified, pause/resume,
   deleteAccount (cascade). Publishes IdentifiersChanged(collegeId).
5. Implement processing's IdentifierSource port here; processing must not depend on subscriber
   internals (ModularityTests must stay green).
6. BootstrapRunner (ApplicationRunner, idempotent upsert) from neobuddy.bootstrap.*: the college
   (slug, name, allowed-email-domains, sender-allowlist, neo-id-pattern) and the admin (email,
   neo-id, optional reg-no, optional ntfy-topic, optional personal-email). Creates the college,
   the admin user with role ADMIN and consented_at = now, identifiers, and pre-verified channels.
   Values come from env in prod; never hard-code real values.
7. Testcontainers tests: same NeoID twice in one college rejected; same NeoID in another college
   allowed; deleteAccount cascades to identifiers, channels, match_hit, delivery; bootstrap run
   twice changes nothing.

Out of scope: web endpoints, sending OTPs, Discord.
Done when: tests pass; starting locally with bootstrap env vars creates my college and me once.
Finish: update docs/PROGRESS.md and propose a commit message.
```

## T6 · Gmail ingestion

Depends on T5 and the `neobuddy-ingest` Google Cloud project from T0. Done when a test email with a synthetic sheet containing your NeoID creates a `match_hit` row locally.

```markdown
Task T6 - Gmail ingestion (ingestion module).
Read CLAUDE.md and docs/ARCHITECTURE.md (Core flows B steps 1-3 and 8, Core flows C;
Non-functional design: Reliability).

Prereq (done by me): Google Cloud project neobuddy-ingest with the Gmail API enabled, an OAuth
client of type Desktop app, consent screen External and published to production.

Goal: every new allow-listed email in my inbox is fetched once, deduped by Message-ID, persisted
in processed_email, run through ProcessingService, and its matches persisted.

Build:
1. SourceAuthorizer: a separate main class exposed as ./gradlew authorizeSource. It runs Google's
   installed-app loopback OAuth flow for scope gmail.readonly with offline access and prints the
   refresh token once to stdout (never to logs). Document it in README.
2. Bootstrap: neobuddy.bootstrap.source.{email, refresh-token} -> encrypted upsert into
   mail_source for the bootstrap college; initial last_history_id = current historyId from
   users.getProfile, so ingestion starts from now.
3. MailSource port + GmailMailSource (google-api-services-gmail):
   - changesSince(cursor): history.list with historyTypes=messageAdded, full paging.
   - load(ref): messages.get format=FULL; read the Message-ID header; walk MIME recursively for
     text/plain, text/html and attachments (including nested multipart and inline parts with
     filenames).
   - openAttachment: attachments.get, base64url decode, streamed where possible.
   - 404 on a stale historyId -> fall back to messages.list q="newer_than:7d", rely on dedupe.
   - invalid_grant -> mark the source AUTH_EXPIRED and publish SourceAuthExpired.
4. MailPoller: @Scheduled(fixedDelayString from config, default 2 min) + @SchedulerLock. Per
   source: page changes; keep only senders matching college.sender_allowlist (exact address or
   @domain); INSERT processed_email ... ON CONFLICT (rfc_message_id) DO NOTHING; only if inserted,
   load and publish EmailIngested. Advance the cursor only after the page's transaction commits.
5. @ApplicationModuleListener on EmailIngested -> ProcessingService -> implement the
   ProcessingResultSink: persist match_hit rows (unique user + email) and update processed_email
   (status, event_type, company, processed_at). On failure set FAILED with the exception class only.
6. Resilience4j retry with backoff for Gmail 429 and 5xx.
7. Tests: WireMock-backed Gmail API tests for history paging, stale-history 404 fallback,
   invalid_grant, the same Message-ID arriving via two sources, and the sender filter; plus an
   integration test from EmailIngested to match_hit rows using a synthetic .xlsx.

Out of scope: notifications, the web flow for connecting sources (T11), Pub/Sub push.
Done when: running locally with my refresh token, an email I send myself from an address
temporarily added to the allowlist, carrying a synthetic sheet with my NeoID, creates a
match_hit row within one poll cycle.
Finish: update docs/PROGRESS.md and propose a commit message.
```

## T7 · Delivery, push and email

Depends on T6. Milestone: after this task NeoBuddy works end to end for you locally. Done when the T6 test email produces a phone push and an email within 3 minutes.

```markdown
Task T7 - Notification core: outbox, dispatcher, ntfy push and SES email.
Read CLAUDE.md and docs/ARCHITECTURE.md (Core flows B step 9; Low-level design: notification
rows; Non-functional design: Reliability).

Goal: every match_hit fans out to its user's verified, enabled channels exactly once, with
retries. After this task NeoBuddy works end to end for me.

Build:
1. DeliveryService: listens for match_hit persistence (or MatchesFound; choose and explain so no
   delivery exists for an uncommitted match) and inserts one delivery row per verified, enabled
   channel (unique match + channel).
2. DeliveryDispatcher: @Scheduled every 10 s + ShedLock; claims up to 50 due rows with a native
   SELECT ... FOR UPDATE SKIP LOCKED; sends via the NotificationChannel for the row's type.
   DeliveryResult handling: SUCCESS -> SENT; RETRYABLE -> attempts + 1, next_attempt_at = now +
   base * 2^(attempts-1) (base 1 min, max 6 attempts from config), DEAD after the last attempt;
   PERMANENT -> DEAD at once. Publish DeliveryDead.
3. AlertRenderer, per channel: headline ("Shortlisted: <Company>", "Offer: <Company>", or
   "You're mentioned: <subject>"), the user's own row snippet, received time in IST, and a link
   to search their own mailbox: https://mail.google.com/mail/u/0/#search/rfc822msgid:<id>.
4. NtfyPushChannel: Spring RestClient POST {base-url}/{topic} with Title, Tags, Priority (high for
   OFFER); 429/5xx RETRYABLE, other 4xx PERMANENT; Resilience4j rate limiter.
5. SesEmailChannel: AWS SDK v2 SesV2Client, from-address from config, text + simple HTML parts;
   throttling RETRYABLE, MessageRejected PERMANENT. Local profile uses LoggingEmailChannel.
6. ChannelVerificationService (service layer only; endpoints come in T9): ntfy topic generation
   ("nb-" + 128 random bits, base32) and email OTP (6 digits, SHA-256 hashed in link_code,
   10-minute expiry, 5 attempts).
7. Tests: dispatcher state machine with WireMock ntfy and a mocked SES client; backoff schedule;
   two concurrent dispatchers never double-send; DEAD after max attempts; end to end from
   EmailIngested to exactly one ntfy POST.

Out of scope: web endpoints, Discord, admin UI.
Done when: locally, with my bootstrap ntfy topic and SES-verified personal email, the T6 manual
test produces a phone push and an email within 3 minutes.
Finish: update docs/PROGRESS.md and propose a commit message.
```

## T8 · AWS infra and deployment

Depends on T7, the AWS account and the domain from T0. Milestone: NeoBuddy runs 24/7 for you. Done when a merge to main deploys automatically and a test email reaches your phone from the cloud.

```markdown
Task T8 - AWS infrastructure and continuous deployment (v0, runs 24/7 for me).
Read CLAUDE.md and docs/ARCHITECTURE.md (Deployment and infrastructure; Scaling, stage 1).

Goal: NeoBuddy running in AWS ap-south-1, deployed from main automatically, secrets in SSM,
nightly database backups, everything reproducible with Terraform.

Build:
1. Container image with Jib: Temurin 21 JRE base, non-root user, linux/arm64 and amd64,
   container-aware JVM memory flags.
2. deploy/docker-compose.prod.yml: app, postgres:16 (named volume on EBS), caddy (reverse proxy,
   automatic HTTPS for the domain). The app reads an env file rendered from SSM at deploy time.
3. infra/terraform (S3 backend + DynamoDB lock; document the one-time state bootstrap):
   - ECR repository with an untagged-image lifecycle rule
   - EC2 t4g.small, Amazon Linux 2023, Docker + compose plugin via user-data, Elastic IP
   - security group: 80/443 in only; no SSH, use SSM Session Manager
   - instance role: ECR pull, ssm:GetParametersByPath on /neobuddy/prod/*, ses:SendEmail,
     CloudWatch Logs, s3:PutObject on the backup bucket
   - S3 backup bucket: versioning, SSE, 30-day lifecycle
   - CloudWatch log group with 30-day retention
   - GitHub OIDC provider + deploy role trusted only for this repo's main branch
   - SES domain identity with DKIM records output for DNS
4. deploy/render-env.sh (runs on the instance): pulls /neobuddy/prod/* SecureStrings into an env
   file with mode 600.
5. Nightly backup: systemd timer running pg_dump | gzip to the S3 bucket.
6. .github/workflows/deploy.yml: after CI passes on main, assume the role via OIDC, Jib-push to
   ECR tagged with the git SHA, then SSM send-command: render env, docker compose pull && up -d,
   poll /actuator/health until UP or fail the job.
7. deploy/README.md runbook: first-time setup, deploy, rollback to a previous SHA tag, restore
   from backup, and the SSM parameters to create by hand (encryption key, source refresh token,
   bootstrap values).

Ask me before: applying Terraform the first time, creating anything beyond the list above, and
picking DNS records for my domain.
Done when: terraform apply is clean; a merge to main deploys; https://<domain>/actuator/health
returns UP publicly without details; a test email reaches my phone from the cloud instance.
Finish: update docs/PROGRESS.md and propose a commit message.
```

## T9 · Web sign-in and onboarding

Depends on T8. Before starting, create a second Google Cloud project `neobuddy-login` with a Web application OAuth client using only openid, email and profile, published to production. Done when a classmate signs up on their phone, verifies push and email, and gets a test alert; anyone outside your college domain is refused.

```markdown
Task T9 - Web: Google sign-in, onboarding and the student dashboard (web module).
Read CLAUDE.md and docs/ARCHITECTURE.md (Core flows A; APIs: Student endpoints; Non-functional
design: Security, Privacy).

Prereq (done by me): project neobuddy-login, Web OAuth client with only openid/email/profile,
redirect URIs https://<domain>/login/oauth2/code/google and http://localhost:8080/login/oauth2/code/google.

Goal: any student whose Google email domain belongs to an ACTIVE or PILOT college can sign up,
add identifiers, verify channels, send a test alert and see their alert history. Everyone else is
refused politely and can request their college.

Build:
1. SecurityConfig: oauth2Login with Google; a custom OidcUserService that checks the hd claim when
   present plus the email domain against college.allowed_email_domains, then calls
   SubscriberService.registerFromOidc. Unknown domain -> /not-supported with a "request your
   college" form stored in a new college_request table (new Flyway migration). Session cookie
   Secure, HttpOnly, SameSite=Lax; CSRF on, with htmx sending the token via hx-headers. ADMIN role
   from app_user.role.
2. Pages (Thymeleaf + htmx, small hand-written CSS, mobile-first since most students use phones):
   landing (what it does, privacy summary), consent step (stores consented_at), onboarding wizard
   (NeoID, then optional reg no and college email, then channels), dashboard (identifiers,
   channels with status and a test button, last 20 alerts), settings (pause all, delete account
   with confirmation), privacy policy and terms (plain language: what is stored, 180-day
   retention, deletion).
3. REST endpoints exactly as in the APIs section under /api/v1/me, backing the htmx calls; RFC 9457
   Problem Details errors; Bean Validation.
4. Channel flows: email OTP send/verify through SES; ntfy topic creation with a QR code (ZXing) and
   subscribe steps; test alert sent through the normal delivery pipeline as a synthetic match
   flagged TEST that never counts in stats.
5. Rate limits (Bucket4j, in-memory for now): OTP send 3/hour/user, 5 verify attempts per code,
   test alert 5/hour/user.
6. Tests: MockMvc with oauth2Login() for every endpoint, including domain rejection, CSRF failure,
   409 identifier conflict and OTP lockout.

Out of scope: Discord linking (T10), admin pages (T11).
Done when: tests pass; deployed, a classmate signs up on a phone, verifies push and email, and
receives a test alert; an @gmail.com account is refused.
Finish: update docs/PROGRESS.md and propose a commit message.
```

## T10 · Discord bot

Depends on T9. Before starting, create the Discord application and bot in the Developer Portal, a NeoBuddy server with #welcome and a private #ops channel, and invite the bot with the `bot` and `applications.commands` scopes. Done when `/link` works and a match arrives as a DM.

```markdown
Task T10 - Discord bot and DM channel (discord + notification modules).
Read CLAUDE.md and docs/ARCHITECTURE.md (Core flows A step 3; APIs: Discord slash commands).

Prereq (done by me): Discord application + bot, NeoBuddy server, bot invited with bot +
applications.commands scopes and minimal permissions. Token in SSM /neobuddy/prod/DISCORD_TOKEN;
guild id and #ops channel id in config.

Goal: a student links Discord with /link and receives alerts by DM; I get ops alerts in #ops.

Build:
1. JdaConfig: JDA with minimal gateway intents (no message content), graceful shutdown; the whole
   discord module is disabled when the token is absent (local runs and tests).
2. Guild slash commands, all replies ephemeral: /link code (validate the link_code, store the
   Discord user id encrypted as the channel address, mark verified), /status, /pause, /resume,
   /unlink.
3. Web: a "Connect Discord" button calling POST /api/v1/me/channels/discord/link-code, showing
   the code (valid 10 min) and the server invite link.
4. DiscordDmChannel: open a private channel and send an embed (title, company, snippet, time).
   Error 50007 "Cannot send messages to this user" -> PERMANENT, with a dashboard hint to allow
   DMs from server members; rate limits and 5xx -> RETRYABLE.
5. AdminNotifier: posts to #ops on SourceAuthExpired, more than 5 DeliveryDead events in an hour,
   and processing failures.
6. Tests: unit tests with mocked JDA objects for each command, link-code expiry and reuse, and
   DM error mapping. No live Discord in CI.

Out of scope: moderation features, any bot behaviour outside the commands above.
Done when: tests pass; deployed, I link my account with /link and a test alert arrives as a DM;
a forced SourceAuthExpired posts in #ops.
Finish: update docs/PROGRESS.md and propose a commit message.
```

## T11 · Admin console and observability

Depends on T10. Done when you can create a second (test) college and connect its inbox from the browser, see failures, and CloudWatch alarms fire on a forced outage.

```markdown
Task T11 - Admin console and observability.
Read CLAUDE.md and docs/ARCHITECTURE.md (APIs: Admin and ops endpoints; Core flows C;
Non-functional design: Observability; Scaling: Onboarding a new college).

Goal: I can run NeoBuddy for many colleges from a browser, see what fails, and get alerted.

Build:
1. Admin pages (ROLE_ADMIN only), Thymeleaf + htmx:
   - Colleges: create/edit name, slug, email domains, sender allowlist, NeoID pattern,
     per-college classifier rule overrides, status PILOT/ACTIVE/PAUSED.
   - College requests from T9, with a "create college" shortcut.
   - Sources: status, last sync, cursor; connect through a web OAuth flow (ingest project, Web
     client, gmail.readonly, access_type=offline, prompt=consent) via a single-use, 24-hour invite
     link so a campus lead from another college can connect their inbox; disconnect.
   - Recent emails: subject, sender, type, company, match count, status. Never bodies.
   - Dead deliveries: retry or discard. Reprocess an email by Message-ID.
2. Micrometer metrics exactly as named in Observability, tagged with college slug:
   emails_ingested_total, extraction_failures_total{type}, matches_total{event},
   delivery_latency_seconds (histogram, received -> sent), deliveries_dead_total{channel}.
3. Health indicators per source and per channel provider; public /actuator/health shows only the
   aggregate status.
4. Prod JSON logging (logstash-logback-encoder) with MDC rfcMessageId and collegeSlug. Add a test
   that runs the T4 golden pipeline and asserts the captured log output contains none of the
   fixture NeoIDs or names.
5. Terraform additions: CloudWatch alarms for the instance status check, an external health check
   on /actuator/health (pick the cheaper of a Route 53 health check or CloudWatch Synthetics and
   explain), and a metric filter + alarm on DeliveryDead log events; notify via SNS email.
6. Tests: every /admin and /api/v1/admin route returns 403 for ROLE_USER; the source invite link
   is single-use and expires.

Out of scope: billing, multi-admin roles per college.
Done when: tests pass; I create a test college, connect a second Gmail account through the invite
link, and its emails are processed; stopping the app container triggers the health alarm.
Finish: update docs/PROGRESS.md and propose a commit message.
```

## T12 · Launch hardening

Depends on T11. Milestone: open to your batch. Done when high and critical findings are fixed, load-test numbers are recorded, Postgres is on RDS, SES is out of the sandbox and `docs/LAUNCH.md` is fully ticked.

```markdown
Task T12 - Launch hardening for my college.
Read CLAUDE.md and all of docs/ARCHITECTURE.md.

Goal: NeoBuddy is safe and ready to open to my whole batch.

Do, in this order, pausing for my approval after steps 1, 2 and 4:
1. Security review: use a subagent that has not seen this session's work to review the codebase
   against the Security, Attachment safety and Privacy sections and the OWASP Top 10. Report
   findings by severity. Fix high and critical ones; record the rest in docs/PROGRESS.md.
2. Load test (profile "loadtest"): seed 10,000 synthetic users with 3 identifiers each in one
   college; process a 3,000-row shortlist containing 500 of them; measure time to all deliveries
   queued and dispatcher throughput against WireMock ntfy. Report the numbers and any bottleneck.
3. Restore drill: script that restores the latest S3 backup into a fresh Postgres container and
   runs smoke queries; document it in deploy/README.md.
4. Move Postgres to RDS via Terraform (db.t4g.micro, encrypted, 7-day automated backups, private
   subnet, security group from the app only) with a written cut-over plan (maintenance page,
   pg_dump -> restore, switch the env, verify). Ask me before applying.
5. SES: draft the production-access request (use case, opt-in, bounce and complaint handling) for
   me to submit; add an SNS webhook (POST /webhooks/ses) that marks bounced or complained email
   channels invalid.
6. Retention job: nightly purge of match_hit snippets older than 180 days; test it.
7. docs/LAUNCH.md checklist: privacy page live, Discord server rules and #welcome text, admin
   alerts tested, rollback tested, restore drill done, a "report a missed alert" link on the
   dashboard, an announcement message draft for the batch.

Done when: high/critical findings fixed, load numbers recorded, RDS live, SES request drafted,
retention job tested, LAUNCH.md complete except the items only I can do.
Finish: update docs/PROGRESS.md and propose a commit message.
```
