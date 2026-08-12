dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:application"))
    implementation(project(":modules:adapters:emitters:support"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.yaml)
}
