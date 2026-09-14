import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
    application
}

group = "obs"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("net.jqwik:jqwik:1.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // -parameters lets jqwik report property parameter names; -processing is noise from jqwik's annotations.
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-parameters"))
}

application {
    mainClass = "obs.app.PrintBookDemo"
}

tasks.test {
    useJUnitPlatform {
        includeEngines("junit-jupiter", "jqwik")
    }
    jvmArgs("-ea")
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
