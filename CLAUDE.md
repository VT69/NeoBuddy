# NeoBuddy

Alerts students when their NeoID appears in placement shortlist or offer emails.
Design (source of truth): docs/ARCHITECTURE.md. Progress log: docs/PROGRESS.md.

## Stack
Java 21, Spring Boot (latest stable), Spring Modulith, Gradle Kotlin DSL, PostgreSQL + Flyway,
Thymeleaf + htmx, JUnit 5 + Testcontainers, AWS (EC2, SES, SSM, S3) via Terraform.

## Commands
- Build + all tests: ./gradlew build
  - With Docker running, DB tests use Testcontainers (this is what CI does).
  - Without Docker, point them at a local PostgreSQL; the database is wiped on every run and its
    name must end in _test:
    NEOBUDDY_TEST_DB_URL=jdbc:postgresql://localhost:5432/neobuddy_test NEOBUDDY_TEST_DB_USER=...
    NEOBUDDY_TEST_DB_PASSWORD=... ./gradlew build
- Run locally (needs PostgreSQL on localhost:5432 with database neobuddy, and env
  NEOBUDDY_ENCRYPTION_KEY set to a base64 32-byte key, and NEOBUDDY_GOOGLE_CLIENT_ID /
  NEOBUDDY_GOOGLE_CLIENT_SECRET for student sign-in; see README):
  ./gradlew bootRun --args='--spring.profiles.active=local'
  - Seed the first college, admin and source inbox with the NEOBUDDY_BOOTSTRAP_* variables
    listed in README. Gmail needs NEOBUDDY_GMAIL_CLIENT_ID and NEOBUDDY_GMAIL_CLIENT_SECRET.
  - Emails are only logged locally; real ones need NEOBUDDY_EMAIL_PROVIDER=ses, NEOBUDDY_SES_FROM
    and AWS credentials (see README, "Alerts: push and email").
- Authorise a source inbox (prints its refresh token once): ./gradlew authorizeSource --console=plain
  - Optional Docker database instead of a local install: docker compose up -d db
- Format: ./gradlew spotlessApply
- Container image: ./gradlew jibDockerBuild (needs Docker). Production runbook: deploy/README.md.
- Terraform checks (AWS mocked): cd infra/terraform && terraform init -backend=false && terraform test

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