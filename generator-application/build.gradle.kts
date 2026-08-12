dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:application"))
    implementation(project(":modules:adapters:configuration"))
    implementation(project(":modules:adapters:openapi"))
    implementation(project(":modules:adapters:filesystem"))
    implementation(project(":modules:adapters:emitters:spring-ai-1"))
    implementation(project(":modules:adapters:emitters:spring-ai-2"))
    implementation(project(":modules:adapters:validation"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.yaml)
}
