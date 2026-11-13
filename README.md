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

## Run locally

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
curl http://localhost:8080/actuator/health   # {"status":"UP", ...}
```

The `local` profile connects to `localhost:5432/neobuddy` as `neobuddy`/`neobuddy`; override with
`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`. Outside the `local` profile those three variables are
required; no secrets live in the repo.

If port 8080 is already in use (IIS on Windows, for example), pick another with `PORT`:

```bash
PORT=8081 ./gradlew bootRun --args='--spring.profiles.active=local'
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
