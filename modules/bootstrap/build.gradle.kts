dependencies {
    api(project(":modules:domain"))
    api(project(":modules:application"))
    api(project(":modules:adapters:configuration"))
    implementation(project(":modules:adapters:openapi"))
    implementation(project(":modules:adapters:filesystem"))
    implementation(project(":modules:adapters:emitters:spring-ai-1"))
    implementation(project(":modules:adapters:emitters:spring-ai-2"))
    implementation(project(":modules:adapters:validation"))
    implementation(libs.bundles.jackson)
}
