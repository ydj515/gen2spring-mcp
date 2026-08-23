import org.gradle.api.plugins.jvm.JvmTestSuite

dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:application"))
    implementation(project(":modules:adapters:emitters:support"))
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
