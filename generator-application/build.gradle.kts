dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:application"))
    implementation(project(":generator-openapi"))
    implementation(project(":generator-core"))
    implementation(project(":generator-spring-ai-1"))
    implementation(project(":generator-spring-ai-2"))
    implementation(project(":generator-validation"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.yaml)
}
