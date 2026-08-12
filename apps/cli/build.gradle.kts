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
