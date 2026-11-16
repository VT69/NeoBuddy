# syntax=docker/dockerfile:1
#
# Image for Render's free web service (512 MB RAM, 0.1 CPU). See deploy/RENDER.md.
# The AWS deployment does not use this file: CI builds that image with Jib (build.gradle.kts).
#
#   docker build -t neobuddy .
#   docker run --rm -p 8080:8080 --memory=512m --cpus=0.1 --env-file render.env neobuddy
#
# Tests are not run here; CI runs ./gradlew build on every push.

# ---- 1. Build the jar ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src

# Gradle and the dependencies first, so a code-only change reuses this layer.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon --quiet dependencies > /dev/null

COPY src src
RUN ./gradlew --no-daemon --quiet bootJar \
 && java -Djarmode=tools -jar build/libs/neobuddy-*-SNAPSHOT.jar extract --destination /app \
 && mv /app/neobuddy-*-SNAPSHOT.jar /app/neobuddy.jar

# ---- 2. Runtime ---------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble

# JVM flags, read by the java launcher for every run below (the training run and the app), so the
# class-data archive is made with the same settings it is used with. Override on Render by setting
# JDK_JAVA_OPTIONS in the dashboard.
#
#   -XX:+UseSerialGC            One GC thread and the least memory overhead of any collector. With
#                               0.1 CPU there is no second core for a parallel or concurrent GC to
#                               use, and a heap of a few hundred MB collects in milliseconds.
#   -XX:MaxRAMPercentage=55     Heap ceiling as a share of the container limit: ~280 MB of 512 MB.
#                               The rest is for metaspace (~110 MB with Spring, Hibernate, POI,
#                               PDFBox, JDA), the code cache, thread stacks, direct buffers and the
#                               mapped class-data archive. Measured: see deploy/RENDER.md.
#   -XX:InitialRAMPercentage=25 Start at ~128 MB instead of 8 MB, so startup is not spent growing
#                               the heap one GC at a time.
#   -XX:TieredStopAtLevel=1     C1 compiler only. C2 needs a lot of CPU time to pay off, which a
#                               0.1-CPU, low-traffic service never gets back; C1 code is plenty fast
#                               here, starts sooner and keeps the code cache small.
#   -XX:ReservedCodeCacheSize=48m  Enough for C1-only code (about 30 MB in use), instead of the
#                               240 MB the JVM reserves for tiered compilation.
#   -Xss512k                    Half the default stack per platform thread. Requests run on virtual
#                               threads; the few platform threads (Tomcat, JDA, schedulers) need
#                               little.
#   -XX:+ExitOnOutOfMemoryError Die at once instead of limping on; Render restarts the service.
#   -Dspring.backgroundpreinitializer.ignore=true
#                               Spring pre-initialises some classes on a second thread to use a
#                               spare core; with 0.1 CPU there is none and it only competes with
#                               startup.
ENV JDK_JAVA_OPTIONS="-XX:+UseSerialGC -XX:MaxRAMPercentage=55 -XX:InitialRAMPercentage=25 \
-XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=48m -Xss512k -XX:+ExitOnOutOfMemoryError \
-Dspring.backgroundpreinitializer.ignore=true" \
    TZ=UTC \
    SPRING_PROFILES_ACTIVE=render

WORKDIR /app
COPY --from=build /app /app

# Class Data Sharing (CDS): start the application once at build time, stop it as soon as the
# context is ready, and save the parsed and verified classes in /app/app.jsa. Startup maps that
# file instead of loading thousands of classes from jars again, which is most of the startup CPU.
# The training run reaches no database and no outside service: Flyway, Hibernate's schema check,
# the poller and the dispatcher are switched off and every secret is a placeholder. The archive
# only holds classes, never configuration or secrets.
RUN env SPRING_PROFILES_ACTIVE=render \
      DB_URL=jdbc:postgresql://127.0.0.1:1/training DB_USERNAME=training DB_PASSWORD=training \
      NEOBUDDY_GOOGLE_CLIENT_ID=training NEOBUDDY_GOOGLE_CLIENT_SECRET=training \
      NEOBUDDY_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= \
      NEOBUDDY_BREVO_API_KEY=training NEOBUDDY_BREVO_FROM=training@example.invalid \
      SPRING_FLYWAY_ENABLED=false \
      SPRING_JPA_HIBERNATE_DDL_AUTO=none \
      SPRING_JPA_PROPERTIES_HIBERNATE_BOOT_ALLOW_JDBC_METADATA_ACCESS=false \
      SPRING_JPA_PROPERTIES_HIBERNATE_DIALECT=org.hibernate.dialect.PostgreSQLDialect \
      SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART=false \
      NEOBUDDY_INGESTION_ENABLED=false NEOBUDDY_DELIVERY_ENABLED=false \
      NEOBUDDY_MATCHING_INDEX_INITIAL_DELAY=PT1H SERVER_PORT=0 \
      java -XX:ArchiveClassesAtExit=/app/app.jsa -Dspring.context.exit=onRefresh \
        -jar /app/neobuddy.jar > /tmp/cds-training.log 2>&1 \
 || (tail -n 50 /tmp/cds-training.log; exit 1) \
 ; rm -f /tmp/cds-training.log \
 && chmod -R a+rX,go-w /app

# An unprivileged uid with no home and no entry in /etc/passwd, as in the Jib image: it owns
# nothing in the image, so the application cannot change its own files.
USER 10001:10001

# Render sets PORT (10000 by default); server.port reads it.
EXPOSE 10000

# -XX:SharedArchiveFile uses the archive from the training run. If it ever does not match the JVM
# (it always does when built by this file), the JVM says so and starts without it.
ENTRYPOINT ["java", "-XX:SharedArchiveFile=/app/app.jsa", "-jar", "/app/neobuddy.jar"]
