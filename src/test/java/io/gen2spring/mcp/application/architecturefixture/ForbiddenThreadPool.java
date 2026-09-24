package io.gen2spring.mcp.application.architecturefixture;

import java.util.concurrent.ThreadPoolExecutor;

public record ForbiddenThreadPool(ThreadPoolExecutor executor) {}
