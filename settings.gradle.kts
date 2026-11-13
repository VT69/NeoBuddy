plugins {
    // Lets Gradle download the Java 21 toolchain when no local JDK 21 is installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "neobuddy"
