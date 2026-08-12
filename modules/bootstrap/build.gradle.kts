dependencies {
    api(project(":modules:domain"))
    api(project(":modules:application"))
    api(project(":modules:adapters:configuration"))
    api(project(":modules:adapters:openapi"))
    api(project(":modules:adapters:filesystem"))
    api(project(":modules:adapters:emitters:spring-ai-1"))
    api(project(":modules:adapters:emitters:spring-ai-2"))
    api(project(":modules:adapters:validation"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.yaml)
}
