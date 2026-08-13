package io.gen2spring.mcp.application.hosted.job;

public record JobQuota(int maxRunning, int maxQueued) {
    public JobQuota {
        if (maxRunning < 1 || maxQueued < 1) {
            throw new IllegalArgumentException("Hosted job quota is invalid");
        }
    }
}
