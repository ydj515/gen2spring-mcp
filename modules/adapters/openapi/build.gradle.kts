dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:application"))
    implementation(libs.swagger.parser)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.yaml)
}
