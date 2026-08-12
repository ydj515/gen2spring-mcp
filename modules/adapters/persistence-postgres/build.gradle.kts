dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:application"))
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.jdbc)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
