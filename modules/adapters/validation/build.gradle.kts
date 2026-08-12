dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:application"))
    implementation(libs.jackson.databind)
    testImplementation(project(":modules:adapters:emitters:spring-ai-2"))
}
