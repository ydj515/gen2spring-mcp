plugins {
    application
}

dependencies {
    implementation(project(":generator-application"))
    implementation(project(":generator-domain"))
    implementation(project(":generator-openapi"))
    implementation(project(":generator-core"))
    implementation(libs.jackson.databind)
}

application {
    mainClass.set("io.gen2spring.mcp.web.Main")
    applicationName = "gen2spring-mcp-web"
}
