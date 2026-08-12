rootProject.name = "gen2spring-mcp"

include(
    ":modules:domain",
    ":modules:application",
    ":modules:adapters:configuration",
    ":modules:adapters:openapi",
    ":modules:adapters:filesystem",
    "generator-application",
    "generator-spring-ai-1",
    "generator-spring-ai-2",
    ":modules:adapters:validation",
    "generator-cli",
    "generator-web",
)
