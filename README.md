# NeoBuddy

NeoBuddy reads placement-cell emails once, finds each subscribed student's NeoID in the body and
attachments, and alerts them on Discord, email and push. Design: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md);
progress: [docs/PROGRESS.md](docs/PROGRESS.md).

## Requirements

- PostgreSQL 16+, either installed locally or through Docker. Docker is optional.
- Any JDK 8+ on `PATH` to launch the Gradle wrapper. Gradle downloads JDK 21 itself if you do
  not have one.

## Database setup

Pick one.

**A. Local PostgreSQL (no Docker).** Create a role and two databases once, as a superuser:

```sql
create role neobuddy login password 'neobuddy';
create database neobuddy owner neobuddy;       -- used by the running app
create database neobuddy_test owner neobuddy;  -- wiped by the tests
\c neobuddy
create extension if not exists citext;
\c neobuddy_test
create extension if not exists citext;
```

**B. Docker.** `docker compose up -d db` starts Postgres 16 on `localhost:5432` with the
`neobuddy` database. Tests need no setup: they start their own container.

## Encryption key

Channel addresses and refresh tokens are encrypted in the database with one AES-256 key, and the
app does not start without it. Generate it once and keep it somewhere safe (a password manager
locally, SSM Parameter Store in production):

```bash
openssl rand -base64 32
```

Set it as `NEOBUDDY_ENCRYPTION_KEY`. **If the key is lost, every stored channel address and
refresh token is unreadable**; if it changes, the same is true until the old one is restored.
Never commit it.

## Run locally

```bash
export NEOBUDDY_ENCRYPTION_KEY=<your key>
./gradlew bootRun --args='--spring.profiles.active=local'
curl http://localhost:8080/actuator/health   # {"status":"UP", ...}
```

## Bootstrap: your college and you

Until there is a sign-up UI, the first college and its admin are created from environment
variables at startup. It is an upsert: starting again with the same values changes nothing, and
changed values update in place. Leave `NEOBUDDY_BOOTSTRAP_COLLEGE_SLUG` unset to seed nothing.

| Variable | Meaning |
| --- | --- |
| `NEOBUDDY_BOOTSTRAP_COLLEGE_SLUG` | Short unique name, e.g. `my-college` |
| `NEOBUDDY_BOOTSTRAP_COLLEGE_NAME` | Display name (defaults to the slug) |
| `NEOBUDDY_BOOTSTRAP_COLLEGE_EMAIL_DOMAINS` | Comma-separated sign-in domains; at least one |
| `NEOBUDDY_BOOTSTRAP_COLLEGE_SENDER_ALLOWLIST` | Comma-separated placement-cell addresses, or `@domain` |
| `NEOBUDDY_BOOTSTRAP_COLLEGE_NEO_ID_PATTERN` | Regex for a NeoID (default `^[A-Z0-9]{8}$`) |
| `NEOBUDDY_BOOTSTRAP_ADMIN_EMAIL` | Your college email; must be on one of the domains above |
| `NEOBUDDY_BOOTSTRAP_ADMIN_NEO_ID` | Your NeoID (required when the admin email is set) |
| `NEOBUDDY_BOOTSTRAP_ADMIN_REG_NO` | Optional registration number |
| `NEOBUDDY_BOOTSTRAP_ADMIN_NTFY_TOPIC` | Optional ntfy topic, registered as already verified |
| `NEOBUDDY_BOOTSTRAP_ADMIN_PERSONAL_EMAIL` | Optional personal email, registered as already verified |

| `NEOBUDDY_BOOTSTRAP_SOURCE_EMAIL` | The inbox placement mail is read from (see below) |
| `NEOBUDDY_BOOTSTRAP_SOURCE_REFRESH_TOKEN` | Its refresh token, from `./gradlew authorizeSource` |

Put real values only in your shell or an untracked `.env` file, never in a tracked file. The
startup log says `Bootstrap: college '<slug>' created, admin created` the first time and
`already present` afterwards; it never prints the admin's details.

The `local` profile connects to `localhost:5432/neobuddy` as `neobuddy`/`neobuddy`; override with
`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`. Outside the `local` profile those three variables are
required; no secrets live in the repo.

If port 8080 is already in use (IIS on Windows, for example), pick another with `PORT`:

```bash
PORT=8081 ./gradlew bootRun --args='--spring.profiles.active=local'
```

## Connect a source inbox (Gmail)

NeoBuddy reads placement mail from an inbox with Google's read-only Gmail scope. It needs the
OAuth client of a Google Cloud project that has the Gmail API enabled (client type "Desktop
app"):

```bash
export NEOBUDDY_GMAIL_CLIENT_ID=<client id>
export NEOBUDDY_GMAIL_CLIENT_SECRET=<client secret>
```

**1. Authorise the inbox once.** This prints a Google address; open it in a browser signed in to
the inbox and approve. The refresh token is printed once in the terminal and nowhere else:

```bash
./gradlew authorizeSource --console=plain
```

Treat the refresh token like a password. If Google returns none, remove NeoBuddy's access at
<https://myaccount.google.com/permissions> and run it again.

**2. Start NeoBuddy with the source configured,** next to the college variables above:

```bash
export NEOBUDDY_BOOTSTRAP_SOURCE_EMAIL=<the inbox address>
export NEOBUDDY_BOOTSTRAP_SOURCE_REFRESH_TOKEN=<the refresh token>
./gradlew bootRun --args='--spring.profiles.active=local'
```

The token is stored encrypted. The first poll only records the inbox's current position, so mail
that was already there is never read; from then on every new email from an address on the
college's sender allowlist is processed, once, about every 2 minutes
(`neobuddy.ingestion.poll-interval`). To reconnect an inbox whose access was revoked, authorise
it again and restart with the new token.

**Check it end to end:** add an address you can send from to
`NEOBUDDY_BOOTSTRAP_COLLEGE_SENDER_ALLOWLIST`, restart, wait for the first poll, then send the
inbox an email from that address with a small spreadsheet containing your NeoID. Within one poll
there should be a row for you:

```sql
select e.subject, e.status, e.event_type, m.context_snippet
from processed_email e left join match_hit m on m.email_id = e.rfc_message_id
order by e.received_at desc limit 5;
```

## Test

```bash
./gradlew build            # compile, format check, all tests
./gradlew spotlessApply    # fix formatting
```

Database tests choose their PostgreSQL like this:

- **Default (and CI): Testcontainers.** Needs a running Docker daemon.
- **Without Docker:** set the variables below and the tests use that database instead.

  ```bash
  export NEOBUDDY_TEST_DB_URL=jdbc:postgresql://localhost:5432/neobuddy_test
  export NEOBUDDY_TEST_DB_USER=neobuddy
  export NEOBUDDY_TEST_DB_PASSWORD=neobuddy
  ./gradlew build
  ```

  Each test run **wipes that database** (Flyway clean, then migrate). As a guard, the tests refuse
  any database whose name does not end in `_test`.

Module diagrams are written to `build/spring-modulith-docs/` by `ModularityTests`.
