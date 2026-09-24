package io.gen2spring.mcp.app.web.presentation.local;

import io.gen2spring.mcp.app.web.presentation.error.WebErrorMapper;

import java.util.regex.Pattern;

final class WebApiRoutes {
    private static final Pattern OPAQUE_IDENTIFIER = Pattern.compile("[a-f0-9]{64}");

    private WebApiRoutes() {}

    static String requireIdentifier(String value) {
        if (value == null || !OPAQUE_IDENTIFIER.matcher(value).matches()) {
            throw WebErrorMapper.failure(404, "ROUTE_NOT_FOUND", "HTTP", "The route was not found");
        }
        return value;
    }
}
