dependencies {
    implementation(project(":generator-domain"))
    implementation(libs.jackson.databind)
    testImplementation(project(":generator-spring-ai-2"))
}
