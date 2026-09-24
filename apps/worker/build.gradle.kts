import org.gradle.api.plugins.jvm.JvmTestSuite

plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.aws.sdk.bom))
    implementation(project(":modules:domain"))
    implementation(project(":modules:application"))
    implementation(project(":modules:adapters:persistence-postgres"))
    implementation(project(":modules:adapters:object-storage-s3"))
    implementation(project(":modules:adapters:cryptography"))
    implementation(project(":modules:adapters:container-runtime"))
    implementation(libs.jackson.databind)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.aws.s3)
    implementation(libs.aws.url.connection.client)

    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit)
}

springBoot {
    mainClass.set("io.gen2spring.mcp.app.worker.Gen2SpringWorkerApplication")
}

testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter(libs.versions.junit.get())
            dependencies {
                implementation(platform(libs.spring.boot.bom))
                implementation(project())
                implementation(project(":modules:domain"))
                implementation(project(":modules:application"))
                implementation(project(":modules:adapters:persistence-postgres"))
                implementation(libs.spring.jdbc)
                implementation(libs.flyway.core)
                implementation(libs.flyway.postgresql)
                implementation(libs.postgresql)
                implementation(libs.testcontainers.junit)
                implementation(libs.testcontainers.postgresql)
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(tasks.test)
                }
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("integrationTest"))
}
