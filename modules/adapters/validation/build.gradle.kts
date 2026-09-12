import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:application"))
    implementation(libs.jackson.databind)
    testImplementation(project(":modules:adapters:emitters:spring-ai-2"))
}

tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
}

tasks.register<Test>("fastTest") {
    group = "verification"
    description = "Run validation contracts without generated project builds"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    exclude("**/GeneratedWeatherValidationSmokeTest.class")
}
