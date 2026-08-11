import org.gradle.api.plugins.jvm.JvmTestSuite

val java17Home = providers.environmentVariable("GEN2SPRING_JAVA_17_HOME")
val java21Home = providers.environmentVariable("GEN2SPRING_JAVA_21_HOME")

plugins {
    application
}

dependencies {
    implementation(project(":generator-application"))
    implementation(project(":generator-domain"))
    implementation(project(":generator-openapi"))
    implementation(project(":generator-core"))
    implementation(libs.jackson.databind)
}

application {
    mainClass.set("io.gen2spring.mcp.web.Main")
    applicationName = "gen2spring-mcp-web"
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
                    dependsOn(tasks.named("installDist"))
                    shouldRunAfter(tasks.test)
                    systemProperty(
                        "gen2springWeb.executable",
                        layout.buildDirectory.file(
                            "install/gen2spring-mcp-web/bin/gen2spring-mcp-web",
                        ).get().asFile.absolutePath,
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
