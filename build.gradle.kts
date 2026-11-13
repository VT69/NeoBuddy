plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spotless)
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

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Database tests switch between Testcontainers and an external PostgreSQL on this
    // variable, so a change must re-run them instead of reusing cached results.
    inputs.property("testDbUrl", providers.environmentVariable("NEOBUDDY_TEST_DB_URL").orElse(""))
}

spotless {
    java {
        googleJavaFormat(libs.versions.google.java.format.get())
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
