package io.gen2spring.mcp.app.provideregress.egress;

import io.gen2spring.mcp.application.managed.execution.ProviderCallRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ProviderRequestPolicy {
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "content-length", "host", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");

    private ProviderRequestPolicy() {}

    static ProviderCallRequest requireAllowed(ProviderCallRequest request) {
        if (request == null) throw failed();
        URI uri = request.uri();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort() == -1 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
        if (!("http".equals(scheme) || "https".equals(scheme)) || !(port == 80 || port == 443)
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw failed();
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        request.headers().forEach((name, values) -> {
            String normalized = name.toLowerCase(Locale.ROOT);
            if (!HOP_BY_HOP.contains(normalized) && !normalized.startsWith("proxy-")) {
                headers.put(name, values);
            }
        });
        return new ProviderCallRequest(request.method(), uri, headers, request.body());
    }

    private static ProviderEgressFailure failed() {
        return new ProviderEgressFailure();
    }
}
