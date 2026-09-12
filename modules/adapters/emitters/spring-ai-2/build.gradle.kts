import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.tasks.testing.Test

dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:application"))
    implementation(project(":modules:adapters:emitters:support"))
    implementation(project(":modules:adapters:emitters:mcp-runtime"))
    implementation(libs.bundles.jackson)
}

testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter(libs.versions.junit.get())
            dependencies {
                implementation(project())
                implementation(project(":modules:domain"))
                implementation(project(":modules:application"))
                implementation(project(":modules:adapters:emitters:support"))
                implementation(files(sourceSets.test.get().output))
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(tasks.test)
                }
            }
        }
    }
}

tasks.register<Test>("fastTest") {
    group = "verification"
    description = "Run Spring AI 2 source rendering tests without generated project builds"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
}
