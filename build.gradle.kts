import java.nio.file.Files

plugins {
    application
    jacoco
    id("info.solidsoft.pitest") version "1.19.0"
    id("com.diffplug.spotless") version "8.10.2"
}

group = "com.loris"
version = "1.0.0"
repositories { mavenCentral() }
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
application { mainClass = "com.loris.bravos.app.Main" }
spotless { java { googleJavaFormat("1.36.1") } }

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.2")
    implementation("org.jsoup:jsoup:1.23.2")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.test {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
    finalizedBy(tasks.jacocoTestReport)
}
jacoco { toolVersion = "0.8.15" }
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports { xml.required = true; html.required = true }
}
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit { counter = "INSTRUCTION"; minimum = "0.85".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.75".toBigDecimal() }
        }
    }
}
tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }
pitest {
    pitestVersion = "1.30.0"
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("com.loris.bravos.domain.*", "com.loris.bravos.source.AlertParser", "com.loris.bravos.state.*", "com.loris.bravos.app.Workflow", "com.loris.bravos.app.Configuration", "com.loris.bravos.broker.Executor", "com.loris.bravos.broker.EtoroClient", "com.loris.bravos.broker.OrderPayloads")
    targetTests = setOf("com.loris.bravos.*")
    threads = 4
    outputFormats = setOf("HTML", "XML")
    timestampedReports = false
    mutationThreshold = 85
    failWhenNoMutations = true
}
dependencyLocking { lockAllConfigurations() }

tasks.register<JavaExec>("capture") {
    group = "bravos"
    description = "Capture Bravos pages read-only; optional -Psince=YYYY-MM-DD."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.loris.bravos.app.Capture"
    if (project.hasProperty("since")) args(project.property("since").toString())
}

// Operations always use the current compiled sources and the project working directory.
fun registerOperation(name: String, command: String, help: String) = tasks.register<JavaExec>(name) {
    group = "bravos"
    description = help
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    workingDir = projectDir
    args(command)
    if (command == "plan" || command == "initialize")
        providers.gradleProperty("since").orNull?.let { args("--since", it) }
    if (command == "early-exit") {
        doFirst {
            args(providers.gradleProperty("cycle").get(), providers.gradleProperty("fraction").get())
        }
    }
}
registerOperation("appHelp", "help", "Show application commands without connecting.")
registerOperation("plan", "plan", "Read-only dry run; optional -Psince=YYYY-MM-DD.")
registerOperation("initialize", "initialize", "Enroll once without trading; optional -Psince=YYYY-MM-DD.")
registerOperation("status", "status", "Show the local journal and unresolved work.")
registerOperation("earlyExit", "early-exit", "Record an exit: -Pcycle=ID -Pfraction=0.25; no immediate trade.")
tasks.named<JavaExec>("run") {
    group = "bravos"
    description = "OWNER ONLY: submit eligible live trades and reconcile execution."
    workingDir = projectDir
    args("run")
}
for ((name, argument) in mapOf("brokerDiagnostics" to "--broker", "replayCapture" to "--replay")) {
    tasks.register<JavaExec>(name) {
        group = "bravos"
        description = if (name == "brokerDiagnostics") "Read-only agent and owner account diagnostics."
            else "Replay the private source capture offline."
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = "com.loris.bravos.app.Capture"
        workingDir = projectDir
        args(argument)
    }
}
tasks.register("kill") {
    group = "bravos"
    description = "Set the local kill switch; does not close holdings or cancel orders."
    doLast {
        val marker = layout.projectDirectory.file("state/runtime/KILL").asFile.toPath()
        Files.createDirectories(marker.parent)
        Files.writeString(marker, "Owner requested stop\n")
    }
}
tasks.register("resume") {
    group = "bravos"
    description = "Remove the local kill switch; does not invoke trading."
    doLast { Files.deleteIfExists(layout.projectDirectory.file("state/runtime/KILL").asFile.toPath()) }
}
defaultTasks("help")

val checkpointState = tasks.register<JavaExec>("checkpointState") {
    group = "bravos"
    description = "Commit authoritative state and numeric history locally under the app lock; never push or trade."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.loris.bravos.app.StateCheckpoint"
    workingDir = projectDir
}
for (operation in listOf("plan", "run", "initialize", "earlyExit")) {
    tasks.named(operation) { finalizedBy(checkpointState) }
}

tasks.register<JavaExec>("watchlistMetadata") {
    group = "bravos"
    description = "Read existing owner watchlists for broker currency/precision metadata; no list changes."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.loris.bravos.app.InstrumentAudit"
    workingDir = projectDir
    args("--watchlists")
}

tasks.register<JavaExec>("instrumentPreflight") {
    group = "bravos"
    description = "Read-only configured-instrument, quote and cost diagnostics; no source crawl or orders."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.loris.bravos.app.InstrumentAudit"
    workingDir = projectDir
    args("--preflight")
}

tasks.register<JavaExec>("instrumentAudit") {
    group = "bravos"
    description = "Read-only instrument eligibility (-Psymbols=CF,EOG) or identity search (-Pquery=name)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.loris.bravos.app.InstrumentAudit"
    workingDir = projectDir
    val query = providers.gradleProperty("query").orNull
    if (query != null) args("--query", query)
    else providers.gradleProperty("symbols").orNull?.let { args(it) }
}
