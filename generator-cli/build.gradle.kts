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
    implementation(project(":generator-application"))
    implementation(project(":modules:domain"))
    implementation(project(":modules:application"))
    implementation(project(":generator-openapi"))
    implementation(project(":generator-core"))
    implementation(project(":generator-spring-ai-1"))
    implementation(project(":generator-spring-ai-2"))
    implementation(project(":generator-validation"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.yaml)
    compileOnly("org.slf4j:slf4j-api:2.0.9")
    testCompileOnly("org.slf4j:slf4j-api:2.0.9")
}

application {
    mainClass.set("io.gen2spring.mcp.cli.Main")
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
            useJUnitJupiter("5.13.4")
            dependencies {
                implementation(project())
                implementation(project(":modules:domain"))
                implementation(project(":modules:application"))
                implementation(project(":generator-validation"))
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
