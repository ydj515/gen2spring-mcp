package io.gen2spring.mcp.app.provideregress;

import io.gen2spring.mcp.application.managed.execution.ProviderCallResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ProviderResponsePolicy {
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "content-length", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");

    private ProviderResponsePolicy() {}

    static ProviderCallResponse requireAllowed(int status, Map<String, List<String>> source, byte[] body) {
        if (status >= 300 && status <= 399 || source == null || body == null || body.length > 1_048_576) {
            throw new ProviderEgressFailure();
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        source.forEach((name, values) -> {
            String normalized = name.toLowerCase(Locale.ROOT);
            if (!HOP_BY_HOP.contains(normalized) && !normalized.startsWith("proxy-")) {
                headers.put(name, values);
            }
        });
        return new ProviderCallResponse(status, headers, body);
    }
}
