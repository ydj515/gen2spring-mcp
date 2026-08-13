dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:application"))
    implementation(libs.jackson.databind)
}

tasks.test {
    systemProperty("gen2spring.repositoryRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
}
