dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:application"))
    implementation(libs.swagger.parser)
    implementation(libs.bundles.jackson)
}
