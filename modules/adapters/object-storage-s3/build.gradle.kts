dependencies {
    implementation(project(":modules:application"))
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.s3)
    implementation(libs.aws.url.connection.client)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.testcontainers.junit)
}
