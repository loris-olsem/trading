plugins {
    application
    jacoco
    id("info.solidsoft.pitest") version "1.19.0"
}

group = "com.loris"
version = "1.0.0"
repositories { mavenCentral() }
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
application { mainClass = "com.loris.bravos.app.Main" }

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
    targetClasses = setOf("com.loris.bravos.domain.*", "com.loris.bravos.source.AlertParser", "com.loris.bravos.state.*", "com.loris.bravos.broker.Executor")
    threads = 4
    outputFormats = setOf("HTML", "XML")
    timestampedReports = false
    mutationThreshold = 85
    failWhenNoMutations = true
}
dependencyLocking { lockAllConfigurations() }
