plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spotless)
    alias(libs.plugins.jib)
}

group = "com.neobuddy"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.modulith.bom))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.security.oauth2.client)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.thymeleaf)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.spring.modulith.starter.jpa)
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.provider.jdbc.template)

    // Extraction (processing module)
    implementation(libs.tika.core)
    implementation(libs.poi.ooxml)
    implementation(libs.excel.streaming.reader)
    implementation(libs.pdfbox)
    implementation(libs.jsoup)
    implementation(libs.commons.csv)

    // Ingestion (Gmail)
    implementation(libs.google.api.gmail)
    implementation(libs.google.auth.oauth2.http)
    implementation(libs.resilience4j.retry)

    // Notification (ntfy, SES)
    implementation(libs.resilience4j.ratelimiter)
    implementation(libs.aws.sesv2) {
        // Only the synchronous client is used.
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }

    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.wiremock.standalone)
    testRuntimeOnly(libs.junit.platform.launcher)
}

springBoot {
    // There is a second main class (the SourceAuthorizer tool), so name the application's.
    mainClass = "com.neobuddy.NeoBuddyApplication"
}

// One-off tool: authorises a source inbox with Google and prints its refresh token.
// Usage: ./gradlew authorizeSource --console=plain
tasks.register<JavaExec>("authorizeSource") {
    group = "neobuddy"
    description = "Runs the Google OAuth flow for a source inbox and prints the refresh token."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.neobuddy.ingestion.tools.SourceAuthorizer"
    standardInput = System.`in`
}

// Container image (docs/ARCHITECTURE.md: "Jib for images"). CI pushes it to ECR with:
//   ./gradlew jib -Djib.to.image=<ecr-repository-url>:<git-sha>
// Locally, ./gradlew jibDockerBuild builds it into a running Docker daemon (one platform only).
jib {
    from {
        // Eclipse Temurin 21 JRE on Ubuntu 24.04; the tag is multi-architecture.
        image = "eclipse-temurin:21-jre-noble"
        platforms {
            platform {
                architecture = "arm64"
                os = "linux"
            }
            platform {
                architecture = "amd64"
                os = "linux"
            }
        }
    }
    to {
        image = "neobuddy"
    }
    container {
        mainClass = "com.neobuddy.NeoBuddyApplication"
        // An unprivileged uid with no entry in /etc/passwd: it owns nothing in the image, so the
        // application cannot modify its own files.
        user = "10001:10001"
        ports = listOf("8080")
        // Java 21 reads the container's memory limit itself; the heap gets a share of it, leaving
        // room for metaspace, thread stacks and direct buffers inside the same limit.
        jvmFlags = listOf(
            "-XX:InitialRAMPercentage=40",
            "-XX:MaxRAMPercentage=65",
            "-XX:+ExitOnOutOfMemoryError",
        )
        environment = mapOf("TZ" to "UTC")
        labels.put("org.opencontainers.image.source", "https://github.com/VT69/NeoBuddy")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Database tests switch between Testcontainers and an external PostgreSQL on this
    // variable, so a change must re-run them instead of reusing cached results.
    inputs.property("testDbUrl", providers.environmentVariable("NEOBUDDY_TEST_DB_URL").orElse(""))
    // DeployScriptsTests runs these scripts, so a change to them must re-run the tests.
    inputs.dir("deploy").withPropertyName("deployScripts")
    inputs.dir(".github/scripts").withPropertyName("ciScripts")
}

spotless {
    java {
        googleJavaFormat(libs.versions.google.java.format.get())
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
