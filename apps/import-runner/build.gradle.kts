plugins {
    application
}

application {
    mainClass.set("io.gen2spring.mcp.app.importer.ImportRunnerApplication")
}

dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:application"))
    implementation(project(":modules:adapters:openapi"))
}
