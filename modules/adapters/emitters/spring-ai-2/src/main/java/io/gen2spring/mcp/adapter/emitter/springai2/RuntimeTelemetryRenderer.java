package io.gen2spring.mcp.adapter.emitter.springai2;

import io.gen2spring.mcp.adapter.emitter.support.JavaStringLiteral;

import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

final class RuntimeTelemetryRenderer {
    private final String targetProfileId;

    RuntimeTelemetryRenderer(CompatibilityProfile profile) {
        this.targetProfileId = profile.id();
    }

    String render(String packageName, List<ToolDefinition> tools) {
        String operationIds = setLiteral(tools.stream()
                .sorted(Comparator.comparing(ToolDefinition::operationId))
                .map(ToolDefinition::operationId)
                .toList());
        String toolNames = setLiteral(tools.stream()
                .sorted(Comparator.comparing(ToolDefinition::name))
                .map(ToolDefinition::name)
                .toList());
        return """
                package %s.runtime;

                import io.micrometer.core.instrument.DistributionSummary;
                import io.micrometer.core.instrument.Gauge;
                import io.micrometer.core.instrument.MeterRegistry;
                import io.micrometer.core.instrument.Timer;
                import io.micrometer.observation.Observation;
                import io.micrometer.observation.ObservationRegistry;
                import io.micrometer.tracing.Span;
                import io.micrometer.tracing.TraceContext;
                import io.micrometer.tracing.Tracer;
                import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
                import io.micrometer.tracing.handler.TracingObservationHandler;
                import io.opentelemetry.api.trace.StatusCode;
                import java.security.SecureRandom;
                import java.util.HexFormat;
                import java.util.Set;
                import java.util.concurrent.ThreadPoolExecutor;
                import java.util.concurrent.atomic.AtomicBoolean;
                import java.util.regex.Pattern;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.stereotype.Component;

                @Component
                public final class RuntimeTelemetry {
                    private static final String TOOL_CALL_NAME = "gen2spring.runtime.mcp.tool.call";
                    private static final String PROVIDER_REQUEST_NAME = "gen2spring.runtime.provider.request";
                    private static final String PROVIDER_RESPONSE_BYTES = "gen2spring.runtime.provider.response.bytes";
                    private static final String PROVIDER_EXECUTOR_ACTIVE = "gen2spring.runtime.provider.executor.active";
                    private static final String PROVIDER_EXECUTOR_QUEUED = "gen2spring.runtime.provider.executor.queued";
                    private static final String TARGET_PROFILE_TAG = "target.profile";
                    private static final String OUTCOME_TAG = "outcome";
                    private static final String ERROR_CATEGORY_TAG = "error.category";
                    private static final String HTTP_STATUS_CLASS_TAG = "http.status.class";
                    private static final String TOOL_NAME_ATTRIBUTE = "gen2spring.tool.name";
                    private static final String OPERATION_ID_ATTRIBUTE = "gen2spring.operation.id";
                    private static final String HTTP_METHOD_ATTRIBUTE = "http.request.method";
                    private static final String HTTP_STATUS_ATTRIBUTE = "http.response.status_code";
                    private static final Pattern OPERATION_ID = Pattern.compile(
                            "[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
                    private static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
                    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
                    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");
                    private static final Set<String> OPERATION_IDS = %s;
                    private static final Set<String> TOOL_NAMES = %s;
                    private static final String TARGET_PROFILE_ID = %s;
                    private static final Set<String> TARGET_PROFILE_IDS = Set.of(
                            "spring-ai-1.1-java17-maven-mvc-streamable",
                            "spring-ai-1.1-java17-mvc-streamable",
                            "spring-ai-1.1-java21-maven-mvc-streamable",
                            "spring-ai-1.1-java21-mvc-streamable",
                            "spring-ai-2.0-java17-maven-mvc-streamable",
                            "spring-ai-2.0-java17-mvc-streamable",
                            "spring-ai-2.0-java21-maven-mvc-streamable",
                            "spring-ai-2.0-java21-mvc-streamable");
                    private static final String INVALID_IDENTITY_MESSAGE =
                            "Generated telemetry identity is invalid";
                    private static final String BRIDGE_UNAVAILABLE_MESSAGE =
                            "Generated telemetry bridge is unavailable";
                    private static final java.lang.reflect.Method OTEL_SPAN_METHOD = otelSpanMethod();

                    private final ObservationRegistry observationRegistry;
                    private final MeterRegistry meterRegistry;
                    private final Tracer tracer;
                    private final SecureRandom secureRandom;
                    private final AtomicBoolean executorGaugesRegistered = new AtomicBoolean();

                    @Autowired
                    public RuntimeTelemetry(
                            ObservationRegistry observationRegistry,
                            MeterRegistry meterRegistry,
                            Tracer tracer) {
                        this(observationRegistry, meterRegistry, tracer, new SecureRandom());
                    }

                    RuntimeTelemetry(
                            ObservationRegistry observationRegistry,
                            MeterRegistry meterRegistry,
                            Tracer tracer,
                            SecureRandom secureRandom) {
                        this.meterRegistry = java.util.Objects.requireNonNull(meterRegistry);
                        this.tracer = java.util.Objects.requireNonNull(tracer);
                        java.util.Objects.requireNonNull(observationRegistry);
                        this.observationRegistry = ObservationRegistry.create();
                        this.observationRegistry.observationConfig()
                                .observationHandler(new DefaultTracingObservationHandler(this.tracer));
                        this.secureRandom = java.util.Objects.requireNonNull(secureRandom);
                        requireTargetProfile(TARGET_PROFILE_ID);
                        OPERATION_IDS.forEach(RuntimeTelemetry::requireOperationId);
                        TOOL_NAMES.forEach(RuntimeTelemetry::requireToolName);
                    }

                    public Call startToolCall(String toolName, String operationId) {
                        requireKnownTool(toolName);
                        requireKnownOperation(operationId);
                        Observation observation = Observation.createNotStarted(TOOL_CALL_NAME, observationRegistry)
                                .lowCardinalityKeyValue(TARGET_PROFILE_TAG, TARGET_PROFILE_ID)
                                .highCardinalityKeyValue(TOOL_NAME_ATTRIBUTE, toolName)
                                .highCardinalityKeyValue(OPERATION_ID_ATTRIBUTE, operationId)
                                .start();
                        return new Call(observation, false, TOOL_CALL_NAME);
                    }

                    public Call startProviderCall(String operationId, String httpMethod) {
                        requireKnownOperation(operationId);
                        String method = requireHttpMethod(httpMethod);
                        Observation observation = Observation.createNotStarted(
                                        PROVIDER_REQUEST_NAME, observationRegistry)
                                .lowCardinalityKeyValue(TARGET_PROFILE_TAG, TARGET_PROFILE_ID)
                                .highCardinalityKeyValue(OPERATION_ID_ATTRIBUTE, operationId)
                                .highCardinalityKeyValue(HTTP_METHOD_ATTRIBUTE, method)
                                .start();
                        return new Call(observation, true, PROVIDER_REQUEST_NAME);
                    }

                    public void registerExecutor(ThreadPoolExecutor executor) {
                        java.util.Objects.requireNonNull(executor);
                        if (!executorGaugesRegistered.compareAndSet(false, true)) {
                            return;
                        }
                        Gauge.builder(PROVIDER_EXECUTOR_ACTIVE, executor, ThreadPoolExecutor::getActiveCount)
                                .tag(TARGET_PROFILE_TAG, TARGET_PROFILE_ID)
                                .register(meterRegistry);
                        Gauge.builder(PROVIDER_EXECUTOR_QUEUED, executor, value -> value.getQueue().size())
                                .tag(TARGET_PROFILE_TAG, TARGET_PROFILE_ID)
                                .register(meterRegistry);
                    }

                    public void recordResponseBytes(HttpStatusClass statusClass, int byteCount) {
                        if (byteCount < 0) {
                            throw new IllegalArgumentException("Generated telemetry byte count is invalid");
                        }
                        DistributionSummary.builder(PROVIDER_RESPONSE_BYTES)
                                .tag(TARGET_PROFILE_TAG, TARGET_PROFILE_ID)
                                .tag(HTTP_STATUS_CLASS_TAG, requireStatusClass(statusClass).value())
                                .register(meterRegistry)
                                .record(byteCount);
                    }

                    public String currentTraceIdOrFallback() {
                        Span span = tracer.currentSpan();
                        if (span != null && validTraceId(span.context().traceId())) {
                            return span.context().traceId();
                        }
                        byte[] bytes = new byte[16];
                        secureRandom.nextBytes(bytes);
                        return HexFormat.of().formatHex(bytes);
                    }

                    public String currentTraceparent() {
                        Span span = tracer.currentSpan();
                        if (span == null) {
                            return null;
                        }
                        TraceContext context = span.context();
                        if (!validTraceId(context.traceId()) || !validSpanId(context.spanId())) {
                            return null;
                        }
                        return "00-" + context.traceId() + "-" + context.spanId() + "-"
                                + (Boolean.TRUE.equals(context.sampled()) ? "01" : "00");
                    }

                    public enum Outcome {
                        SUCCESS("success"),
                        EXPECTED_ERROR("expected_error"),
                        INTERNAL_ERROR("internal_error"),
                        FATAL("fatal");

                        private final String value;

                        Outcome(String value) {
                            this.value = value;
                        }

                        public String value() {
                            return value;
                        }
                    }

                    public enum ErrorCategory {
                        NONE("none"),
                        PROVIDER_BUSINESS("provider_business"),
                        UPSTREAM_CLIENT("upstream_client"),
                        UPSTREAM_SERVER("upstream_server"),
                        UPSTREAM_TIMEOUT("upstream_timeout"),
                        UPSTREAM_UNAVAILABLE("upstream_unavailable"),
                        UPSTREAM_PROTOCOL("upstream_protocol"),
                        LOCAL_RESOURCE("local_resource"),
                        ARGUMENT_CONVERSION("argument_conversion"),
                        RESULT_CONVERSION("result_conversion"),
                        TOOL_EXECUTION("tool_execution"),
                        UNEXPECTED_RUNTIME("unexpected_runtime"),
                        FATAL("fatal");

                        private final String value;

                        ErrorCategory(String value) {
                            this.value = value;
                        }

                        public String value() {
                            return value;
                        }
                    }

                    public enum HttpStatusClass {
                        SUCCESS("2xx"),
                        CLIENT_ERROR("4xx"),
                        SERVER_ERROR("5xx"),
                        OTHER("other"),
                        NONE("none");

                        private final String value;

                        HttpStatusClass(String value) {
                            this.value = value;
                        }

                        public String value() {
                            return value;
                        }
                    }

                    public final class Call {
                        private final Observation observation;
                        private final boolean provider;
                        private final String timerName;
                        private final Timer.Sample timerSample;
                        private final AtomicBoolean completed = new AtomicBoolean();

                        private Call(Observation observation, boolean provider, String timerName) {
                            this.observation = observation;
                            this.provider = provider;
                            this.timerName = timerName;
                            this.timerSample = Timer.start(meterRegistry);
                        }

                        public Observation.Scope openScope() {
                            return observation.openScope();
                        }

                        public boolean complete(
                                Outcome outcome,
                                ErrorCategory errorCategory,
                                HttpStatusClass statusClass) {
                            requireOutcomeCategory(outcome, errorCategory);
                            HttpStatusClass status = requireStatusClass(statusClass);
                            if (!provider && status != HttpStatusClass.NONE) {
                                throw new IllegalArgumentException("Generated telemetry status is invalid");
                            }
                            if (!completed.compareAndSet(false, true)) {
                                return false;
                            }
                            observation.lowCardinalityKeyValue(OUTCOME_TAG, outcome.value());
                            observation.lowCardinalityKeyValue(ERROR_CATEGORY_TAG, errorCategory.value());
                            if (provider) {
                                observation.lowCardinalityKeyValue(HTTP_STATUS_CLASS_TAG, status.value());
                            }
                            if (outcome != Outcome.SUCCESS) {
                                markErrorStatus(observation, errorCategory.value());
                            }
                            observation.stop();
                            Timer.Builder timer = Timer.builder(timerName)
                                    .tag(TARGET_PROFILE_TAG, TARGET_PROFILE_ID)
                                    .tag(OUTCOME_TAG, outcome.value())
                                    .tag(ERROR_CATEGORY_TAG, errorCategory.value());
                            if (provider) {
                                timer.tag(HTTP_STATUS_CLASS_TAG, status.value());
                            }
                            timerSample.stop(timer.register(meterRegistry));
                            return true;
                        }

                        public void responseStatus(int status) {
                            if (provider && status >= 100 && status <= 999) {
                                observation.highCardinalityKeyValue(HTTP_STATUS_ATTRIBUTE, Integer.toString(status));
                            }
                        }
                    }

                    private static void requireOutcomeCategory(Outcome outcome, ErrorCategory category) {
                        java.util.Objects.requireNonNull(outcome);
                        java.util.Objects.requireNonNull(category);
                        boolean valid = switch (outcome) {
                            case SUCCESS -> category == ErrorCategory.NONE;
                            case EXPECTED_ERROR -> switch (category) {
                                case PROVIDER_BUSINESS, UPSTREAM_CLIENT, UPSTREAM_SERVER,
                                        UPSTREAM_TIMEOUT, UPSTREAM_UNAVAILABLE, UPSTREAM_PROTOCOL,
                                        LOCAL_RESOURCE -> true;
                                default -> false;
                            };
                            case INTERNAL_ERROR -> switch (category) {
                                case ARGUMENT_CONVERSION, RESULT_CONVERSION, TOOL_EXECUTION,
                                        UNEXPECTED_RUNTIME -> true;
                                default -> false;
                            };
                            case FATAL -> category == ErrorCategory.FATAL;
                        };
                        if (!valid) {
                            throw new IllegalArgumentException("Generated telemetry result is invalid");
                        }
                    }

                    private static HttpStatusClass requireStatusClass(HttpStatusClass value) {
                        return java.util.Objects.requireNonNull(value, "Generated telemetry status is invalid");
                    }

                    private static void requireKnownOperation(String value) {
                        requireOperationId(value);
                        if (!OPERATION_IDS.contains(value)) {
                            throw new IllegalArgumentException(INVALID_IDENTITY_MESSAGE);
                        }
                    }

                    private static void requireKnownTool(String value) {
                        requireToolName(value);
                        if (!TOOL_NAMES.contains(value)) {
                            throw new IllegalArgumentException(INVALID_IDENTITY_MESSAGE);
                        }
                    }

                    private static void requireOperationId(String value) {
                        if (value == null || !OPERATION_ID.matcher(value).matches()) {
                            throw new IllegalArgumentException(INVALID_IDENTITY_MESSAGE);
                        }
                    }

                    private static void requireToolName(String value) {
                        if (value == null || !TOOL_NAME.matcher(value).matches()) {
                            throw new IllegalArgumentException(INVALID_IDENTITY_MESSAGE);
                        }
                    }

                    private static String requireHttpMethod(String value) {
                        if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(value)) {
                            throw new IllegalArgumentException(INVALID_IDENTITY_MESSAGE);
                        }
                        return value;
                    }

                    private static void requireTargetProfile(String value) {
                        if (!TARGET_PROFILE_IDS.contains(value)) {
                            throw new IllegalArgumentException(INVALID_IDENTITY_MESSAGE);
                        }
                    }

                    private static boolean validTraceId(String value) {
                        return value != null && TRACE_ID.matcher(value).matches()
                                && !"00000000000000000000000000000000".equals(value);
                    }

                    private static boolean validSpanId(String value) {
                        return value != null && SPAN_ID.matcher(value).matches()
                                && !"0000000000000000".equals(value);
                    }

                    private static void markErrorStatus(Observation observation, String category) {
                        TracingObservationHandler.TracingContext context = observation.getContext()
                                .get(TracingObservationHandler.TracingContext.class);
                        if (context != null && context.getSpan() != null && !context.getSpan().isNoop()) {
                            try {
                                ((io.opentelemetry.api.trace.Span) OTEL_SPAN_METHOD.invoke(null, context.getSpan()))
                                        .setStatus(StatusCode.ERROR, category);
                            } catch (ReflectiveOperationException failure) {
                                throw new IllegalStateException(BRIDGE_UNAVAILABLE_MESSAGE);
                            }
                        }
                    }

                    private static java.lang.reflect.Method otelSpanMethod() {
                        try {
                            return Class.forName("io.micrometer.tracing.otel.bridge.OtelSpan")
                                    .getMethod("toOtel", Span.class);
                        } catch (ReflectiveOperationException failure) {
                            throw new IllegalStateException(BRIDGE_UNAVAILABLE_MESSAGE);
                        }
                    }
                }
                """.formatted(packageName, operationIds, toolNames, JavaStringLiteral.quote(targetProfileId));
    }

    String renderReactive(String packageName, List<ToolDefinition> tools) {
        String source = render(packageName, tools);
        source = replaceReactive(
                source,
                "SUCCESS(\"success\"),\n        EXPECTED_ERROR",
                "SUCCESS(\"success\"),\n        CANCELLED(\"cancelled\"),\n        EXPECTED_ERROR");
        source = replaceReactive(
                source,
                "case SUCCESS -> category == ErrorCategory.NONE;\n            case EXPECTED_ERROR",
                "case SUCCESS, CANCELLED -> category == ErrorCategory.NONE;\n            case EXPECTED_ERROR");
        return replaceReactive(
                source,
                "if (outcome != Outcome.SUCCESS) {",
                "if (outcome != Outcome.SUCCESS && outcome != Outcome.CANCELLED) {");
    }

    private String replaceReactive(String source, String target, String replacement) {
        if (!source.contains(target)) {
            throw JavaSourceRenderer.invalid("Reactive telemetry template is inconsistent");
        }
        return source.replace(target, replacement);
    }

    private String setLiteral(List<String> values) {
        return values.stream()
                .map(JavaStringLiteral::quote)
                .collect(Collectors.joining(", ", "Set.of(", ")"));
    }
}
