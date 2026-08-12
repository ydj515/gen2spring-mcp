import org.gradle.api.plugins.jvm.JvmTestSuite
import org.springframework.boot.gradle.tasks.bundling.BootJar

val java17Home = providers.environmentVariable("GEN2SPRING_JAVA_17_HOME")
val java21Home = providers.environmentVariable("GEN2SPRING_JAVA_21_HOME")

plugins {
    id("org.springframework.boot") version "3.5.16"
}

val bootJar = tasks.named<BootJar>("bootJar")

configurations.configureEach {
    exclude(group = "commons-logging", module = "commons-logging")
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    implementation(project(":modules:bootstrap"))
    implementation(libs.jackson.databind)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}

springBoot {
    mainClass.set("io.gen2spring.mcp.app.web.Gen2SpringWebApplication")
}

testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter("5.13.4")
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
