rootProject.name = "gen2spring-mcp"

include(
    ":modules:domain",
    ":modules:application",
    "generator-openapi",
    "generator-core",
    "generator-application",
    "generator-spring-ai-1",
    "generator-spring-ai-2",
    "generator-validation",
    "generator-cli",
    "generator-web",
)
