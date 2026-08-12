dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:application"))
    implementation(project(":modules:adapters:emitters:support"))
    implementation(libs.bundles.jackson)
}
