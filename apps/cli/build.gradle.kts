import org.gradle.api.tasks.testing.Test
import org.gradle.api.plugins.jvm.JvmTestSuite

val java17Home = providers.environmentVariable("GEN2SPRING_JAVA_17_HOME")
val java21Home = providers.environmentVariable("GEN2SPRING_JAVA_21_HOME")
val installedExecutableName = if (System.getProperty("os.name").lowercase().startsWith("windows")) {
    "openapi-mcp.bat"
} else {
    "openapi-mcp"
}

plugins {
    application
}

dependencies {
    implementation(project(":modules:bootstrap"))
    implementation(libs.bundles.jackson)
    compileOnly(libs.slf4j.api)
    testImplementation(libs.archunit)
    testCompileOnly(libs.slf4j.api)
}

application {
    mainClass.set("io.gen2spring.mcp.app.cli.Main")
    applicationName = "openapi-mcp"
}

tasks.named<Test>("test") {
    dependsOn(tasks.named("installDist"))
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    systemProperty(
        "openapiMcp.executable",
        layout.buildDirectory.file("install/openapi-mcp/bin/$installedExecutableName").get().asFile.absolutePath,
    )
}

val fastUnitTestSources = fileTree("src/test/java") {
    include("**/*.java")
    exclude("**/InstalledCliTest.java")
}

val verifyFastTestBoundary = tasks.register("verifyFastTestBoundary") {
    group = "verification"
    description = "Verify CLI fast tests cannot launch generated project builds"
    inputs.files(fastUnitTestSources)
    doLast {
        val forbiddenTokens = listOf("ProcessBuilder", "Runtime.getRuntime().exec(", "runGeneratedTests(")
        val offenders = fastUnitTestSources.files
            .filter { source -> forbiddenTokens.any(source.readText()::contains) }
            .map { projectDir.toPath().relativize(it.toPath()).toString() }
            .sorted()
        check(offenders.isEmpty()) {
            "CLI fast test sources must not launch generated builds: ${offenders.joinToString()}"
        }
    }
}

tasks.register<Test>("fastTest") {
    group = "verification"
    description = "Run CLI unit tests without installed or generated project subprocesses"
    dependsOn(verifyFastTestBoundary)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    exclude("**/InstalledCliTest.class")
}

testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter(libs.versions.junit.get())
            dependencies {
                implementation(project())
                implementation(project(":modules:adapters:validation"))
                implementation(libs.jackson.databind)
            }
            targets.all {
                testTask.configure {
                    dependsOn(tasks.named("installDist"))
                    shouldRunAfter(tasks.test)
                    systemProperty(
                        "openapiMcp.executable",
                        layout.buildDirectory.file("install/openapi-mcp/bin/$installedExecutableName")
                            .get().asFile.absolutePath,
                    )
                    if (java17Home.isPresent) {
                        environment("GEN2SPRING_JAVA_17_HOME", java17Home.get())
                    }
                    if (java21Home.isPresent) {
                        environment("GEN2SPRING_JAVA_21_HOME", java21Home.get())
                    }
                    systemProperty("java.io.tmpdir", temporaryDir.absolutePath)
                }
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("integrationTest"))
}
