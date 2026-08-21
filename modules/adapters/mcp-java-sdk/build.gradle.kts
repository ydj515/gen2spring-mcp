dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:application"))
    implementation(libs.jackson.databind)
    implementation(libs.mcp.java.sdk)
    implementation(libs.mcp.java.sdk.jackson2)
    implementation(libs.mcp.java.sdk.webmvc)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}
