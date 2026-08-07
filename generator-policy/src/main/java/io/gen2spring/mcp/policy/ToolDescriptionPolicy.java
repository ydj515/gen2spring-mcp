package io.gen2spring.mcp.policy;

public final class ToolDescriptionPolicy {
    public String describe(String override, String summary, String description) {
        if (hasText(override)) {
            return override.trim();
        }
        if (hasText(summary) && hasText(description)) {
            return summary.trim() + "\n\n" + description.trim();
        }
        if (hasText(summary)) {
            return summary.trim();
        }
        if (hasText(description)) {
            return description.trim();
        }
        return "";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
