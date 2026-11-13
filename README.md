# NeoBuddy

NeoBuddy reads placement-cell emails once, finds each subscribed student's NeoID in the body and
attachments, and alerts them on Discord, email and push. Design: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md);
progress: [docs/PROGRESS.md](docs/PROGRESS.md).

## Requirements

- Docker (local Postgres and the Testcontainers tests).
- Any JDK 8+ on `PATH` to launch the Gradle wrapper. Gradle downloads JDK 21 itself if you do
  not have one.

## Run locally

```bash
docker compose up -d db
./gradlew bootRun --args='--spring.profiles.active=local'
curl http://localhost:8080/actuator/health   # {"status":"UP", ...}
```

The `local` profile points at the Compose database. Outside it, `DB_URL`, `DB_USERNAME` and
`DB_PASSWORD` must be set in the environment; no secrets live in the repo.

## Test

```bash
./gradlew build            # compile, format check, all tests (needs Docker running)
./gradlew spotlessApply    # fix formatting
```

Module diagrams are written to `build/spring-modulith-docs/` by `ModularityTests`.
