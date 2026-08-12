plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":modules:domain"))
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.httpclient5)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
}
