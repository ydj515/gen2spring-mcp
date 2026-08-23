import org.gradle.api.tasks.testing.Test

dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:application"))
    implementation(libs.jackson.databind)
    testImplementation(project(":modules:adapters:emitters:spring-ai-2"))
}

tasks.register<Test>("fastTest") {
    group = "verification"
    description = "Run validation contracts without generated project builds"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    exclude("**/GeneratedWeatherValidationSmokeTest.class")
}
