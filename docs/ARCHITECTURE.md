# NeoBuddy — HLD, LLD & Architecture

Nov 13, 2026 · @VT

## Overview

NeoBuddy reads placement-cell emails once, finds every subscribed student's NeoID (or other identifier) in the body and attachments, and alerts each matched student on Discord, email and mobile push within about 5 minutes.

**Product goal.** Every college that runs placements on NeoPAT has this problem, so NeoBuddy is built as a multi-college product from day one. v1 serves one college (Vaibhav's): it reads only his inbox and onboards only students from that college's email domain. Adding a college later is configuration plus one connected inbox, not a rewrite.

**Problem.** Shortlists arrive as Excel sheets or inline text with hundreds of rows. Students scan them by hand and miss results.

**v1 scope**

- Ingest from one or more shared college Gmail inboxes (Vaibhav's first).
- Parse the body (text and HTML) and attachments: .xlsx, .xls, .csv, .pdf, .docx.
- Classify each email: shortlist, offer/placed, or other.
- Match against all subscribers' identifiers: NeoID, registration number, college email.
- Notify on Discord (bot DM), email (AWS SES) and push (ntfy).
- Self-serve signup: Google sign-in limited to the college domain, add identifiers, link channels, send a test alert.

**Not in v1:** WhatsApp (paid per message), users connecting their own Gmail, OCR for images and scanned PDFs, Google Sheets links, a native mobile app.

**Success criteria:** alert within 5 minutes of the email landing; zero duplicate alerts per user per email; no missed match in a supported format.

## Key design decisions

The core choice is a shared inbox: shortlists go to the whole batch, so each email is parsed once and matched against N users, and no user ever grants mailbox access.

| # | Decision | Choice | Why | Trade-off |
| --- | --- | --- | --- | --- |
| 1 | Ingestion model | Shared college inbox(es), hybrid-ready | Parse once, match everyone; avoids Gmail's restricted scope per user (unverified apps are capped at 100 users) | Misses emails sent only to a subset nobody connected; fixed later by adding source inboxes or personal connect |
| 2 | Tenancy | Multi-tenant from day one: `college` table, `college_id` on tenant data, matching scoped per college; one college live in v1 | Adding another NeoPAT college is config + one inbox, no migration | A little more code and testing now |
| 3 | Architecture style | Modular monolith with Spring Modulith | One deployable, enforced module boundaries, can split along them into services later | Scales vertically only until split |
| 4 | Hosting | One Docker container on EC2 + PostgreSQL, Terraform-managed | Always on for the Discord gateway, scheduler and web UI | Small fixed monthly cost instead of Lambda's near-zero idle cost |
| 5 | Change detection | Gmail History API poll every 2 min (v1); Gmail watch + Pub/Sub push (later) | Polling is simple and reliable; history IDs make it incremental | Up to 2 min extra latency |
| 6 | Dedupe key | RFC 822 Message-ID header, not the Gmail message ID | The same email lands in several source inboxes with different Gmail IDs | None |
| 7 | Delivery | Transactional outbox + scheduled dispatcher | Survives crashes, retries per channel, full status trail | One more table |
| 8 | Matching | Multiple identifiers per user, in-memory token index per college | Unclear whether sheets always carry NeoID | Users fill a few more fields |
| 9 | Channels | Discord bot (JDA), email (SES), push (ntfy) behind one `NotificationChannel` interface | Free or near-free; the Discord server doubles as a community | WhatsApp deferred |
| 10 | Login | Google OpenID Connect; email domain must belong to an onboarded college | Only students of supported colleges sign up; basic scopes need no Google verification | Requires a college Google account |
| 11 | Privacy | Store no sheet content, only each user's own matched row | Sheets hold other students' data | Reprocessing re-fetches from Gmail |

## High-level architecture

NeoBuddy is one Spring Boot app with six modules. Mail flows left to right through ingestion, processing and notification; students and the Discord bot feed the subscriber module from above.

&#91;embedded content: NeoBuddy architecture · 6 modules, 3 alert channels\]

Processing (highlighted) is the core: it turns each email into text chunks and looks every token up in the identifier index. Only the outbound channels and the Gmail source touch the outside world, each behind its own interface.

## Core flows

Three flows carry the product: a student onboards once, every new email runs the pipeline once, and each match fans out to that student's verified channels.

**A. Onboarding (about 2 minutes for the student)**

1. Student opens the site and signs in with Google. The server rejects any account outside the college domain (the `hd` claim plus an email-suffix check).
2. Student adds identifiers: NeoID (required), plus registration number and college email if they want. Values are normalised; a value already claimed by another account returns 409.
3. Student links channels:
   - **Discord:** the site shows a one-time code and the server invite. In the server the student runs `/link code:ABC123`; the bot stores their Discord user ID and DMs a confirmation.
   - **Email:** the student enters a personal address and confirms a 6-digit code sent via SES.
   - **Push:** the server generates an unguessable ntfy topic and shows a QR code. The student subscribes in the ntfy app.
4. Student taps "Send test alert" and sees it arrive on each channel.

**B. Ingestion to notification (every 2 minutes)**

1. `MailPoller` (ShedLock, so only one instance polls) calls Gmail `history.list` from each source's saved `historyId`.
2. For each new message from an allow-listed sender, it reads the RFC 822 Message-ID and inserts into `processed_email`. A duplicate key means another inbox already brought it, so the message is skipped.
3. The full message and attachments are fetched, and an `EmailIngested` event is published (persisted by Spring Modulith's event registry).
4. `ProcessingService` extracts text chunks from the body and each attachment. A spreadsheet row becomes one chunk, so a match carries the student's own row (round, slot, venue).
5. `EmailClassifier` tags it SHORTLIST, OFFER or OTHER and guesses the company.
6. `Matcher` tokenises each chunk and looks the tokens up in the in-memory identifier index.
7. In one transaction it writes `match_hit` rows (unique per user and email) and one `delivery` row per enabled, verified channel.
8. The source cursor advances only after the batch commits, so a crash replays the batch safely.
9. `DeliveryDispatcher` claims due rows (`FOR UPDATE SKIP LOCKED`) and sends through the matching `NotificationChannel`. Failures retry with backoff 1, 2, 4, 8, 16, 32 minutes; after 6 attempts a row goes DEAD and the admin is alerted on Discord.

**C. Source inbox connection (admin only)**

1. Admin signs in and opens Sources, then grants `gmail.readonly` through Google OAuth.
2. The refresh token is encrypted (AES-256-GCM) and saved with the current `historyId`, so ingestion starts from now, not from old mail.
3. If Google revokes the token, the source flips to AUTH\_EXPIRED and the admin gets a Discord alert to reconnect.

## Low-level design

The app is six Spring Modulith modules plus a shared kernel; they talk through interfaces and events. Every format and channel is a strategy, so adding WhatsApp or OCR later means adding one class, not editing the pipeline.

**Package layout** (`com.neobuddy`)

```
ingestion/      MailSource (port), GmailMailSource, MailPoller, SourceService, EmailIngested event
processing/     ProcessingService, extract/*, classify/*, match/*, MatchesFound event
subscriber/     User, Identifier, Channel entities; SubscriberService, ChannelVerificationService, LinkCodeService
notification/   DeliveryService, DeliveryDispatcher, channel/* (DiscordDmChannel, SesEmailChannel, NtfyPushChannel), AlertRenderer
web/            REST controllers, Thymeleaf views, SecurityConfig, CollegeDomainOidcUserService
discord/        JdaConfig, SlashCommandListener (/link, /status, /pause, /resume, /unlink)
shared/         FieldEncryptor (AES-GCM), NeoBuddyProperties (@ConfigurationProperties), Clock, error types
```

**Classes and responsibilities**

| Module | Class | Responsibility |
| --- | --- | --- |
| ingestion | `GmailMailSource` | Implements `MailSource` with the Gmail API: history paging, message and attachment fetch, MIME walking |
| ingestion | `MailPoller` | `@Scheduled` every 2 min with `@SchedulerLock`; loops active sources and advances cursors after commit |
| ingestion | `SourceService` | Connects, refreshes and disables source inboxes; detects AUTH\_EXPIRED |
| processing | `ExtractorRegistry` | Holds every `ContentExtractor` bean; picks one by detected MIME type (Apache Tika), not file extension |
| processing | `ExcelExtractor` | .xlsx and .xls via POI `WorkbookFactory`; streaming reader above 5 MB; `DataFormatter` keeps cells as displayed text; one chunk per row |
| processing | `CsvExtractor`, `PdfExtractor`, `DocxExtractor`, `HtmlBodyExtractor`, `PlainTextExtractor` | One format each; all emit `TextChunk` |
| processing | `KeywordEmailClassifier` | Rule-based SHORTLIST / OFFER / OTHER from subject and body; rules in YAML |
| processing | `CompanyNameResolver` | Pulls the company from subject patterns and a known-recruiter list; falls back to the subject |
| processing | `Tokenizer` | Splits chunks into upper-case alphanumeric tokens and email addresses |
| processing | `InMemoryIdentifierIndex` | `Map<String, Set<IdentifierRef>>` rebuilt on `IdentifiersChanged` events and every 5 min |
| processing | `Matcher` | Tokens to index hits to `MatchHit` with the user's row as the snippet |
| subscriber | `SubscriberService` | Users, identifier claims, consent, account deletion |
| subscriber | `ChannelVerificationService` | Email OTP (hashed, 10-min expiry, 5 tries), Discord link codes, ntfy topic generation |
| notification | `DeliveryService` | Listens to `MatchesFound`; writes one `delivery` row per verified channel |
| notification | `DeliveryDispatcher` | Claims due rows with `SKIP LOCKED`; sends; applies backoff; marks SENT or DEAD |
| notification | `AlertRenderer` | Builds channel-specific text: company, event type, the user's row, a Gmail deep link |

**Key interfaces**

```java
public interface MailSource {
    SourceId id();
    Page<MailRef> changesSince(SyncCursor cursor);          // Gmail history.list
    RawEmail load(MailRef ref);                             // users.messages.get(format=FULL)
    InputStream openAttachment(MailRef ref, AttachmentRef att);
}

public record RawEmail(String rfcMessageId, String gmailId, String from, String subject,
                       Instant receivedAt, String textBody, String htmlBody,
                       List<AttachmentRef> attachments) {}

public interface ContentExtractor {
    boolean supports(DetectedType type);
    Stream<TextChunk> extract(InputStream in, AttachmentRef att) throws ExtractionException;
}

public record TextChunk(String source, String location, String text) {}   // location e.g. "Sheet1!R42"

public interface EmailClassifier {
    Classification classify(RawEmail email);
}
public record Classification(EventType type, Optional<String> company) {}
public enum EventType { SHORTLIST, OFFER, OTHER }

public interface IdentifierIndex {
    Set<IdentifierRef> lookup(String normalizedToken);
    void rebuild();
}

public interface NotificationChannel {
    ChannelType type();                                     // DISCORD, EMAIL, NTFY (later WHATSAPP)
    DeliveryResult send(ChannelAddress to, Alert alert);    // SUCCESS | RETRYABLE(e) | PERMANENT(e)
}
```

**Matching rules**

- Normalise identifiers on save and tokens on read the same way: trim, upper-case, strip spaces and hyphens. `n1b7 j5v6` matches `N1B7J5V6`.
- Match whole tokens only, never substrings, so `N1B7J5V6X` does not match.
- College emails match case-insensitively on the full address.
- One email can match many users; one user matches an email at most once (`unique(user_id, email_id)`).
- An identifier format is checked on save with a configurable regex (NeoID pattern to be confirmed).

* Matching is scoped to the email's college: the index is keyed by `college_id`, so a NeoID registered at one college never matches mail from another.

**Design patterns used**

- Strategy + registry: `ContentExtractor`, `NotificationChannel`, `EmailClassifier`.
- Ports and adapters: `MailSource` hides Gmail, so a personal-inbox or Outlook source plugs in later.
- Domain events: `EmailIngested`, `MatchesFound`, `IdentifiersChanged` decouple the modules.
- Transactional outbox: the `delivery` table makes sending retryable and auditable.
- Idempotent consumer: unique keys at the email, match and delivery levels.

## Data model

Nine tables in PostgreSQL, managed by Flyway. Unique constraints do the dedupe work, so retries can never double-alert. Spring Modulith adds its own `event_publication` table.

```sql
-- V1__init.sql
create extension if not exists citext;

create table college (
  id                     uuid primary key,
  slug                   text unique not null,           -- e.g. 'my-college'
  name                   text not null,
  allowed_email_domains  text[] not null,                -- sign-in allowed only from these
  sender_allowlist       text[] not null,                -- placement-cell addresses or @domains
  neo_id_pattern         text not null default '^[A-Z0-9]{8}$',
  status                 text not null default 'PILOT',  -- PILOT | ACTIVE | PAUSED
  created_at             timestamptz not null default now()
);

create table app_user (
  id            uuid primary key,
  college_id    uuid not null references college(id),
  google_sub    text unique not null,
  email         citext unique not null,
  display_name  text,
  role          text not null default 'USER',      -- USER | ADMIN
  status        text not null default 'ACTIVE',    -- ACTIVE | PAUSED
  consented_at  timestamptz not null,
  created_at    timestamptz not null default now()
);

create table identifier (
  id                uuid primary key,
  college_id        uuid not null references college(id),
  user_id           uuid not null references app_user(id) on delete cascade,
  type              text not null,                 -- NEO_ID | REG_NO | COLLEGE_EMAIL
  normalized_value  text not null,
  created_at        timestamptz not null default now(),
  unique (college_id, type, normalized_value)      -- one owner per NeoID per college
);

create table channel (
  id           uuid primary key,
  user_id      uuid not null references app_user(id) on delete cascade,
  type         text not null,                      -- DISCORD | EMAIL | NTFY
  address_enc  bytea not null,                     -- AES-GCM: Discord user id, email, ntfy topic
  verified_at  timestamptz,
  enabled      boolean not null default true,
  unique (user_id, type)
);

create table mail_source (
  id                 uuid primary key,
  college_id         uuid not null references college(id),
  email              citext unique not null,
  refresh_token_enc  bytea not null,
  last_history_id    bigint,
  status             text not null,                -- ACTIVE | AUTH_EXPIRED | DISABLED
  last_synced_at     timestamptz
);

create table processed_email (
  rfc_message_id  text primary key,                -- cross-inbox dedupe key
  college_id      uuid not null references college(id),
  source_id       uuid not null references mail_source(id),
  gmail_id        text not null,
  sender          text not null,
  subject         text,
  received_at     timestamptz not null,
  event_type      text,                            -- SHORTLIST | OFFER | OTHER
  company         text,
  status          text not null,                   -- RECEIVED | PROCESSED | FAILED
  error           text,
  processed_at    timestamptz
);

create table match_hit (
  id               uuid primary key,
  user_id          uuid not null references app_user(id) on delete cascade,
  email_id         text not null references processed_email(rfc_message_id),
  identifier_id    uuid not null references identifier(id) on delete cascade,
  event_type       text not null,
  context_snippet  text,                           -- the user's own row only
  created_at       timestamptz not null default now(),
  unique (user_id, email_id)
);

create table delivery (
  id               uuid primary key,
  match_id         uuid not null references match_hit(id) on delete cascade,
  channel_id       uuid not null references channel(id) on delete cascade,
  status           text not null default 'PENDING', -- PENDING | RETRY | SENT | DEAD
  attempts         int not null default 0,
  next_attempt_at  timestamptz not null default now(),
  last_error       text,
  sent_at          timestamptz,
  unique (match_id, channel_id)
);
create index delivery_due_idx on delivery (next_attempt_at) where status in ('PENDING', 'RETRY');

create table link_code (
  code          text primary key,                  -- Discord /link code or hashed email OTP
  user_id       uuid not null references app_user(id) on delete cascade,
  channel_type  text not null,
  attempts      int not null default 0,
  expires_at    timestamptz not null
);
```

ShedLock needs one more small table (`shedlock`) for the poller lock. Deleting a user cascades to identifiers, channels, matches and deliveries.

## APIs

The REST API is versioned under `/api/v1`, session-authenticated after Google sign-in, and CSRF-protected. Discord slash commands cover the in-chat side.

**Student endpoints**

| Method | Path | Body | Purpose |
| --- | --- | --- | --- |
| GET | `/oauth2/authorization/google` | none | Start Google sign-in (Spring Security) |
| GET | `/api/v1/me` | none | Profile, identifiers, channels and their status |
| DELETE | `/api/v1/me` | none | Delete the account and all its data |
| POST | `/api/v1/me/identifiers` | `{type, value}` | Add an identifier; 409 if another account claimed it |
| DELETE | `/api/v1/me/identifiers/{id}` | none | Remove an identifier |
| POST | `/api/v1/me/channels/email` | `{address}` | Save the address and send an OTP |
| POST | `/api/v1/me/channels/email/verify` | `{code}` | Confirm the OTP |
| POST | `/api/v1/me/channels/ntfy` | none | Create a private topic; returns the subscribe URL and QR code |
| POST | `/api/v1/me/channels/discord/link-code` | none | One-time code for `/link`, valid 10 min |
| PATCH | `/api/v1/me/channels/{id}` | `{enabled}` | Pause or resume a channel |
| POST | `/api/v1/me/channels/{id}/test` | none | Send a test alert |
| GET | `/api/v1/me/alerts?page=` | none | Alert history: company, type, date, delivery status |

**Admin and ops endpoints** (role ADMIN)

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/v1/admin/sources/connect` | OAuth consent for a new source inbox (`gmail.readonly`) |
| GET | `/api/v1/admin/sources` | Source status, last sync, cursor |
| POST | `/api/v1/admin/emails/{rfcMessageId}/reprocess` | Re-run the pipeline for one email |
| GET | `/api/v1/admin/deliveries?status=DEAD` | Failed deliveries for review |
| GET | `/actuator/health`, `/actuator/prometheus` | Health and metrics (internal network only) |

**Discord slash commands**

| Command | Purpose |
| --- | --- |
| `/link code:<code>` | Ties the Discord account to the web account; replies ephemerally |
| `/status` | Shows linked identifiers and the last 3 alerts |
| `/pause`, `/resume` | Stops or restarts Discord alerts |
| `/unlink` | Removes the Discord channel |

**Errors** use RFC 9457 Problem Details (`application/problem+json`) with a stable `type` per error, for example `identifier-already-claimed`.

**v2 webhooks:** `POST /webhooks/gmail` (Pub/Sub push, OIDC token verified) and `POST /webhooks/ses` (bounce and complaint notices via SNS).

## Tech stack and libraries

Java 21 LTS and the current stable Spring Boot release; exact versions get pinned when the project is scaffolded.

| Layer | Choice | Used for |
| --- | --- | --- |
| Runtime | Java 21 LTS, virtual threads | Blocking I/O (Gmail, SES, ntfy) without thread-pool tuning |
| Framework | Spring Boot: Web MVC, Data JPA, Validation, Security OAuth2 Client, Actuator | App backbone |
| Modularity | Spring Modulith | Module boundaries, verified in tests; persisted domain events |
| Scheduling | `@Scheduled` + ShedLock | Single active poller and dispatcher across instances |
| Gmail | `google-api-services-gmail`, `google-auth-library-oauth2-http` | History polling, message and attachment fetch |
| Type detection | Apache Tika (core only) | Real file type, whatever the extension says |
| Excel | Apache POI (`poi-ooxml`), `excel-streaming-reader` | .xlsx/.xls parsing; streaming for big sheets |
| CSV | Apache Commons CSV | CSV attachments |
| PDF | Apache PDFBox | Text PDFs |
| Word | POI XWPF | .docx attachments |
| HTML | jsoup | HTML bodies and HTML tables to text |
| Discord | JDA | Gateway bot, slash commands, DMs |
| Email out | AWS SDK v2 `sesv2` | Alerts and OTP mails |
| Push | Spring `RestClient` to ntfy | Push alerts |
| Resilience | Resilience4j | Per-channel rate limiter and circuit breaker |
| Database | PostgreSQL 16+, Flyway, HikariCP | Storage and migrations |
| Crypto and secrets | AES-256-GCM field encryption; key from AWS SSM Parameter Store or KMS | Tokens and channel addresses at rest |
| UI | Thymeleaf + htmx | Server-rendered signup and dashboard in the same deployable |
| Observability | Micrometer, Prometheus format, JSON logs (logstash-logback-encoder) | Metrics, logs, alerts |
| Testing | JUnit 5, AssertJ, Mockito, Testcontainers (Postgres), WireMock, Modulith `verify()` | Unit, integration and architecture tests |
| Build and CI | Gradle (Kotlin DSL), GitHub Actions, Jib for images, Amazon ECR | Build, test, ship |

## Non-functional design

The hard requirements are no duplicate alerts, no silent misses, and no leakage of other students' data. Each gets a concrete mechanism below.

**Security**

- Gmail access is `gmail.readonly` on source inboxes only. Students grant just `openid email profile`.
- Refresh tokens and channel addresses are encrypted with AES-256-GCM. The key lives in SSM Parameter Store or KMS, never in the repo or the image.
- Sign-in accepts only the college domain: the `hd` claim plus a server-side suffix check.
- Session cookies are HttpOnly, Secure and SameSite=Lax; CSRF stays on.
- OTP and link-code endpoints are rate-limited per user and IP, and codes are stored hashed.
- No alert goes to a channel until it is verified, so nobody can spam someone else's email or Discord.

**Attachment safety**

- Size cap of 25 MB per attachment (Gmail's own limit).
- POI's zip-bomb guard (`ZipSecureFile` minimum inflate ratio) stays on; macros are never evaluated.
- Parsing runs on a bounded executor with a 30-second timeout per file. One bad file fails only that file, and the rest of the email still processes.

**Privacy** (built with India's DPDP Act, 2023 in mind)

- Explicit consent at signup, with a plain-language note on what is stored.
- Stored per user: identifiers, verified channels, and only their own matched row. Raw sheets and bodies are never persisted.
- One-click account deletion cascades everything. Match snippets are purged after 180 days.
- Residual risk: the app cannot prove a NeoID belongs to the person claiming it. The unique constraint stops double claims, and an admin resolves disputes. Since lists already go to the whole batch, exposure is low.

**Reliability**

- Idempotency at three levels: RFC Message-ID, `(user_id, email_id)`, `(match_id, channel_id)`.
- The cursor advances only after commit. If Gmail returns 404 for a stale `historyId`, the poller falls back to `messages.list` for the last 7 days and relies on dedupe.
- Retryable errors (429, 5xx, timeouts) back off; permanent ones (Discord DMs closed, invalid email) go DEAD at once and show on the student's dashboard.
- Admin alerts on Discord: source AUTH\_EXPIRED, DEAD deliveries, extraction failure spikes.

**Scalability**

- Work grows with emails, not users: each email is parsed once. Index lookup is O(tokens) with a hash map.
- 10,000 users with 3 identifiers each is about 30,000 index entries, a few MB of heap.
- `SKIP LOCKED` and ShedLock let a second instance run later without code changes.

**Observability**

- Metrics: `emails_ingested_total`, `extraction_failures_total{type}`, `matches_total{event}`, `delivery_latency_seconds` (email received to alert sent, p95 target under 5 min), `deliveries_dead_total{channel}`.
- Health indicators per source inbox and per channel. JSON logs carry the RFC Message-ID as a correlation ID.

## Deployment and infrastructure

One small ARM instance in AWS Mumbai (ap-south-1) runs everything under Docker Compose. Moving Postgres to RDS later changes only a connection string.

| Piece | v1 choice | Notes |
| --- | --- | --- |
| Compute | EC2 `t4g.small` (or Lightsail) | Docker Compose: `app` + `caddy` (reverse proxy, automatic HTTPS) + `postgres` |
| Database | Postgres container with nightly `pg_dump` to S3 | Cheapest; switch to RDS `db.t4g.micro` when there are real users |
| Secrets | SSM Parameter Store (SecureString) | Loaded at boot through an instance IAM role; nothing in `.env` files |
| Email | Amazon SES | Domain verified with DKIM, SPF and DMARC; request production access to leave the sandbox |
| Images | Amazon ECR | Built by Jib in CI |
| CI/CD | GitHub Actions | Test, build the image, push to ECR, deploy over SSM Run Command (`docker compose pull && up -d`) |
| DNS | Domain + Route 53 (or registrar DNS) | e.g. a `neobuddy` domain for the site and SES |
| Logs and metrics | CloudWatch (awslogs driver) + Actuator Prometheus endpoint | CloudWatch alarm on health check and dead deliveries |

**Environments:** `local` (Compose + Testcontainers), `prod`. A separate test Gmail inbox and test Discord server keep development away from real placement mail.

**Configuration** (`application.yml`, overridable by env):

```yaml
neobuddy:
  ingestion.poll-interval: PT2M
  delivery:
    max-attempts: 6
    base-backoff: PT1M
  channels:
    discord.guild-id: ${DISCORD_GUILD_ID:}
    ntfy.base-url: https://ntfy.sh
    ses.from: alerts@<your-domain>
  bootstrap:                       # v1 seed, values from env / SSM
    college:
      slug: my-college
      name: <college name>
      allowed-email-domains: [<college-domain>]
      sender-allowlist: [<placement-cell-address>]
      neo-id-pattern: "^[A-Z0-9]{8}$"   # confirm format
    admin:
      email: <your college email>
      neo-id: N1B7J5V6
    source:
      email: <your college email>
      refresh-token: ${SOURCE_REFRESH_TOKEN:}
```

Per-college settings (domains, senders, NeoID pattern) live in the `college` table. The bootstrap block only seeds the first college and admin; later colleges are added from the admin console.

## Scaling to every NeoPAT college

Going from one college to many takes one connected inbox per college and no code changes. Infrastructure grows in three stages as real load appears.

**Onboarding a new college**

1. A student from an unsupported college signs in and lands on "Request your college", which records the request.
2. Admin creates the college with its email domains, placement sender addresses and NeoID pattern, at status PILOT.
3. A volunteer campus lead connects their college inbox as the source through an admin-issued link.
4. Admin checks the first processed emails, flips the college to ACTIVE, and the campus lead shares the signup link.

**Google limits that shape growth**

- Students grant only basic sign-in scopes, so Google puts no cap on student numbers.
- Source inboxes use the restricted `gmail.readonly` scope. An unverified app can authorise at most 100 accounts over its lifetime, which means about 100 colleges.
- Before reaching that, either complete Google's restricted-scope verification (it includes a paid third-party security assessment) or move sources to a forwarding model: the campus lead forwards placement mail to a NeoBuddy-owned inbox, which needs no restricted scope.

**Infrastructure stages** (rough planning figures, to be replaced with measured load)

| Stage | Load | Setup | What changes |
| --- | --- | --- | --- |
| 1 · Pilot | 1 college, up to \~1,000 students | One EC2 t4g.small, Postgres container, S3 backups | Starting point |
| 2 · Growth | \~5–30 colleges | RDS Postgres, two app instances behind an ALB | Terraform change and a DB cut-over; ShedLock and `SKIP LOCKED` already handle concurrency |
| 3 · Scale | 30+ colleges | Gmail push via Pub/Sub, ingestion and processing workers fed by SQS, ECS Fargate | Split services along the Modulith module boundaries |

Per-college quirks (sheet layouts, company naming in subjects) are handled with per-college classifier rules, so one college's format never needs a code change.

## Build plan and open questions

Build from the inside out: the parsing and matching engine first, since it is pure Java and testable offline on real sheets, then wire Gmail, channels, users and Discord around it.

**Milestones**

Step-by-step Claude Code prompts for each task are in Claude Code playbook.

1. **T0 Setup (you):** repo, `CLAUDE.md`, Google Cloud and AWS accounts.
2. **T1 Scaffold:** Gradle project, modules, Flyway V1, Testcontainers, CI.
3. **T2 Extraction engine:** extractors with safety limits, synthetic fixtures.
4. **T3 CsvExtractor + Tokenizer (you):** against tests that T2 writes; Claude Code only reviews.
5. **T4 Classification and matching:** per-college index, golden tests.
6. **T5 Subscribers, encryption, bootstrap:** seeds your college and you.
7. **T6 Gmail ingestion:** history polling, Message-ID dedupe, cursor handling.
8. **T7 Delivery, push and email:** outbox, dispatcher, ntfy, SES. Works for you locally.
9. **T8 AWS infra and deployment:** Terraform, Docker, CD via GitHub OIDC. Runs 24/7 for you.
10. **T9 Web sign-in and onboarding:** your college's students can sign up.
11. **T10 Discord bot:** `/link` and DM alerts.
12. **T11 Admin console and observability:** colleges, sources, failures, metrics.
13. **T12 Launch hardening:** security review, load test, RDS, SES production access. Open to your batch.

**v2 backlog:** Gmail push via Pub/Sub; students connecting their own inbox for emails not sent to the shared inbox; WhatsApp Cloud API with an approved utility template; Google Sheets links; OCR for scanned PDFs and images (Textract); an LLM fallback classifier for unusual emails.

**Open questions**

- [ ] What is the college Google domain, and do Workspace admins let student accounts grant third-party OAuth apps? If blocked, the fallback is a Gmail filter that forwards placement mail to a dedicated ingestion inbox.
- [ ] Is every NeoID 8 alphanumeric characters? This sets the validation regex.
- [ ] Which sender addresses does the placement cell use? They become the allowlist.
- [ ] Can you share 3–5 real shortlists with other students' data redacted, as test fixtures?
- [ ] UI: Thymeleaf + htmx (recommended, one deployable) or a React SPA?
- [ ] Product domain name, for the site and SES sender.
