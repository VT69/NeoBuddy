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