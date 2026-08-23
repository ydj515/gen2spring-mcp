package io.gen2spring.mcp.adapter.emitter.springai2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReactiveRuntimeSourceRendererTest {
    @Test
    void rendersAnEndToEndReactiveProviderExecutor() {
        var profile = CompatibilityProfileRegistry.defaults()
                .find("spring-ai-2.0-java21-mvc-streamable")
                .orElseThrow();
        var context = JavaSourceRendererTest.contextWithWeatherTool(profile);
        var request = new ProgrammingModelRenderRequest(
                context,
                "com.example.weather",
                "com/example/weather",
                "Weather",
                context.tools(),
                Map.of("weather_get_forecast", "{}"),
                false,
                false,
                false);

        Map<String, String> sources = new ReactiveRuntimeSourceRenderer().render(request);
        String executor = sources.get(
                "src/main/java/com/example/weather/runtime/OpenApiOperationExecutor.java");

        assertTrue(executor.contains("WebClient"), executor);
        assertTrue(executor.contains("ConnectionProvider"), executor);
        assertTrue(executor.contains("Mono<"), executor);
        assertTrue(executor.contains("Mono.deferContextual"), executor);
        assertTrue(executor.contains("maxConnections(maxConcurrentRequests)"), executor);
        assertTrue(executor.contains("pendingAcquireMaxCount(maxQueuedRequests)"), executor);
        assertTrue(executor.contains("maxInMemorySize(responseMaxBytes)"), executor);
        assertFalse(executor.contains("RestClient"), executor);
        assertFalse(executor.contains(".block("), executor);
        assertFalse(executor.contains("Future<"), executor);
        assertFalse(sources.keySet().stream().anyMatch(path -> path.endsWith("ToolArgumentContext.java")));
    }

    @Test
    void rendersSequentialRetryPaginationAndCancellationWithoutBlocking() {
        var profile = CompatibilityProfileRegistry.defaults()
                .find("spring-ai-2.0-java21-mvc-streamable")
                .orElseThrow();
        var context = JavaSourceRendererTest.contextWithWeatherTool(profile);
        var request = new ProgrammingModelRenderRequest(
                context,
                "com.example.weather",
                "com/example/weather",
                "Weather",
                context.tools(),
                Map.of("weather_get_forecast", "{}"),
                false,
                true,
                true);

        String executor = new ReactiveRuntimeSourceRenderer().render(request).get(
                "src/main/java/com/example/weather/runtime/OpenApiOperationExecutor.java");

        assertTrue(executor.contains("Mono.defer"), executor);
        assertTrue(executor.contains("Mono.delay"), executor);
        assertTrue(executor.contains("executeWithRetry"), executor);
        assertTrue(executor.contains("executePaginated"), executor);
        assertTrue(executor.contains("ReactivePageState"), executor);
        assertTrue(executor.contains("doOnCancel"), executor);
        assertTrue(executor.contains("RuntimeTelemetry.Outcome.CANCELLED"), executor);
        assertFalse(executor.contains("Thread.sleep"), executor);
        assertFalse(executor.contains(".block("), executor);
    }
}
