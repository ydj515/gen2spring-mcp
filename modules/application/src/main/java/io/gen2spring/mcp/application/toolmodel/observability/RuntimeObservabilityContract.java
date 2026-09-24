package io.gen2spring.mcp.application.toolmodel.observability;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class RuntimeObservabilityContract {
    public static final String MCP_TOOL_CALL_OBSERVATION = "gen2spring.runtime.mcp.tool.call";
    public static final String PROVIDER_REQUEST_OBSERVATION = "gen2spring.runtime.provider.request";
    public static final String PROVIDER_RESPONSE_BYTES_METER = "gen2spring.runtime.provider.response.bytes";
    public static final String PROVIDER_EXECUTOR_ACTIVE_METER = "gen2spring.runtime.provider.executor.active";
    public static final String PROVIDER_EXECUTOR_QUEUED_METER = "gen2spring.runtime.provider.executor.queued";

    public static final String TARGET_PROFILE_TAG = "target.profile";
    public static final String OUTCOME_TAG = "outcome";
    public static final String ERROR_CATEGORY_TAG = "error.category";
    public static final String HTTP_STATUS_CLASS_TAG = "http.status.class";
    public static final List<String> MCP_TOOL_CALL_TAGS = List.of(
            TARGET_PROFILE_TAG, OUTCOME_TAG, ERROR_CATEGORY_TAG);
    public static final List<String> PROVIDER_REQUEST_TAGS = List.of(
            TARGET_PROFILE_TAG, OUTCOME_TAG, ERROR_CATEGORY_TAG, HTTP_STATUS_CLASS_TAG);
    public static final List<String> PROVIDER_RESPONSE_BYTES_TAGS = List.of(
            TARGET_PROFILE_TAG, HTTP_STATUS_CLASS_TAG);
    public static final List<String> PROVIDER_EXECUTOR_GAUGE_TAGS = List.of(TARGET_PROFILE_TAG);

    public static final String TOOL_NAME_ATTRIBUTE = "gen2spring.tool.name";
    public static final String OPERATION_ID_ATTRIBUTE = "gen2spring.operation.id";
    public static final String HTTP_METHOD_ATTRIBUTE = "http.request.method";
    public static final String HTTP_STATUS_ATTRIBUTE = "http.response.status_code";
    public static final List<String> TRACE_ONLY_ATTRIBUTES = List.of(
            TOOL_NAME_ATTRIBUTE, OPERATION_ID_ATTRIBUTE, HTTP_METHOD_ATTRIBUTE, HTTP_STATUS_ATTRIBUTE);

    public static final List<String> OUTCOME_VALUES = List.of(
            "success", "expected_error", "internal_error", "fatal");
    public static final List<String> ERROR_CATEGORY_VALUES = List.of(
            "none",
            "provider_business",
            "upstream_client",
            "upstream_server",
            "upstream_timeout",
            "upstream_unavailable",
            "upstream_protocol",
            "local_resource",
            "argument_conversion",
            "result_conversion",
            "tool_execution",
            "unexpected_runtime",
            "fatal");
    public static final List<String> HTTP_STATUS_CLASS_VALUES = List.of("2xx", "4xx", "5xx", "other", "none");

    public static final List<String> RESERVED_PROPAGATION_HEADERS = List.of(
            "traceparent", "tracestate", "baggage", "b3");
    public static final String RESERVED_B3_HEADER_PREFIX = "x-b3-";

    private static final Set<String> PROVIDER_ERROR_CATEGORIES = Set.of(
            "provider_business",
            "upstream_client",
            "upstream_server",
            "upstream_timeout",
            "upstream_unavailable",
            "upstream_protocol",
            "local_resource");
    private static final Set<String> INTERNAL_ERROR_CATEGORIES = Set.of(
            "argument_conversion", "result_conversion", "tool_execution", "unexpected_runtime");

    private RuntimeObservabilityContract() {}

    public static boolean isAllowedOutcomeCategory(String outcome, String errorCategory) {
        if (outcome == null || errorCategory == null) {
            return false;
        }
        return switch (outcome) {
            case "success" -> "none".equals(errorCategory);
            case "expected_error" -> PROVIDER_ERROR_CATEGORIES.contains(errorCategory);
            case "internal_error" -> INTERNAL_ERROR_CATEGORIES.contains(errorCategory);
            case "fatal" -> "fatal".equals(errorCategory);
            default -> false;
        };
    }

    public static boolean isReservedPropagationHeader(String headerName) {
        if (headerName == null) {
            return false;
        }
        String canonicalName = headerName.toLowerCase(Locale.ROOT);
        return RESERVED_PROPAGATION_HEADERS.contains(canonicalName)
                || canonicalName.startsWith(RESERVED_B3_HEADER_PREFIX);
    }
}
