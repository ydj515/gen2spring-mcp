import org.gradle.api.plugins.jvm.JvmTestSuite
import org.springframework.boot.gradle.tasks.bundling.BootJar

val java17Home = providers.environmentVariable("GEN2SPRING_JAVA_17_HOME")
val java21Home = providers.environmentVariable("GEN2SPRING_JAVA_21_HOME")

plugins {
    alias(libs.plugins.spring.boot)
}

val bootJar = tasks.named<BootJar>("bootJar")

configurations.configureEach {
    exclude(group = "commons-logging", module = "commons-logging")
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.aws.sdk.bom))
    implementation(project(":modules:bootstrap"))
    implementation(project(":modules:application"))
    implementation(project(":modules:adapters:persistence-postgres"))
    implementation(project(":modules:adapters:object-storage-s3"))
    implementation(project(":modules:adapters:cryptography"))
    implementation(libs.jackson.databind)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.thymeleaf)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.client)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.flyway.core)
    implementation(libs.aws.s3)
    implementation(libs.aws.url.connection.client)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.bootstrap)
    implementation(libs.bootstrap.icons)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.archunit)
}

springBoot {
    mainClass.set("io.gen2spring.mcp.app.web.Gen2SpringWebApplication")
}

testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter(libs.versions.junit.get())
            dependencies {
                implementation(project())
                implementation(libs.jackson.databind)
            }
            targets.all {
                testTask.configure {
                    dependsOn(bootJar)
                    shouldRunAfter(tasks.test)
                    systemProperty(
                        "gen2springWeb.bootJar",
                        bootJar.flatMap { it.archiveFile }.get().asFile.absolutePath,
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
