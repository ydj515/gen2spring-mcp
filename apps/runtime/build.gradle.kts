plugins {
    alias(libs.plugins.spring.boot)
}

import org.gradle.api.plugins.jvm.JvmTestSuite

dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:application"))
    implementation(project(":modules:adapters:cryptography"))
    implementation(project(":modules:adapters:persistence-postgres"))
    implementation(project(":modules:adapters:provider-egress"))
    implementation(project(":modules:adapters:mcp-java-sdk"))
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.mcp.java.sdk)
    implementation(libs.mcp.java.sdk.jackson2)
    implementation(libs.mcp.java.sdk.webmvc)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
}

testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter(libs.versions.junit.get())
            dependencies {
                implementation(project())
                implementation(project(":modules:domain"))
                implementation(project(":modules:application"))
                implementation(project(":modules:adapters:mcp-java-sdk"))
                implementation(project(":modules:adapters:persistence-postgres"))
                implementation(platform(libs.spring.boot.bom))
                implementation(libs.jackson.databind)
                implementation(libs.flyway.core)
                implementation(libs.flyway.postgresql)
                implementation(libs.mcp.java.sdk)
                implementation(libs.mcp.java.sdk.jackson2)
                implementation(libs.mcp.java.sdk.webmvc)
                implementation(libs.spring.boot.starter.test)
                implementation(libs.spring.boot.starter.jdbc)
                implementation(libs.spring.boot.starter.web)
                implementation(libs.postgresql)
                implementation(libs.testcontainers.junit)
                implementation(libs.testcontainers.postgresql)
            }
            targets.all {
                testTask.configure { shouldRunAfter(tasks.test) }
            }
        }
    }
}

tasks.check { dependsOn(testing.suites.named("integrationTest")) }
