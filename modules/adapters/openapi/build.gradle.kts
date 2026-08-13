dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:application"))
    implementation(libs.swagger.parser)
    implementation(libs.bundles.jackson)
}

tasks.test {
    systemProperty("gen2spring.projectRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
}
