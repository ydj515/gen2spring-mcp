rootProject.name = "gen2spring-mcp"

include(
    ":modules:domain",
    ":modules:application",
    ":modules:adapters:configuration",
    ":modules:adapters:openapi",
    ":modules:adapters:filesystem",
    ":modules:bootstrap",
    ":modules:adapters:emitters:support",
    ":modules:adapters:emitters:spring-ai-1",
    ":modules:adapters:emitters:spring-ai-2",
    ":modules:adapters:validation",
    ":apps:cli",
    ":apps:web",
)
