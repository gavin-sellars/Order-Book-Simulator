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

// JMH benchmarks live in src/jmh/java and can see the main code.
val jmh: SourceSet = sourceSets.create("jmh") {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}
configurations[jmh.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[jmh.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    implementation("org.hdrhistogram:HdrHistogram:2.2.2")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("net.jqwik:jqwik:1.10.1")
    testImplementation("org.openjdk.jol:jol-core:0.17")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "jmhImplementation"("org.openjdk.jmh:jmh-core:1.37")
    "jmhAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // -parameters lets jqwik report property parameter names; -processing is noise from jqwik's annotations.
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-parameters"))
}

// JMH's generated benchmark code isn't lint-clean, and its warnings would bury ours.
tasks.named<JavaCompile>(jmh.compileJavaTaskName) {
    options.compilerArgs.removeAll { it.startsWith("-Xlint") }
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

fun splitArgs(property: String): List<String> =
    (findProperty(property) as String?)?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()

tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Runs JMH. -PjmhArgs=\"<JMH options and benchmark regex>\", -PjmhJvmArgs=\"<flags for forked JVMs>\"."
    classpath = jmh.runtimeClasspath
    mainClass = "org.openjdk.jmh.Main"
    val results = layout.buildDirectory.file("reports/jmh/results.json").get().asFile
    val jvmArgsAppend = findProperty("jmhJvmArgs") as String?
    args(splitArgs("jmhArgs"))
    if (jvmArgsAppend != null) args("-jvmArgsAppend", jvmArgsAppend)
    args("-rf", "json", "-rff", results.path)
    doFirst { results.parentFile.mkdirs() }
}

tasks.register<JavaExec>("latency") {
    group = "benchmark"
    description = "Prints HdrHistogram latency percentiles and the coordinated omission demo. -PlatencyArgs=\"<cycles>\"."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "obs.app.LatencyHistogramMain"
    jvmArgs("-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch")
    args(splitArgs("latencyArgs"))
}

tasks.register<JavaExec>("itchSession") {
    group = "application"
    description = "Generates a synthetic ITCH session, replays it into the fast book and checks it. -PitchArgs=\"[seed] [minutes] [file]\"."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "obs.app.ItchSessionMain"
    jvmArgs("-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch")
    args(splitArgs("itchArgs"))
}

tasks.register<JavaExec>("latencySweep") {
    group = "application"
    description = "Runs the sample market maker at several latencies and writes P&L CSVs. -PsweepArgs=\"[seed] [minutes]\"."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "obs.app.LatencySweepMain"
    jvmArgs("-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch")
    args(splitArgs("sweepArgs"))
}

tasks.register<JavaExec>("pipelinedReplay") {
    group = "benchmark"
    description = "Compares single-threaded and two-thread ITCH replay. -PpipelineArgs=\"[file] [runs]\"."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "obs.app.PipelinedReplayMain"
    jvmArgs("-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch")
    args(splitArgs("pipelineArgs"))
}

tasks.register<JavaExec>("epsilonSmoke") {
    group = "benchmark"
    description = "Replays the benchmark tape under Epsilon GC, which never frees memory: any hot-path allocation kills the run."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "obs.app.EpsilonSmokeMain"
    jvmArgs("-XX:+UnlockExperimentalVMOptions", "-XX:+UseEpsilonGC", "-Xms512m", "-Xmx512m", "-XX:+AlwaysPreTouch")
    args(splitArgs("epsilonArgs"))
}
